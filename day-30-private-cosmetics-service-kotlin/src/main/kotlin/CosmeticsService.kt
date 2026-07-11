import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.decodeFromString
import java.time.Duration

interface CosmeticsUseCases {
    suspend fun health(): HealthResponse
    suspend fun analyzeText(request: AnalyzeTextRequest): AnalyzeResponse
    suspend fun analyzeName(request: AnalyzeNameRequest): AnalyzeResponse
    suspend fun recognizePhoto(photo: UploadedPhoto): OcrResponse
    suspend fun chat(request: ChatRequest): ChatResponse
    fun deleteSession(id: String): Boolean
}

class LocalCosmeticsService(
    private val config: AppConfig,
    private val knowledge: IngredientKnowledgeBase,
    private val gateway: LocalLlmGateway = OllamaClient(config),
    private val ocr: OcrEngine = TesseractOcrEngine(config),
    private val inferenceGate: InferenceGate = InferenceGate(
        config.maxConcurrentInference,
        config.inferenceQueueCapacity,
    ),
    private val ocrGate: InferenceGate = InferenceGate(
        maxConcurrent = 1,
        maxQueued = config.inferenceQueueCapacity,
    ),
    private val sessions: AnalysisSessionStore = AnalysisSessionStore(
        config.maxSessions,
        config.sessionTtl,
        config.maxChatMessages,
    ),
) : CosmeticsUseCases, AutoCloseable {
    private data class CachedReadiness(val response: HealthResponse, val expiresAtNanos: Long)

    private val healthMutex = Mutex()
    @Volatile
    private var cachedReadiness: CachedReadiness? = null

    override suspend fun health(): HealthResponse {
        cachedHealth()?.let { return it }
        healthMutex.lock()
        return try {
            cachedHealth()?.let { return it }
            val (status, ocrReady) = coroutineScope {
                val modelProbe = async(Dispatchers.IO) { runCatching { gateway.diagnose() }.getOrNull() }
                val ocrProbe = async { runCatching { ocr.diagnose() }.getOrDefault(false) }
                modelProbe.await() to ocrProbe.await()
            }
            val modelInstalled = status?.hasModel(config.model) == true
            HealthResponse(
                status = if (modelInstalled && ocrReady) "ready" else "degraded",
                model = config.model,
                ollamaVersion = status?.version,
                modelInstalled = modelInstalled,
                ocrReady = ocrReady,
                ingredientCards = knowledge.ingredientCount,
                catalogProducts = knowledge.productCount,
                photoOcrProvider = ocr.currentProvider(),
            ).also { response ->
                cachedReadiness = CachedReadiness(
                    response,
                    System.nanoTime() + Duration.ofSeconds(10).toNanos(),
                )
            }
        } finally {
            healthMutex.unlock()
        }
    }

    override suspend fun analyzeText(request: AnalyzeTextRequest): AnalyzeResponse {
        val inci = validateInci(request.inciText)
        val productName = request.productName?.trim()?.takeIf { it.isNotBlank() }?.also {
            if (it.length > 200) throw invalidInput("product_name_too_long", "Название продукта длиннее 200 символов.")
            rejectInstructionText(it)
        }
        val productTypeHint = validateProductTypeHint(request.productTypeHint)
            ?: ProductTypeResolver.resolve(productName)
        validateProfile(request.profile)
        return analyze(
            inputType = "text",
            inciText = inci,
            productName = productName,
            matchedProduct = null,
            version = null,
            productTypeHint = productTypeHint,
            productTypeSource = productTypeHint?.let { "user_hint" },
            profile = request.profile,
        )
    }

    override suspend fun analyzeName(request: AnalyzeNameRequest): AnalyzeResponse {
        val name = request.name.trim()
        if (name.length !in 2..200) throw invalidInput("invalid_product_name", "Укажите название продукта длиной от 2 до 200 символов.")
        rejectInstructionText(name)
        validateProfile(request.profile)
        val product = knowledge.findProductExact(name) ?: throw ApiProblem(
            HttpStatusCode.NotFound,
            "product_not_found",
            "Продукт не найден в локальном каталоге, поэтому сервис не будет угадывать его состав.",
            "Загрузите фото этикетки или вставьте актуальный INCI.",
        )
        val version = knowledge.latestVersion(product)
        return analyze(
            inputType = "name",
            inciText = version.inci,
            productName = "${product.brand} ${product.name}",
            matchedProduct = product,
            version = version,
            productTypeHint = product.category,
            productTypeSource = "local_catalog",
            profile = request.profile,
        )
    }

    override suspend fun recognizePhoto(photo: UploadedPhoto): OcrResponse {
        val result = try {
            ocrGate.withPermit { ocr.recognize(photo) }
        } catch (error: ApiProblem) {
            throw error
        } catch (error: OcrUnavailableException) {
            throw ApiProblem(
                HttpStatusCode.ServiceUnavailable,
                "ocr_unavailable",
                "Локальный OCR недоступен.",
                "Установите tesseract-ocr, tesseract-ocr-eng и tesseract-ocr-rus или вставьте INCI вручную.",
            )
        }
        if (result.text.length > config.maxInciChars) {
            throw ApiProblem(
                HttpStatusCode.PayloadTooLarge,
                "ocr_text_too_long",
                "OCR распознал слишком много текста для одного состава.",
                "Обрежьте фотографию до области с INCI.",
            )
        }
        return OcrResponse(
            fileName = photo.fileName,
            format = photo.format,
            width = photo.width,
            height = photo.height,
            extractedText = result.text,
            quality = result.quality,
            provider = result.provider,
            uncertainFragments = result.uncertainFragments,
            notice = result.notice,
        )
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val message = request.message.trim()
        if (message.length !in 2..config.maxChatChars) {
            throw invalidInput("invalid_chat_message", "Вопрос должен содержать от 2 до ${config.maxChatChars} символов.")
        }
        if (InciParser.runCatchingInstruction(message)) {
            throw invalidInput("prompt_injection_detected", "Вопрос похож на попытку изменить системные инструкции.")
        }
        val session = sessions.require(request.sessionId)
        val (systemPrompt, userPrompt) = PromptBuilder.chatPrompt(session, message)
        PromptBuilder.ensureBudget(systemPrompt, userPrompt, config)
        val reply = callModel(systemPrompt, userPrompt, PromptBuilder.chatSchema())
        val decision = groundChatDecision(parseChatDecision(reply.content), message)
        validateChatDecision(decision)
        val answer = assembleChatAnswer(decision, session)
        sessions.append(session.id, message, answer.answer)
        return ChatResponse(
            sessionId = session.id,
            reply = answer,
            sources = knowledge.evidenceSources(answer.sourceIds),
            model = reply.metrics(),
        )
    }

    override fun deleteSession(id: String): Boolean = sessions.delete(id)

    private suspend fun analyze(
        inputType: String,
        inciText: String,
        productName: String?,
        matchedProduct: CatalogProduct?,
        version: ProductVersion?,
        productTypeHint: String?,
        productTypeSource: String?,
        profile: SkinProfile,
    ): AnalyzeResponse {
        val pack = knowledge.retrieve(inciText, config.maxKnowledgeCards)
        val input = AnalysisInputSummary(
            type = inputType,
            productName = productName,
            matchedProductId = matchedProduct?.id,
            catalogVersion = version?.version,
            productTypeHint = productTypeHint,
            productTypeSource = productTypeSource,
            inciText = inciText,
            parsedIngredientCount = pack.parsed.ingredients.size,
            recognizedIngredientCount = pack.recognized.size,
            matchedFragmentCount = pack.matchedFragmentCount,
            evidenceIngredientCount = pack.evidenceIngredients.size,
            unknownIngredients = pack.unknown,
            ingredientCorrections = pack.corrections,
        )
        if (pack.recognized.isEmpty()) return needsMoreData(input)

        val modelCards = pack.evidenceIngredients.map { it.card }
        val reportCards = pack.recognized.map { it.card }
        val manualReviewReason = CoveragePolicy.manualReviewReason(input, reportCards)
        if (manualReviewReason != null) {
            val report = ReportAssembler.partial(input, profile, reportCards, pack.sources, manualReviewReason)
            validateReport(report, input, reportCards, pack.sources)
            val citedIds = ReportAssembler.citedSourceIds(report)
            return AnalyzeResponse(
                input = input,
                report = report,
                sources = knowledge.evidenceSources(citedIds),
            )
        }
        val (systemPrompt, userPrompt) = PromptBuilder.analysisPrompt(input, profile, modelCards)
        PromptBuilder.ensureBudget(systemPrompt, userPrompt, config)
        val reply = callModel(systemPrompt, userPrompt, PromptBuilder.reportSchema(input, modelCards))
        val decision = groundAnalysisDecision(parseAnalysisDecision(reply.content), input, modelCards)
        validateAnalysisDecision(decision, input, modelCards)
        val report = ReportAssembler.assemble(decision, input, profile, reportCards, pack.sources)
        validateReport(report, input, reportCards, pack.sources)
        val citedIds = ReportAssembler.citedSourceIds(report)
        val session = sessions.create(input, profile, report, reportCards, pack.sources)
        return AnalyzeResponse(
            sessionId = session.id,
            input = input,
            report = report,
            sources = knowledge.evidenceSources(citedIds),
            model = reply.metrics(),
        )
    }

    private fun needsMoreData(input: AnalysisInputSummary): AnalyzeResponse = AnalyzeResponse(
        input = input,
        report = CosmeticsReport(
            status = "needs_clarification",
            productType = input.productTypeHint ?: "unknown",
            summary = "В локальной базе не нашлось достаточно распознанных ингредиентов для обоснованного анализа.",
            highlights = emptyList(),
            skinFit = emptyList(),
            routine = RoutineAdvice(emptyList(), "unknown", "Сверьте INCI на упаковке и исправьте OCR.", "unknown", emptyList()),
            keyIngredients = emptyList(),
            cautions = emptyList(),
            limitations = listOf("Сервис не использует знания модели без локальных источников."),
            confidence = "low",
            sourceIds = emptyList(),
            disclaimer = ReportAssembler.DISCLAIMER,
        ),
        sources = emptyList(),
    )

    private suspend fun callModel(
        systemPrompt: String,
        userPrompt: String,
        schema: kotlinx.serialization.json.JsonObject,
    ): OllamaReply = try {
        inferenceGate.withPermit {
            withContext(Dispatchers.IO) { gateway.chat(systemPrompt, userPrompt, schema) }
        }
    } catch (error: ApiProblem) {
        throw error
    } catch (error: OllamaUnavailableException) {
        throw ApiProblem(HttpStatusCode.ServiceUnavailable, "ollama_unavailable", "Локальный Ollama недоступен.", "Запустите: ollama serve")
    } catch (error: OllamaHttpException) {
        if (error.statusCode == 404) {
            throw ApiProblem(HttpStatusCode.ServiceUnavailable, "model_missing", "Локальная модель '${config.model}' не установлена.", "Выполните: ollama pull ${config.model}")
        }
        throw ApiProblem(HttpStatusCode.BadGateway, "ollama_http_error", "Ollama вернул ошибку при локальном инференсе.")
    } catch (error: OllamaProtocolException) {
        throw ApiProblem(HttpStatusCode.BadGateway, "ollama_invalid_response", error.message ?: "Некорректный ответ Ollama.")
    }

    private fun parseAnalysisDecision(content: String): ModelAnalysisDecision = try {
        AppJson.strict.decodeFromString(content)
    } catch (_: Exception) {
        throw ApiProblem(HttpStatusCode.BadGateway, "invalid_report_json", "Модель нарушила строгий JSON-контракт отчёта.")
    }

    private fun parseChatDecision(content: String): ModelChatDecision = try {
        AppJson.strict.decodeFromString(content)
    } catch (_: Exception) {
        throw ApiProblem(HttpStatusCode.BadGateway, "invalid_chat_json", "Модель нарушила строгий JSON-контракт чата.")
    }

    private fun groundAnalysisDecision(
        decision: ModelAnalysisDecision,
        input: AnalysisInputSummary,
        cards: List<IngredientCard>,
    ): ModelAnalysisDecision {
        if (decision.status !in setOf("answered", "needs_clarification")) invalidModel("Unknown analysis status.")
        val productTypes = setOf("face_cleanser", "face_toner", "face_serum", "face_moisturizer", "face_sunscreen", "other", "unknown")
        if (decision.productType !in productTypes) invalidModel("Unknown product type.")
        if (decision.confidence !in setOf("low", "medium", "high")) invalidModel("Unknown confidence.")
        if (decision.keyIngredientIds.size > 6 || decision.keyIngredientIds.distinct().size != decision.keyIngredientIds.size) {
            invalidModel("Key ingredient IDs are duplicated or exceed the limit.")
        }
        val allowedCards = cards.map { it.id }.toSet()
        if (decision.keyIngredientIds.any { it !in allowedCards }) invalidModel("Decision contains an ungrounded ingredient ID.")

        val coverageCeiling = when {
            input.parsedIngredientCount <= 0 || input.matchedFragmentCount * 2 < input.parsedIngredientCount -> "low"
            input.unknownIngredients.isNotEmpty() ||
                input.recognizedIngredientCount < input.parsedIngredientCount ||
                input.evidenceIngredientCount < input.recognizedIngredientCount -> "medium"
            else -> "high"
        }
        val confidenceOrder = mapOf("low" to 0, "medium" to 1, "high" to 2)
        val boundedConfidence = if (confidenceOrder.getValue(decision.confidence) <= confidenceOrder.getValue(coverageCeiling)) {
            decision.confidence
        } else {
            coverageCeiling
        }
        val resolvedType = input.productTypeHint ?: return decision.copy(confidence = boundedConfidence)
        val cardsById = cards.associateBy { it.id }
        val deterministicIds = cards.sortedByDescending(::keyIngredientPriority)
            .filter { keyIngredientPriority(it) > 0 }
            .map { it.id }
        val modelIds = decision.keyIngredientIds.filter { id -> cardsById[id]?.let(::keyIngredientPriority) ?: 0 > 0 }
        val groundedIds = (deterministicIds + modelIds).distinct().take(6)
        if (groundedIds.isEmpty()) return decision.copy(productType = resolvedType)
        return decision.copy(
            status = "answered",
            productType = resolvedType,
            keyIngredientIds = groundedIds,
            confidence = boundedConfidence,
        )
    }

    private fun validateAnalysisDecision(
        decision: ModelAnalysisDecision,
        input: AnalysisInputSummary,
        cards: List<IngredientCard>,
    ) {
        if (decision.status !in setOf("answered", "needs_clarification")) invalidModel("Unknown analysis status.")
        val productTypes = setOf("face_cleanser", "face_toner", "face_serum", "face_moisturizer", "face_sunscreen", "other", "unknown")
        if (decision.productType !in productTypes) invalidModel("Unknown product type.")
        if (input.productTypeHint != null && decision.productType != input.productTypeHint) {
            invalidModel("Decision contradicts the resolved product type.")
        }
        if (decision.confidence !in setOf("low", "medium", "high")) invalidModel("Unknown confidence.")
        if (decision.keyIngredientIds.size > 6 || decision.keyIngredientIds.distinct().size != decision.keyIngredientIds.size) {
            invalidModel("Key ingredient IDs are duplicated or exceed the limit.")
        }
        val allowedCards = cards.map { it.id }.toSet()
        if (decision.keyIngredientIds.any { it !in allowedCards }) invalidModel("Decision contains an ungrounded ingredient ID.")
        if (decision.status == "answered" && decision.keyIngredientIds.isEmpty()) {
            invalidModel("Answered decision has no grounded key ingredients.")
        }
    }

    private fun validateReport(
        report: CosmeticsReport,
        input: AnalysisInputSummary,
        cards: List<IngredientCard>,
        sources: List<KnowledgeSource>,
    ) {
        if (report.status !in setOf("answered", "needs_clarification")) invalidModel("Unknown report status.")
        val productTypes = setOf("face_cleanser", "face_toner", "face_serum", "face_moisturizer", "face_sunscreen", "other", "unknown")
        if (report.productType !in productTypes) invalidModel("Unknown product type.")
        if (input.productTypeHint != null && report.productType != input.productTypeHint) invalidModel("Report contradicts the resolved product type.")
        if (report.confidence !in setOf("low", "medium", "high")) invalidModel("Unknown confidence.")
        if (report.routine.rinseOff !in setOf("yes", "no", "unknown")) invalidModel("Unknown rinseOff value.")
        if (report.summary.isBlank() || report.disclaimer.isBlank()) invalidModel("Required report text is blank.")

        val allowedSources = sources.map { it.id }.toSet()
        val allowedCards = cards.associateBy { it.id }
        fun validateSources(ids: List<String>, place: String) {
            if (ids.any { it !in allowedSources }) invalidModel("$place contains an ungrounded source ID.")
        }
        validateSources(report.sourceIds, "report")
        validateSources(report.summarySourceIds, "summary")
        validateSources(report.routine.sourceIds, "routine")
        (report.highlights + report.skinFit + report.cautions).forEach { claim ->
            if (claim.text.isBlank()) invalidModel("Grounded claim text is blank.")
            if (claim.sourceIds.isEmpty()) invalidModel("Grounded claim has no claim-level evidence.")
            validateSources(claim.sourceIds, "claim")
        }
        report.keyIngredients.forEach { insight ->
            val card = allowedCards[insight.ingredientId] ?: invalidModel("Key ingredient is absent from retrieved facts.")
            if (insight.sourceIds.isEmpty() || insight.sourceIds.any { it !in card.sourceIds || it !in allowedSources }) {
                invalidModel("Key ingredient contains an ungrounded source ID.")
            }
        }
        val citedIds = ReportAssembler.citedSourceIds(report)
        if (report.status == "answered" && citedIds.isEmpty()) invalidModel("Answered report has no sources.")
        if (report.sourceIds.toSet() != citedIds.toSet()) invalidModel("Report source registry does not match displayed claims.")
    }

    private fun validateChatDecision(decision: ModelChatDecision) {
        val topics = setOf("overview", "routine", "skin_fit", "key_ingredients", "cautions", "limitations", "unknown")
        if (decision.status !in setOf("answered", "needs_clarification")) invalidModel("Unknown chat status.")
        if (decision.topic !in topics) invalidModel("Unknown chat topic.")
        if (decision.status == "answered" && decision.topic == "unknown") {
            invalidModel("Answered chat decision has no grounded topic.")
        }
        if (decision.status == "needs_clarification" && decision.topic != "unknown") {
            invalidModel("Clarification decision must use the unknown topic.")
        }
    }

    private fun assembleChatAnswer(decision: ModelChatDecision, session: AnalysisSession): ChatAnswer {
        val cardsById = session.cards.associateBy { it.id }
        fun keyIngredientText(insights: List<IngredientInsight>): String = insights.joinToString("; ") { insight ->
            val name = cardsById[insight.ingredientId]?.inciName ?: insight.ingredientId
            val detail = insight.whyItMatters
                .removePrefix("Функции в локальной карточке: ")
                .substringBefore(". Роль зависит")
                .trimEnd('.')
            "$name — $detail"
        }
        val overviewHighlights = session.report.highlights.take(2)
        val overviewIngredients = session.report.keyIngredients.take(3)
        val allDisplayedIngredients = session.report.keyIngredients.take(6)
        val answer = when (decision.topic) {
            "overview" -> listOf(
                session.report.summary,
                overviewHighlights.joinToString(" ") { it.text },
                keyIngredientText(overviewIngredients).takeIf(String::isNotBlank)?.let { "Ключевое: $it." }.orEmpty(),
                "Когда и как: ${session.report.routine.step}; ${session.report.routine.directions}",
            ).filter(String::isNotBlank).joinToString(" ")
            "routine" -> listOf(
                "Время: ${session.report.routine.timeOfDay.joinToString().ifBlank { "уточните по этикетке" }}.",
                "Шаг: ${session.report.routine.step}.",
                session.report.routine.directions,
                "Смывание: ${session.report.routine.rinseOff}.",
            ).joinToString(" ")
            "skin_fit" -> session.report.skinFit.joinToString(" ") { it.text }.ifBlank {
                "По локальным фактам нельзя обоснованно выделить тип кожи; ориентируйтесь на этикетку и переносимость."
            }
            "key_ingredients" -> keyIngredientText(allDisplayedIngredients).ifBlank { "В текущем отчёте нет выбранных ключевых ингредиентов." }
            "cautions" -> session.report.cautions.joinToString(" ") { it.text }.ifBlank {
                "Специальных оговорок в локальных карточках нет; индивидуальная реакция всё равно возможна."
            }
            "limitations" -> session.report.limitations.joinToString(" ")
            else -> "В локальных фактах этого анализа нет данных для ответа. Уточните вопрос о рутине, типе кожи, ингредиентах или ограничениях."
        }
        val sourceIds = if (decision.status != "answered") emptyList() else when (decision.topic) {
            "overview" -> (
                session.report.summarySourceIds +
                    overviewHighlights.flatMap { it.sourceIds } +
                    overviewIngredients.flatMap { it.sourceIds }
                ).distinct()
            "skin_fit" -> session.report.skinFit.flatMap { it.sourceIds }.distinct()
            "cautions" -> session.report.cautions.flatMap { it.sourceIds }.distinct()
            "key_ingredients" -> allDisplayedIngredients.flatMap { it.sourceIds }.distinct()
            "routine" -> session.report.routine.sourceIds
            else -> emptyList()
        }
        return ChatAnswer(
            status = decision.status,
            answer = answer,
            sourceIds = sourceIds,
            limitations = if (decision.status == "needs_clarification") {
                listOf("Сервис не дополняет локальные факты знаниями модели.")
            } else session.report.limitations.take(3),
        )
    }

    private fun groundChatDecision(decision: ModelChatDecision, message: String): ModelChatDecision {
        val text = message.lowercase()
        if (OUT_OF_SCOPE_CHAT_TERMS.any(text::contains)) {
            return ModelChatDecision(status = "needs_clarification", topic = "unknown")
        }
        if (decision.status == "answered" && decision.topic != "unknown") return decision
        val topic = when {
            listOf("для чего", "зачем", "что делает", "расскажи о", "обзор").any(text::contains) -> "overview"
            listOf("когда", "как использовать", "как применять", "нанос", "шаг", "смыва").any(text::contains) -> "routine"
            listOf("подойд", "тип кожи", "моей коже", "чувствительн", "сухой коже", "жирной коже").any(text::contains) -> "skin_fit"
            listOf("ингредиент", "состав", "актив").any(text::contains) -> "key_ingredients"
            listOf("осторож", "риск", "раздраж", "аллерг", "опас").any(text::contains) -> "cautions"
            listOf("огранич", "точность", "неизвест").any(text::contains) -> "limitations"
            else -> null
        }
        return topic?.let { ModelChatDecision(status = "answered", topic = it) } ?: decision
    }

    private fun keyIngredientPriority(card: IngredientCard): Int = when {
        card.presentationRole == "technical" -> 0
        card.id in KEY_INGREDIENT_PRIORITIES -> 120
        card.functions.any { it in setOf("uv_filter", "exfoliant", "keratolytic", "cleansing") } -> 100
        card.functions.any { it in setOf("humectant", "emollient", "skin_protecting") } -> 70
        card.functions.contains("antioxidant") -> 55
        card.id == "aqua" || card.functions.all(FORMULATION_ONLY_FUNCTIONS::contains) -> 0
        card.functions.any { it in setOf("perfuming", "preservative") } -> 0
        else -> 30
    }

    private fun validateInci(value: String): String {
        val text = value.trim()
        if (text.length !in 3..config.maxInciChars) {
            throw invalidInput("invalid_inci", "INCI должен содержать от 3 до ${config.maxInciChars} символов.")
        }
        if (InciParser.parse(text).ingredients.isEmpty()) throw invalidInput("invalid_inci", "Не удалось выделить ингредиенты из INCI.")
        return text
    }

    private fun validateProfile(profile: SkinProfile) {
        if (profile.skinType != null && profile.skinType !in setOf("dry", "oily", "combination", "normal", "sensitive", "unknown")) {
            throw invalidInput("invalid_skin_type", "Неизвестный тип кожи.")
        }
        if (profile.allergies.size > 10 || profile.goals.size > 10 ||
            (profile.allergies + profile.goals).any { it.isBlank() || it.length > 100 }) {
            throw invalidInput("invalid_profile", "Профиль содержит слишком много или слишком длинные значения.")
        }
        (profile.allergies + profile.goals).forEach(::rejectInstructionText)
    }

    private fun validateProductTypeHint(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (normalized !in ProductTypeResolver.supportedHints) {
            throw invalidInput("invalid_product_type", "Выберите тип продукта из предложенного списка.")
        }
        return normalized
    }

    private fun rejectInstructionText(value: String) {
        if (InciParser.runCatchingInstruction(value)) {
            throw invalidInput("prompt_injection_detected", "Пользовательское поле похоже на попытку изменить системные инструкции.")
        }
    }

    private fun cachedHealth(): HealthResponse? {
        val cached = cachedReadiness ?: return null
        return cached.response.takeIf { System.nanoTime() < cached.expiresAtNanos }
    }

    override fun close() {
        sessions.close()
    }

    private fun invalidInput(code: String, message: String) = ApiProblem(HttpStatusCode.UnprocessableEntity, code, message)

    private fun invalidModel(message: String): Nothing = throw ApiProblem(
        HttpStatusCode.BadGateway,
        "ungrounded_model_response",
        "Модель вернула решение, которое не прошло серверную проверку allowlist и локальных фактов.",
        message,
    )

    private companion object {
        val OUT_OF_SCOPE_CHAT_TERMS = setOf(
            "беремен", "кормлен", "лекар", "диагноз", "дерматит", "экзем", "псориаз",
            "онколог", "болезн", "лечение", "лечить", "ребен", "детям", "врач",
        )
        val FORMULATION_ONLY_FUNCTIONS = setOf(
            "solvent", "preservative", "buffering", "chelating", "viscosity_controlling",
            "colorant", "opacifying", "antifoaming", "perfuming", "emulsifying",
        )
        val KEY_INGREDIENT_PRIORITIES = REPORTABLE_ACTIVE_IDS
    }
}

