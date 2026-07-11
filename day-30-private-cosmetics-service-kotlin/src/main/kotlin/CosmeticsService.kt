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
            evidenceIngredientCount = pack.evidenceIngredients.size,
            unknownIngredients = pack.unknown,
            ingredientCorrections = pack.corrections,
        )
        if (pack.recognized.isEmpty()) return needsMoreData(input)

        val cards = pack.evidenceIngredients.map { it.card }
        val (systemPrompt, userPrompt) = PromptBuilder.analysisPrompt(input, profile, cards)
        PromptBuilder.ensureBudget(systemPrompt, userPrompt, config)
        val reply = callModel(systemPrompt, userPrompt, PromptBuilder.reportSchema(input, cards))
        val decision = groundAnalysisDecision(parseAnalysisDecision(reply.content), input, cards)
        validateAnalysisDecision(decision, input, cards)
        val report = ReportAssembler.assemble(decision, input, profile, cards, pack.sources)
        validateReport(report, input, cards, pack.sources)
        val citedIds = report.sourceIds + report.routine.sourceIds + report.keyIngredients.flatMap { it.sourceIds }
        val session = sessions.create(input, profile, report, cards, pack.sources)
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
            suitableSkinTypes = emptyList(),
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
            input.parsedIngredientCount <= 0 || input.recognizedIngredientCount * 2 < input.parsedIngredientCount -> "low"
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
        val priorityIds = cards.asSequence()
            .map { it.id }
            .filter(KEY_INGREDIENT_PRIORITIES::contains)
            .take(3)
            .toList()
        val fallbackIds = cards.asSequence().filterNot { it.id == "aqua" }.map { it.id }.toList()
            .ifEmpty { cards.take(1).map { it.id } }
        val groundedIds = (priorityIds + decision.keyIngredientIds + fallbackIds).distinct().take(6)
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
        validateSources(report.routine.sourceIds, "routine")
        report.keyIngredients.forEach { insight ->
            val card = allowedCards[insight.ingredientId] ?: invalidModel("Key ingredient is absent from retrieved facts.")
            if (insight.sourceIds.isEmpty() || insight.sourceIds.any { it !in card.sourceIds || it !in allowedSources }) {
                invalidModel("Key ingredient contains an ungrounded source ID.")
            }
        }
        if (report.status == "answered" && report.sourceIds.isEmpty()) invalidModel("Answered report has no sources.")
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
        val answer = when (decision.topic) {
            "overview" -> listOf(
                session.report.summary,
                session.report.keyIngredients.joinToString(" ") { "${it.ingredientId}: ${it.whyItMatters}" },
                "Использование: ${session.report.routine.step}; ${session.report.routine.directions}",
            ).filter(String::isNotBlank).joinToString(" ")
            "routine" -> listOf(
                "Время: ${session.report.routine.timeOfDay.joinToString().ifBlank { "уточните по этикетке" }}.",
                "Шаг: ${session.report.routine.step}.",
                session.report.routine.directions,
                "Смывание: ${session.report.routine.rinseOff}.",
            ).joinToString(" ")
            "skin_fit" -> session.report.suitableSkinTypes.joinToString(" ").ifBlank {
                "По локальным фактам нельзя обоснованно выделить тип кожи; ориентируйтесь на этикетку и переносимость."
            }
            "key_ingredients" -> session.report.keyIngredients.joinToString(" ") {
                "${it.ingredientId}: ${it.whyItMatters}"
            }.ifBlank { "В текущем отчёте нет выбранных ключевых ингредиентов." }
            "cautions" -> session.report.cautions.joinToString(" ").ifBlank {
                "Специальных оговорок в локальных карточках нет; индивидуальная реакция всё равно возможна."
            }
            "limitations" -> session.report.limitations.joinToString(" ")
            else -> "В локальных фактах этого анализа нет данных для ответа. Уточните вопрос о рутине, типе кожи, ингредиентах или ограничениях."
        }
        val sourceIds = if (decision.status != "answered") emptyList() else when (decision.topic) {
            "overview", "skin_fit", "cautions" -> session.report.sourceIds.take(8)
            "key_ingredients" -> session.report.keyIngredients.flatMap { it.sourceIds }.distinct().take(8)
            "routine" -> session.report.routine.sourceIds.take(8)
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
        if (decision.status == "answered" && decision.topic != "unknown") return decision
        val text = message.lowercase()
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
        val KEY_INGREDIENT_PRIORITIES = setOf(
            "niacinamide",
            "tranexamic-acid",
            "arbutin",
            "retinol",
            "ascorbic-acid",
            "salicylic-acid",
            "glycolic-acid",
            "lactic-acid",
            "zinc-oxide",
            "titanium-dioxide",
        )
    }
}

