package ru.ai.course.day31.developerassistant

import kotlinx.serialization.decodeFromString

class DeveloperAssistant(
    private val config: AppConfig,
    private val mcpClient: ProjectContextGateway,
    private val embeddings: EmbeddingClient = EmbeddingFactory.create(config),
    private val promptBuilder: PromptBuilder = PromptBuilder(config),
    private val validator: GroundingValidator = GroundingValidator(),
    private val fixtureResponder: FixtureResponder = FixtureResponder(),
    private val generator: AssistantAnswerGenerator = OllamaClient(config),
) {
    private val indexManager = RagIndexManager(config, embeddings = embeddings)
    private val retriever = Retriever(config, embeddings)

    fun ensureIndex(): RagIndex = indexManager.ensureIndex()

    suspend fun askFixture(question: String): AssistantRun =
        run(question, fixture = true)

    suspend fun askLive(question: String): AssistantRun =
        run(question, fixture = false)

    private suspend fun run(question: String, fixture: Boolean): AssistantRun {
        val normalizedQuestion = question.trim()
        require(normalizedQuestion.isNotEmpty()) { "Question must not be blank." }
        require(TextTools.utf8Bytes(normalizedQuestion) <= config.maxQuestionBytes) {
            "Question must not exceed ${config.maxQuestionBytes} UTF-8 bytes for the configured prompt envelope."
        }
        if (mustRefuseSensitiveTopic(normalizedQuestion)) {
            return preflightRefusal(normalizedQuestion, fixture)
        }

        val requirements = GroundingRequirementsFactory.forQuestion(normalizedQuestion)
        val retrieval = if (requirements.documentationRequired) {
            retriever.retrieve(ensureIndex(), normalizedQuestion)
        } else {
            RetrievalResult(normalizedQuestion, hits = emptyList(), lowConfidence = false)
        }
        val rawMcp = mcpClient.fetchContext(
            includeFiles = requirements.fetchFiles,
            fileLimit = config.maxFileList,
        )
        val prepared = promptBuilder.prepare(normalizedQuestion, retrieval, rawMcp, requirements)

        val proposed = when {
            requirements.documentationRequired && (retrieval.lowConfidence || prepared.evidence.items.isEmpty()) ->
                unknownDocumentationAnswer()
            requirements.filesRequired && prepared.mcp.files?.files.isNullOrEmpty() ->
                unknownDocumentationAnswer()
            !requirements.documentationRequired -> AnswerAssembler.mcpOnlyDocumentationAnswer()
            fixture -> fixtureResponder.answer(normalizedQuestion, prepared.evidence, requirements)
            else -> liveDocumentationAnswer(prepared)
        }
        val normalized = if (proposed.status == "unknown") unknownDocumentationAnswer() else proposed
        val proposedValidation = validator.validateGenerated(
            normalized,
            prepared.evidence,
            requirements.documentationRequired,
        )
        val accepted = if (proposedValidation.valid) normalized else unknownDocumentationAnswer()
        var answer = AnswerAssembler.assemble(accepted, prepared.evidence, requirements, prepared.mcp)
        var validation = combinedValidation(
            validator.validateGenerated(accepted, prepared.evidence, requirements.documentationRequired),
            validator.validateFinal(answer, prepared.mcp, requirements),
        )
        if (!validation.valid) {
            val safe = unknownDocumentationAnswer()
            answer = AnswerAssembler.assemble(safe, prepared.evidence, requirements, prepared.mcp)
            validation = combinedValidation(
                validator.validateGenerated(safe, prepared.evidence, requirements.documentationRequired),
                validator.validateFinal(answer, prepared.mcp, requirements),
            )
        }

        return AssistantRun(
            question = normalizedQuestion,
            retrieval = retrieval,
            evidence = prepared.evidence,
            mcp = prepared.mcp,
            requirements = requirements,
            answer = answer,
            validation = validation,
            prompt = prepared.prompt,
            fixture = fixture,
        )
    }

    private fun liveDocumentationAnswer(prepared: PreparedPrompt): GeneratedDocumentationAnswer {
        val reply = generator.answer(
            prepared.prompt,
            promptBuilder.answerSchema(allowedSourceCount = prepared.evidence.items.size),
        )
        return try {
            AppJson.strict.decodeFromString(reply.content)
        } catch (_: Exception) {
            unknownDocumentationAnswer()
        }
    }

    private fun unknownDocumentationAnswer(): GeneratedDocumentationAnswer = GeneratedDocumentationAnswer(
        status = "unknown",
        answer = CANONICAL_UNKNOWN_MESSAGE,
        sourceIds = emptyList(),
    )

    private fun preflightRefusal(question: String, fixture: Boolean): AssistantRun {
        val requirements = GroundingRequirements(
            documentationRequired = false,
            branchRequired = false,
            filesRequired = false,
            fetchFiles = false,
        )
        val retrieval = RetrievalResult(question, hits = emptyList(), lowConfidence = true)
        val evidence = EvidencePackBuilder().build(retrieval, maxTokens = 0)
        val mcp = McpEvidence(
            availableTools = emptyList(),
            usedTools = emptyList(),
            branch = GitBranchInfo("(not requested)", detached = false),
            files = null,
        )
        val prompt = PromptPack(
            system = "",
            user = "",
            preview = "(not built: sensitive topic refused before retrieval, MCP, embeddings, or model processing)",
            approxTokens = 0,
            maxTokens = config.maxPromptTokens,
            utf8Bytes = 0,
            maxBytes = config.maxPromptBytes,
        )
        val generated = unknownDocumentationAnswer()
        val answer = AnswerAssembler.assemble(generated, evidence, requirements, mcp)
        return AssistantRun(
            question = question,
            retrieval = retrieval,
            evidence = evidence,
            mcp = mcp,
            requirements = requirements,
            answer = answer,
            validation = combinedValidation(
                validator.validateGenerated(generated, evidence, documentationRequired = false),
                validator.validateFinal(answer, mcp, requirements),
            ),
            prompt = prompt,
            fixture = fixture,
            preflightRefusalReason = "sensitive topic",
        )
    }

    companion object {
        const val CANONICAL_UNKNOWN_MESSAGE: String =
            "Не нашёл достаточного ответа в разрешённой документации проекта. " +
                "Уточните вопрос о структуре, командах, RAG или MCP."

        fun asksAboutBranch(question: String): Boolean {
            val normalized = question.lowercase()
            return listOf("ветк", "branch", "git branch", "текущий git").any(normalized::contains)
        }

        fun mustRefuseSensitiveTopic(question: String): Boolean {
            val normalized = question.lowercase()
            val sensitivePhrase = listOf(
                ".env",
                ".certs",
                "certs",
                "certificate",
                "сертификат",
                "tls cert",
                "парол",
                "password",
                "passwd",
                "passphrase",
                "oauth",
                "bearer",
                "authorization header",
                "токен",
                "token",
                "api key",
                "access key",
                "secret key",
                "client secret",
                "секрет",
                "secret",
                "private key",
                "приватный ключ",
                "credential",
                "учётные данные",
                "authentication material",
                "access code",
                "код доступа",
                "keystore",
                "truststore",
                "ssh key",
                "pem file",
                "login data",
            ).any(normalized::contains)
            val sensitiveToken = TextTools.tokens(question).any {
                it in setOf(
                    "key",
                    "keys",
                    "ключ",
                    "ключи",
                    "credential",
                    "credentials",
                    "cookie",
                    "cookies",
                    "session",
                    "sessions",
                    "auth",
                    "cert",
                    "certs",
                )
            }
            return sensitivePhrase || sensitiveToken
        }

    }

    private fun combinedValidation(
        generated: GroundingValidation,
        final: GroundingValidation,
    ): GroundingValidation = GroundingValidation(
        valid = generated.valid && final.valid,
        unknownSourceIds = (generated.unknownSourceIds + final.unknownSourceIds).distinct().sorted(),
        errors = generated.errors + final.errors,
    )
}