fun InciParser.runCatchingInstruction(text: String): Boolean = runCatching { parse(text); false }
    .getOrElse { error -> error is ApiProblem && error.code == "prompt_injection_detected" }

private val REPORTABLE_ACTIVE_IDS = setOf(
    "niacinamide", "tranexamic-acid", "arbutin", "retinol", "ascorbic-acid",
    "salicylic-acid", "glycolic-acid", "lactic-acid", "zinc-oxide", "titanium-dioxide",
)

private enum class ManualReviewReason { LOW_COVERAGE, NO_REPORTABLE_EVIDENCE }

private object CoveragePolicy {
    fun manualReviewReason(input: AnalysisInputSummary, cards: List<IngredientCard>): ManualReviewReason? {
        val lowCoverage = input.parsedIngredientCount >= 3 && input.matchedFragmentCount * 2 < input.parsedIngredientCount
        val hasReportableEvidence = cards.any { card ->
            card.presentationRole != "technical" && (
                card.id == "butylphenyl-methylpropional" || card.id in REPORTABLE_ACTIVE_IDS || card.functions.any {
                it in setOf(
                    "humectant", "emollient", "skin_protecting", "cleansing", "surfactant", "exfoliant",
                    "keratolytic", "uv_filter", "antioxidant", "perfuming",
                )
                }
                )
        }
        return when {
            lowCoverage -> ManualReviewReason.LOW_COVERAGE
            !hasReportableEvidence -> ManualReviewReason.NO_REPORTABLE_EVIDENCE
            else -> null
        }
    }