fun InciParser.runCatchingInstruction(text: String): Boolean = runCatching { parse(text); false }
    .getOrElse { error -> error is ApiProblem && error.code == "prompt_injection_detected" }

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

    private val functionLabels = mapOf(
        "solvent" to "растворитель",
        "humectant" to "удержание влаги",
        "skin_conditioning" to "кондиционирование кожи",
        "emollient" to "смягчение",
        "skin_protecting" to "защита кожи",
        "antifoaming" to "снижение пенообразования",
        "exfoliant" to "отшелушивание",
        "keratolytic" to "кератолитическая функция",
        "preservative" to "консервирование формулы",
        "buffering" to "регулирование pH формулы",
        "antioxidant" to "антиоксидантная функция",
        "uv_filter" to "UV-фильтр",
        "colorant" to "окрашивание",
        "opacifying" to "придание непрозрачности",
        "perfuming" to "ароматизация",
        "astringent" to "вяжущая функция",
        "antimicrobial" to "антимикробная функция в формуле",
        "cleansing" to "очищение",
        "surfactant" to "поверхностно-активная функция",
        "foam_boosting" to "усиление пены",
        "foaming" to "пенообразование",
        "chelating" to "связывание ионов в формуле",
        "viscosity_controlling" to "регулирование вязкости формулы",
        "film_forming" to "образование плёнки",
    )

    private val skinLabels = mapOf(
        "dry" to "Может быть релевантно сухой коже по функциям распознанных компонентов; итог зависит от всей формулы.",
        "dehydrated" to "Может быть релевантно обезвоженной коже по локальным карточкам увлажняющих компонентов.",
        "oily" to "Может быть релевантно жирной коже, но переносимость определяется готовым продуктом.",
        "combination" to "Может быть релевантно комбинированной коже, с учётом зоны и переносимости.",
        "normal" to "Может быть релевантно нормальной коже при соблюдении инструкции продукта.",
        "sensitive" to "Для чувствительной кожи нужна особенно осторожная проба готового продукта.",
        "all_skin_types" to "Карточки не ограничивают средство одним типом кожи, но это не гарантирует индивидуальную переносимость.",
    )

    private val profileLabels = mapOf(
        "dry" to "сухая",
        "oily" to "жирная",
        "combination" to "комбинированная",
        "normal" to "нормальная",
        "sensitive" to "чувствительная",
    )

    fun assemble(
        decision: ModelAnalysisDecision,
        input: AnalysisInputSummary,
        profile: SkinProfile,
        cards: List<IngredientCard>,
        sources: List<KnowledgeSource>,
    ): CosmeticsReport {
        val allowedSources = sources.map { it.id }.toSet()
        val selected = decision.keyIngredientIds.mapNotNull(cards.associateBy { it.id }::get)
        val sourceIds = cards.flatMap { it.sourceIds }.filter { it in allowedSources }.distinct()
        val keyIngredients = selected.map { card ->
            val functions = card.functions.map { functionLabels[it] ?: it }.joinToString()
            IngredientInsight(
                ingredientId = card.id,
                whyItMatters = "Функции в локальной карточке: $functions. Роль зависит от концентрации и всей формулы.",
                sourceIds = card.sourceIds.filter { it in allowedSources },
            )
        }
        val suitable = cards.flatMap { it.suitableFor }.distinct().mapNotNull(skinLabels::get).take(6).toMutableList()
        profile.skinType?.takeIf { it != "unknown" }?.let { type ->
            suitable += "Профиль пользователя: ${profileLabels[type] ?: type} кожа; это контекст, а не подтверждение совместимости."
        }
        val cautions = cards.flatMap { it.cautions }.distinct().take(8).toMutableList()
        if (profile.sensitive) cautions += "При чувствительной коже вводите готовый продукт постепенно и прекратите использование при реакции."
        if (profile.allergies.isNotEmpty()) cautions += "Сверьте заявленные аллергии со всей этикеткой: локальный список карточек неполон."
        val limitations = buildList {
            add("INCI не раскрывает точные концентрации, pH и взаимодействие компонентов в готовой формуле.")
            add(
                when (input.productTypeSource) {
                    "local_catalog" -> "Тип продукта взят из проверенной записи локального demo-каталога. Способ применения всё равно сверяйте с этикеткой."
                    "user_hint" -> "Тип продукта определён по вашей подсказке; если подсказка неточна, исправьте её и повторите анализ."
                    else -> "Вероятный тип продукта выбран локальной моделью; способ применения всегда сверяйте с этикеткой производителя."
                },
            )
            if (input.unknownIngredients.isNotEmpty()) {
                add("Локальная база не распознала ${input.unknownIngredients.size} позиций; для них свойства не выводятся.")
            }
            if (input.recognizedIngredientCount > cards.size) {
                add("Для компактного контекста модели отобрано ${cards.size} приоритетных карточек из ${input.recognizedIngredientCount} распознанных; общий счётчик покрытия сохранён полностью.")
            }
        }
        val incompleteCoverage = input.unknownIngredients.isNotEmpty() ||
            input.recognizedIngredientCount < input.parsedIngredientCount ||
            input.evidenceIngredientCount < input.recognizedIngredientCount
        val confidence = if (incompleteCoverage && decision.confidence == "high") "medium" else decision.confidence
        return CosmeticsReport(
            status = decision.status,
            productType = decision.productType,
            summary = if (decision.status == "answered") {
                val typeLead = if (input.productTypeHint != null) "Тип" else "Вероятный тип"
                "$typeLead — ${productLabels.getValue(decision.productType)}. " +
                    "Распознано ${input.recognizedIngredientCount} из ${input.parsedIngredientCount} позиций; текст собран сервером только из локальных карточек."
            } else {
                "Локальная модель не смогла обоснованно определить карточку по переданным фактам."
            },
            suitableSkinTypes = suitable.distinct(),
            routine = if (decision.status == "answered") routine(decision.productType) else {
                RoutineAdvice(emptyList(), "не определён", "Сверьте тип и способ применения с этикеткой.", "unknown", emptyList())
            },
            keyIngredients = keyIngredients,
            cautions = cautions.distinct(),
            limitations = limitations,
            confidence = confidence,
            sourceIds = sourceIds,
            disclaimer = DISCLAIMER,
        )
    }

    private fun routine(productType: String): RoutineAdvice = when (productType) {
        "face_cleanser" -> RoutineAdvice(listOf("утро", "вечер"), "очищение", "Используйте и смывайте строго по этикетке.", "yes", emptyList())
        "face_toner" -> RoutineAdvice(
            listOf("утро или вечер — по этикетке"),
            "после очищения",
            "Наносите способом и с частотой, указанными производителем; избегайте области вокруг глаз.",
            "unknown",
            emptyList(),
        )
        "face_serum" -> RoutineAdvice(listOf("по этикетке"), "после очищения, до крема", "Наносите с частотой и количеством, указанными производителем.", "no", emptyList())
        "face_moisturizer" -> RoutineAdvice(listOf("утро", "вечер"), "после сыворотки или самостоятельно", "Используйте по инструкции производителя.", "no", emptyList())
        "face_sunscreen" -> RoutineAdvice(listOf("днём"), "финальный дневной шаг", "Ориентируйтесь на заявленные SPF, количество и обновление на этикетке; один INCI SPF не доказывает.", "no", emptyList())
        else -> RoutineAdvice(listOf("по этикетке"), "не определён", "Тип и способ применения нужно подтвердить по упаковке.", "unknown", emptyList())
    }
}