    fun isPartial(input: AnalysisInputSummary): Boolean =
        input.parsedIngredientCount > 0 && input.matchedFragmentCount * 5 < input.parsedIngredientCount * 4
}

private object ReportAssembler {
    const val DISCLAIMER =
        "Проверьте этикетку и реакцию кожи. Это информационный сервис, а не медицинская консультация: " +
            "он не диагностирует заболевания и не гарантирует безопасность или переносимость."

    private val productLabels = mapOf(
        "face_cleanser" to "средство для очищения лица",
        "face_toner" to "тоник или тонер для лица",
        "face_serum" to "сыворотка для лица",
        "face_moisturizer" to "увлажняющее средство для лица",
        "face_sunscreen" to "солнцезащитное средство для лица",
        "other" to "другое косметическое средство",
        "unknown" to "средство неустановленного типа",
    )

    private val activeIds = REPORTABLE_ACTIVE_IDS
    private val fragranceIds = setOf(
        "parfum", "limonene", "linalool", "citronellol", "benzyl-salicylate", "amyl-cinnamal",
        "geraniol", "butylphenyl-methylpropional",
    )
    private val cautionIds = setOf(
        "salicylic-acid", "glycolic-acid", "lactic-acid", "retinol",
    )
    private val formulaOnlyFunctions = setOf(
        "solvent", "preservative", "buffering", "chelating", "viscosity_controlling",
        "colorant", "opacifying", "antifoaming", "perfuming", "emulsifying",
    )

    fun partial(
        input: AnalysisInputSummary,
        profile: SkinProfile,
        cards: List<IngredientCard>,
        sources: List<KnowledgeSource>,
        reason: ManualReviewReason,
    ): CosmeticsReport {
        val allowedSources = sources.map { it.id }.toSet()
        val keyIngredients = buildKeyIngredients(cards.sortedByDescending(::keyPriority), allowedSources, 3)
        val highlights = buildHighlights(cards, allowedSources, partial = true).take(2)
        val cautions = buildCautions(cards, profile, allowedSources)
        val conflicts = allergyMatches(cards, profile)
        val skinFit = if (conflicts.isEmpty()) emptyList() else buildSkinFit(cards, profile, allowedSources, partial = true)
        val report = CosmeticsReport(
            status = "needs_clarification",
            productType = input.productTypeHint ?: "unknown",
            summary = when (reason) {
                ManualReviewReason.LOW_COVERAGE ->
                    "Сопоставлено только ${input.matchedFragmentCount} из ${input.parsedIngredientCount} OCR-фрагментов " +
                        "(${input.recognizedIngredientCount} карточек INCI). Сервис остановил полный вывод: исправьте OCR по этикетке и запустите анализ снова."
                ManualReviewReason.NO_REPORTABLE_EVIDENCE ->
                    "Состав сопоставлен, но в локальной базе найдены только базовые или технические компоненты. Их недостаточно, чтобы обоснованно описать назначение продукта."
            },
            highlights = highlights,
            skinFit = skinFit,
            routine = RoutineAdvice(
                emptyList(),
                if (reason == ManualReviewReason.LOW_COVERAGE) "сначала подтвердите состав" else "недостаточно данных",
                if (reason == ManualReviewReason.LOW_COVERAGE) {
                    "Схема применения не выводится при покрытии состава ниже 50%."
                } else {
                    "Схема применения не выводится без компонентов, которые подтверждают назначение средства."
                },
                "unknown",
                emptyList(),
            ),
            keyIngredients = keyIngredients,
            cautions = cautions,
            limitations = limitations(input, reason),
            confidence = "low",
            sourceIds = emptyList(),
            disclaimer = DISCLAIMER,
        )
        return report.copy(sourceIds = citedSourceIds(report))
    }

    fun assemble(
        decision: ModelAnalysisDecision,
        input: AnalysisInputSummary,
        profile: SkinProfile,
        cards: List<IngredientCard>,
        sources: List<KnowledgeSource>,
    ): CosmeticsReport {
        val allowedSources = sources.map { it.id }.toSet()
        val cardsById = cards.associateBy { it.id }
        val selected = decision.keyIngredientIds.mapNotNull(cardsById::get)
            .sortedByDescending(::keyPriority)
            .filter { keyPriority(it) > 0 }
        val keyIngredients = buildKeyIngredients(selected, allowedSources, 5)
        val highlights = buildHighlights(cards, allowedSources, partial = CoveragePolicy.isPartial(input))
        val cautions = buildCautions(cards, profile, allowedSources)
        val skinFit = buildSkinFit(cards, profile, allowedSources, partial = CoveragePolicy.isPartial(input))
        val conflicts = allergyMatches(cards, profile)
        val typeLead = if (input.productTypeHint != null) "Тип" else "Вероятный тип"
        val direction = formulaDirection(cards)
        val summary = when {
            conflicts.isNotEmpty() ->
                "В распознанной части найдено совпадение с указанной аллергией. Сервис не считает продукт совместимым с этим профилем."
            direction != null ->
                "$typeLead — ${productLabels.getValue(decision.productType)}. По распознанной части формула имеет $direction; это не доказательство эффекта готового продукта."
            else ->
                "$typeLead — ${productLabels.getValue(decision.productType)}. Сопоставлено ${input.matchedFragmentCount} из ${input.parsedIngredientCount} фрагментов."
        }
        val summarySourceIds = when {
            conflicts.isNotEmpty() -> skinFit.flatMap { it.sourceIds }.distinct()
            direction != null -> highlights.flatMap { it.sourceIds }.distinct()
            else -> emptyList()
        }
        val incompleteCoverage = CoveragePolicy.isPartial(input) || input.evidenceIngredientCount < input.recognizedIngredientCount
        val confidence = if (incompleteCoverage && decision.confidence == "high") "medium" else decision.confidence
        val report = CosmeticsReport(
            status = decision.status,
            productType = decision.productType,
            summary = summary,
            summarySourceIds = summarySourceIds,
            highlights = highlights,
            skinFit = skinFit,
            routine = routine(decision.productType),
            keyIngredients = keyIngredients,
            cautions = cautions,
            limitations = limitations(input, null),
            confidence = confidence,
            sourceIds = emptyList(),
            disclaimer = DISCLAIMER,
        )
        return report.copy(sourceIds = citedSourceIds(report))
    }

    fun citedSourceIds(report: CosmeticsReport): List<String> = (
        report.summarySourceIds +
            report.highlights.flatMap { it.sourceIds } +
            report.skinFit.flatMap { it.sourceIds } +
            report.cautions.flatMap { it.sourceIds } +
            report.routine.sourceIds +
            report.keyIngredients.flatMap { it.sourceIds }
        ).distinct()

    private fun buildHighlights(
        cards: List<IngredientCard>,
        allowedSources: Set<String>,
        partial: Boolean,
    ): List<GroundedClaim> {
        val prefix = if (partial) "Среди распознанных компонентов" else "В распознанной формуле"
        val claims = mutableListOf<GroundedClaim>()
        val reportableCards = cards.filterNot { it.presentationRole == "technical" }
        val humectants = reportableCards.filter { "humectant" in it.functions }
        val emollients = reportableCards.filter { "emollient" in it.functions || "skin_protecting" in it.functions }
        val cleansers = reportableCards.filter { "cleansing" in it.functions || "surfactant" in it.functions }
        val exfoliants = reportableCards.filter { "exfoliant" in it.functions || "keratolytic" in it.functions }
        val filters = reportableCards.filter { "uv_filter" in it.functions }
        val toneFocus = reportableCards.filter { it.id in setOf("niacinamide", "tranexamic-acid", "arbutin", "ascorbic-acid") }

        if (humectants.isNotEmpty()) claims += GroundedClaim(
            "$prefix есть влагоудерживающие компоненты (${names(humectants)}): это указывает на увлажняющую направленность, но не измеряет эффект продукта.",
            functionSources(humectants, allowedSources),
        )
        if (emollients.isNotEmpty()) claims += GroundedClaim(
            "$prefix есть смягчающие компоненты (${names(emollients)}), которые формируют эмолентную часть средства.",
            functionSources(emollients, allowedSources),
        )
        if (cleansers.isNotEmpty()) claims += GroundedClaim(
            "$prefix найдена очищающая система (${names(cleansers)}); её мягкость нельзя определить без концентраций и испытания готовой формулы.",
            functionSources(cleansers, allowedSources),
        )
        if (exfoliants.isNotEmpty()) claims += GroundedClaim(
            "$prefix есть кислоты с отшелушивающей функцией (${names(exfoliants)}); интенсивность зависит от концентрации и pH.",
            specificSources(exfoliants, allowedSources),
        )
        if (filters.isNotEmpty()) claims += GroundedClaim(
            "$prefix есть UV-фильтры (${names(filters)}), но SPF и broad-spectrum защиту подтверждают только испытания готового продукта.",
            specificSources(filters, allowedSources),
        )
        if (toneFocus.size >= 2) claims.add(0, GroundedClaim(
            "$prefix выделяется сочетание ${names(toneFocus)}. Оно показывает акцент состава, но INCI не подтверждает концентрации или результат для неровного тона.",
            (specificSources(toneFocus, allowedSources) + listOf("eu-cosmetic-claims-655").filter(allowedSources::contains)).distinct(),
        ))
        return claims.distinctBy { it.text }.take(3)
    }

    private fun buildSkinFit(
        cards: List<IngredientCard>,
        profile: SkinProfile,
        allowedSources: Set<String>,
        partial: Boolean,
    ): List<GroundedClaim> {
        val conflicts = allergyMatches(cards, profile)
        if (conflicts.isNotEmpty()) {
            return conflicts.map { (allergy, card) ->
                GroundedClaim(
                    "В профиле указано «$allergy», а в распознанной части найден ${card.inciName}. До уточнения сервис не считает продукт совместимым с профилем.",
                    profileMatchSources(card, allowedSources),
                )
            }
        }
        if (partial) return emptyList()

        val claims = mutableListOf<GroundedClaim>()
        val fragranceCards = cards.filter { it.id in fragranceIds }
        val effectiveSensitive = profile.sensitive || profile.skinType == "sensitive"
        if (effectiveSensitive && fragranceCards.isNotEmpty()) claims += GroundedClaim(
            "Для отмеченной чувствительной кожи совместимость нельзя подтвердить: в составе распознаны компоненты отдушки.",
            fragranceCards.flatMap { fragranceSources(it, allowedSources) }.distinct(),
        )
        val moistureCards = cards.filter { it.presentationRole != "technical" && ("humectant" in it.functions || "emollient" in it.functions) }
        if (moistureCards.size >= 2) claims += GroundedClaim(
            "В распознанной части есть несколько влагоудерживающих или смягчающих компонентов. Это сигнал для запроса на уменьшение сухости, но не подтверждение совместимости готового продукта с конкретным типом кожи.",
            functionSources(moistureCards, allowedSources),
        )
        val normalizedGoals = profile.goals.joinToString(" ").lowercase()
        if (moistureCards.isNotEmpty() && listOf("увлаж", "сухост", "hydration", "moistur").any(normalizedGoals::contains)) {
            claims += GroundedClaim(
                "Цель профиля по увлажнению совпадает с влагоудерживающим или смягчающим сигналом распознанной части; сила эффекта и переносимость остаются неизвестны.",
                functionSources(moistureCards, allowedSources),
            )
        }
        val cleanserCards = cards.filter { it.presentationRole != "technical" && ("cleansing" in it.functions || "surfactant" in it.functions) }
        if (cleanserCards.isNotEmpty() && listOf("очищ", "clean").any(normalizedGoals::contains)) {
            claims += GroundedClaim(
                "Цель профиля по очищению совпадает с распознанной очищающей системой; её мягкость определяется всей смываемой формулой.",
                functionSources(cleanserCards, allowedSources),
            )
        }
        if (claims.isEmpty()) claims += GroundedClaim(
            "По одному INCI нельзя обоснованно назначить продукт конкретному типу кожи; ориентируйтесь на задачу средства и реакцию готовой формулы.",
            listOf("eu-cosmetic-claims-655").filter(allowedSources::contains),
        )
        return claims.take(3)
    }

    private fun buildCautions(
        cards: List<IngredientCard>,
        profile: SkinProfile,
        allowedSources: Set<String>,
    ): List<GroundedClaim> {
        val claims = mutableListOf<GroundedClaim>()
        cards.firstOrNull { it.id == "butylphenyl-methylpropional" }?.let {
            claims += GroundedClaim(
                "BUTYLPHENYL METHYLPROPIONAL (Lilial) включён в Annex II ЕС; запрет применяется с 1 марта 2022 года. Этот вывод показывается только для подтверждённого INCI, а не для похожего OCR-фрагмента.",
                listOf("eu-bmhca-ban-2021").filter(allowedSources::contains),
            )
        }
        allergyMatches(cards, profile).forEach { (allergy, card) ->
            claims += GroundedClaim(
                "Прямое совпадение с профилем: «$allergy» ↔ ${card.inciName}. Сервис не оценивает средство как совместимое, пока профиль и этикетка не уточнены.",
                profileMatchSources(card, allowedSources),
            )
        }
        val fragranceCards = cards.filter { it.id in fragranceIds }
        if (fragranceCards.isNotEmpty() && allergyMatches(cards, profile).isEmpty()) {
            claims += GroundedClaim(
                "Распознаны компоненты отдушки (${names(fragranceCards)}). У отдельных людей отдушки могут вызывать раздражение или аллергическую реакцию.",
                fragranceCards.flatMap { fragranceSources(it, allowedSources) }.distinct(),
            )
        }
        cards.filter { it.id in cautionIds }.take(2).forEach { card ->
            card.cautions.firstOrNull()?.let { text ->
                claims += GroundedClaim(text, specificSources(listOf(card), allowedSources))
            }
        }
        return claims.distinctBy { it.text }.take(5)
    }

    private fun buildKeyIngredients(
        cards: List<IngredientCard>,
        allowedSources: Set<String>,
        limit: Int,
    ): List<IngredientInsight> = cards.distinctBy { it.id }
        .filter { keyPriority(it) > 0 }
        .take(limit)
        .map { card ->
            IngredientInsight(card.id, whyItMatters(card), ingredientInsightSources(card, allowedSources))
        }

    private fun whyItMatters(card: IngredientCard): String = when {
        card.id == "niacinamide" ->
            "Уходовый компонент; данные для конкретных формул нельзя переносить на продукт с неизвестной концентрацией."
        card.id == "tranexamic-acid" ->
            "Компонент, который исследовали в средствах для неровного тона; результаты зависят от готовой формулы и концентрации."
        card.id == "arbutin" ->
            "Строка ARBUTIN не уточняет форму и концентрацию, поэтому по ней нельзя обещать осветляющий эффект."
        "uv_filter" in card.functions ->
            "UV-фильтр, но наличие в INCI само по себе не подтверждает SPF готового средства."
        "exfoliant" in card.functions || "keratolytic" in card.functions ->
            "Отшелушивающий компонент; интенсивность и переносимость зависят от концентрации и pH."
        "cleansing" in card.functions || "surfactant" in card.functions ->
            "Компонент очищающей системы; мягкость оценивают по всей смываемой формуле."
        "humectant" in card.functions ->
            "Влагоудерживающий компонент, поддерживающий увлажняющую направленность формулы."
        "emollient" in card.functions || "skin_protecting" in card.functions ->
            "Смягчающий компонент эмолентной части формулы."
        "antioxidant" in card.functions ->
            "Антиоксидантный или кондиционирующий компонент; вклад зависит от концентрации и формулы."
        else -> "Уходовый компонент из распознанной части состава; роль зависит от готовой формулы."
    }

    private fun keyPriority(card: IngredientCard): Int = when {
        card.presentationRole == "technical" -> 0
        card.id in activeIds -> 120
        card.functions.any { it in setOf("uv_filter", "exfoliant", "keratolytic", "cleansing") } -> 100
        card.functions.any { it in setOf("humectant", "emollient", "skin_protecting") } -> 70
        card.functions.contains("antioxidant") -> 55
        card.id == "aqua" || card.id in fragranceIds || card.functions.contains("preservative") -> 0
        card.functions.all(formulaOnlyFunctions::contains) -> 0
        else -> 25
    }

    private fun allergyMatches(cards: List<IngredientCard>, profile: SkinProfile): List<Pair<String, IngredientCard>> {
        val cardsByAlias = buildMap {
            cards.forEach { card ->
                (card.aliases + card.inciName + card.id).forEach { put(normalizeLookup(it), card) }
            }
        }
        return profile.allergies.mapNotNull { allergy ->
            val normalized = normalizeLookup(allergy)
            val alias = when (normalized) {
                "FRAGRANCE", "FRAGRANCE MIX", "ОТДУШКА", "АРОМАТИЗАТОР" -> "PARFUM"
                else -> normalized
            }
            cardsByAlias[alias]?.let { allergy to it }
        }.distinctBy { it.second.id }
    }

    private fun formulaDirection(cards: List<IngredientCard>): String? {
        val reportableCards = cards.filterNot { it.presentationRole == "technical" }
        val directions = buildList {
            if (reportableCards.count { "humectant" in it.functions } >= 1) add("увлажняющую направленность")
            if (reportableCards.any { "emollient" in it.functions || "skin_protecting" in it.functions }) add("смягчающую основу")
            if (reportableCards.any { "cleansing" in it.functions || "surfactant" in it.functions }) add("очищающую систему")
            if (reportableCards.count { it.id in setOf("niacinamide", "tranexamic-acid", "arbutin", "ascorbic-acid") } >= 2) {
                add("акцент состава на уход за неровным тоном")
            }
        }
        return directions.take(2).joinToString(" и ").ifBlank { null }
    }

    private fun names(cards: List<IngredientCard>): String = cards.map { it.inciName }.distinct().take(4).joinToString()

    private fun functionSources(cards: List<IngredientCard>, allowed: Set<String>): List<String> =
        (
            listOf("eu-cosing").filter { it in allowed && cards.any { card -> it in card.sourceIds } } +
                listOf("eu-cosmetic-claims-655").filter(allowed::contains)
            ).distinct()

    private fun ingredientInsightSources(card: IngredientCard, allowed: Set<String>): List<String> = when {
        card.id in activeIds || card.id in cautionIds || card.functions.contains("uv_filter") ->
            specificSources(listOf(card), allowed)
        else -> identityFunctionSources(card, allowed)
    }

    private fun specificSources(cards: List<IngredientCard>, allowed: Set<String>): List<String> {
        val ids = cards.flatMap { it.sourceIds }.filter { it in allowed }.distinct()
        val specific = ids.filterNot { it in setOf("aad-patch-test", "eu-cosmetics-framework") }
        return (specific.take(3) + ids.filter { it == "eu-cosing" }.take(1)).distinct().take(3)
    }

    private fun fragranceSources(card: IngredientCard, allowed: Set<String>): List<String> =
        card.sourceIds.filter { it in allowed && it in setOf("fda-allergens", "fda-fragrances", "eu-bmhca-ban-2021") }.distinct()

    private fun profileMatchSources(card: IngredientCard, allowed: Set<String>): List<String> =
        if (card.id in fragranceIds) fragranceSources(card, allowed) else identityFunctionSources(card, allowed)

    private fun identityFunctionSources(card: IngredientCard, allowed: Set<String>): List<String> =
        listOf("eu-cosing").filter { it in allowed && it in card.sourceIds }

    private fun limitations(
        input: AnalysisInputSummary,
        manualReviewReason: ManualReviewReason?,
    ): List<String> = buildList {
        when (manualReviewReason) {
            ManualReviewReason.LOW_COVERAGE -> add("Покрытие ниже 50%: выводы о продукте целиком, типе кожи и применении намеренно отключены.")
            ManualReviewReason.NO_REPORTABLE_EVIDENCE -> add("Покрытие состава достаточное, но распознаны только базовые или технические функции; продуктовые выводы намеренно отключены.")
            null -> Unit
        }
        add("INCI не раскрывает точные концентрации, pH и взаимодействие компонентов в готовой формуле.")
        add("Свойство отдельного ингредиента нельзя автоматически переносить на готовый продукт без подтверждающих данных.")
        add(
            when (input.productTypeSource) {
                "local_catalog" -> "Тип продукта взят из проверенной записи локального demo-каталога; способ применения всё равно сверяйте с этикеткой."
                "user_hint" -> "Тип продукта взят из вашей подсказки, а не выведен из INCI."
                else -> "Вероятный тип продукта выбран локальной моделью; способ применения сверяйте с этикеткой."
            },
        )
        if (input.unknownIngredients.isNotEmpty()) {
            add("Локальная база не распознала ${input.unknownIngredients.size} позиций; для них свойства не выводятся.")
        }
        if (input.evidenceIngredientCount < input.recognizedIngredientCount) {
            add("В контекст модели отобрано ${input.evidenceIngredientCount} приоритетных карточек из ${input.recognizedIngredientCount}; детерминированные проверки используют все распознанные карточки.")
        }
    }

    private fun routine(productType: String): RoutineAdvice = when (productType) {
        "face_cleanser" -> RoutineAdvice(listOf("утро или вечер"), "очищение", "Типичная схема для выбранного типа: нанесите и смойте; точный способ сверяйте с этикеткой.", "yes", emptyList())
        "face_toner" -> RoutineAdvice(listOf("утро или вечер — по этикетке"), "после очищения", "Типичная схема для тонера: нанесите после очищения способом и с частотой, указанными производителем.", "unknown", emptyList())
        "face_serum" -> RoutineAdvice(listOf("по этикетке"), "после очищения, до крема", "Типичная схема для сыворотки; частоту и количество сверяйте с этикеткой.", "no", emptyList())
        "face_moisturizer" -> RoutineAdvice(listOf("утро", "вечер"), "после сыворотки или самостоятельно", "Типичная схема для выбранного типа средства; используйте по инструкции производителя.", "no", emptyList())
        "face_sunscreen" -> RoutineAdvice(listOf("днём"), "финальный дневной шаг", "Ориентируйтесь на заявленные SPF, количество и обновление на этикетке; один INCI SPF не доказывает.", "no", emptyList())
        else -> RoutineAdvice(listOf("по этикетке"), "не определён", "Тип и способ применения нужно подтвердить по упаковке.", "unknown", emptyList())
    }
}
