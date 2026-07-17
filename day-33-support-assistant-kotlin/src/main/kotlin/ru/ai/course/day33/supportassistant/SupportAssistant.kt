package ru.ai.course.day33.supportassistant

import java.util.ArrayDeque

data class SupportPreparation(
    val fetch: McpContextFetch,
    val context: SupportContext?,
    val evidence: KnowledgeEvidencePack?,
    val prompt: PromptPack?,
)

class SupportAssistant(
    private val config: AppConfig,
    private val contextGateway: SupportContextGateway,
    private val embeddings: EmbeddingClient = HashEmbeddingClient(config.limits.embeddingDimensions),
    private val validator: GroundingValidator = GroundingValidator(),
) {
    private val index = RagIndex(
        loader = KnowledgeDocumentLoader(config.knowledgeDirectory),
        chunker = StructuredChunker(config.limits.maxChunkChars),
        embeddings = embeddings,
        dimensions = config.limits.embeddingDimensions,
        repositoryRoot = config.repositoryRoot,
        indexPath = config.ragIndexPath,
    )
    private val retriever = HybridRetriever(
        embeddings = embeddings,
        topK = config.limits.topK,
        minRelevance = config.limits.minRelevance,
    )
    private val evidenceBuilder = EvidencePackBuilder(config.limits.maxEvidenceChars)
    private val promptBuilder = PromptBuilder(config.limits.maxPromptChars)

    suspend fun prepare(
        ticketId: String,
        question: String,
        recentHistory: List<ChatTurn> = emptyList(),
    ): SupportPreparation {
        val safeTicketId = SupportDataPolicy.requireTicketId(ticketId)
        val safeQuestion = TextTools.bounded(question, config.limits.maxQuestionChars, "question")
        val fetch = contextGateway.fetch(safeTicketId)
        val context = fetch.context ?: return SupportPreparation(fetch, null, null, null)
        SupportDataPolicy.requireCurrentReferences(
            safeQuestion,
            context.ticket.id,
            context.user.id,
            "question",
        )
        require(recentHistory.size <= config.limits.chatHistoryTurns) {
            "Recent history exceeds ${config.limits.chatHistoryTurns} turns."
        }
        recentHistory.forEach { turn ->
            require(SupportDataPolicy.requireTicketId(turn.ticketId) == safeTicketId) {
                "Recent history belongs to a different ticket."
            }
            TextTools.bounded(turn.question, config.limits.maxQuestionChars, "history question")
            TextTools.bounded(turn.answer, 1_200, "history answer")
            SupportDataPolicy.requireCurrentReferences(
                turn.question + "\n" + turn.answer,
                context.ticket.id,
                context.user.id,
                "recent history",
            )
        }
        val query = buildRetrievalQuery(safeQuestion, context)
        val hits = retriever.retrieve(safeQuestion, query, index.ensure())
        val (evidence, prompt) = fitPrompt(query, hits, safeQuestion, context, recentHistory)
        return SupportPreparation(fetch, context, evidence, prompt)
    }

    suspend fun askFixture(
        ticketId: String,
        question: String,
        recentHistory: List<ChatTurn> = emptyList(),
    ): SupportAssistantResult = run(
        ticketId,
        question,
        recentHistory,
        generatorFactory = { SupportResponseGenerator(FixtureResponder()::generate) },
        countAsLlm = false,
    )

    suspend fun askLive(
        ticketId: String,
        question: String,
        recentHistory: List<ChatTurn> = emptyList(),
        generator: SupportResponseGenerator? = null,
    ): SupportAssistantResult = run(
        ticketId,
        question,
        recentHistory,
        generatorFactory = { generator ?: ElizaLlmClient(config) },
        countAsLlm = true,
    )

    private suspend fun run(
        ticketId: String,
        question: String,
        recentHistory: List<ChatTurn>,
        generatorFactory: () -> SupportResponseGenerator,
        countAsLlm: Boolean,
    ): SupportAssistantResult {
        val safeQuestion = TextTools.bounded(question, config.limits.maxQuestionChars, "question")
        val safeTicketId = SupportDataPolicy.requireTicketId(ticketId)
        val preparation = prepare(safeTicketId, safeQuestion, recentHistory)
        val context = preparation.context
        if (context == null) {
            return SupportAssistantResult(
                outcome = AssistantOutcome.NOT_FOUND,
                question = safeQuestion,
                ticketId = safeTicketId,
                context = null,
                evidence = null,
                response = GroundingValidator.canonicalNotFound(safeTicketId),
                mcpToolsUsed = preparation.fetch.usedTools,
                grounded = true,
                currentTicketIsolationValid = true,
                llmCalls = 0,
                failureReason = preparation.fetch.failureReason,
            )
        }
        val evidence = requireNotNull(preparation.evidence)
        val prompt = preparation.prompt
        if (prompt == null) {
            return SupportAssistantResult(
                outcome = AssistantOutcome.UNKNOWN,
                question = safeQuestion,
                ticketId = safeTicketId,
                context = context,
                evidence = evidence,
                response = GroundingValidator.canonicalUnknown(),
                mcpToolsUsed = preparation.fetch.usedTools,
                grounded = true,
                currentTicketIsolationValid = true,
                llmCalls = 0,
                failureReason = "Retrieval relevance gate rejected all knowledge chunks.",
            )
        }

        var llmCalls = 0
        val reply = runCatching {
            val generator = generatorFactory()
            if (countAsLlm) llmCalls = 1
            generator.generate(prompt)
        }
            .getOrElse { error ->
                if (error is LlmPreflightException) llmCalls = 0
                return SupportAssistantResult(
                    outcome = AssistantOutcome.UNKNOWN,
                    question = safeQuestion,
                    ticketId = safeTicketId,
                    context = context,
                    evidence = evidence,
                    response = GroundingValidator.canonicalUnknown(),
                    mcpToolsUsed = preparation.fetch.usedTools,
                    grounded = false,
                    currentTicketIsolationValid = true,
                    llmCalls = llmCalls,
                    failureReason = "Response generation failed: ${error.message ?: error::class.simpleName}",
                )
            }
        val validation = validator.validate(reply.content, prompt, context)
        val validated = validation.response
        if (!validation.valid || validated == null) {
            return SupportAssistantResult(
                outcome = AssistantOutcome.UNKNOWN,
                question = safeQuestion,
                ticketId = safeTicketId,
                context = context,
                evidence = evidence,
                response = GroundingValidator.canonicalUnknown(),
                mcpToolsUsed = preparation.fetch.usedTools,
                grounded = false,
                currentTicketIsolationValid = validation.reasons.none {
                    it == "cross-ticket reference" || it == "cross-user reference"
                },
                llmCalls = llmCalls,
                failureReason = "Grounding validation failed: ${validation.reasons.joinToString()}",
            )
        }
        val response = if (validated.status == ModelAnswerStatus.UNKNOWN) {
            GroundingValidator.canonicalUnknown()
        } else {
            validated
        }
        return SupportAssistantResult(
            outcome = if (response.status == ModelAnswerStatus.ANSWERED) {
                AssistantOutcome.ANSWERED
            } else {
                AssistantOutcome.UNKNOWN
            },
            question = safeQuestion,
            ticketId = safeTicketId,
            context = context,
            evidence = evidence,
            response = response,
            mcpToolsUsed = preparation.fetch.usedTools,
            grounded = true,
            currentTicketIsolationValid = true,
            llmCalls = llmCalls,
        )
    }

    private fun buildRetrievalQuery(question: String, context: SupportContext): String = buildString {
        append(question)
        context.facts.filter {
            it.id in setOf(
                "ticket.category",
                "ticket.productArea",
                "ticket.errorCode",
                "ticket.failedAuthAttempts",
                "ticket.deviceClockSkewSeconds",
                "user.accountState",
                "user.plan",
            )
        }.forEach { fact ->
            append("\n").append(fact.label).append(": ").append(fact.value)
        }
    }

    private fun fitPrompt(
        query: String,
        hits: List<RetrievedKnowledge>,
        question: String,
        context: SupportContext,
        recentHistory: List<ChatTurn>,
    ): Pair<KnowledgeEvidencePack, PromptPack?> {
        var candidateCount = hits.size
        while (candidateCount > 0) {
            val evidence = evidenceBuilder.build(query, hits.take(candidateCount))
            if (evidence.items.isEmpty()) break
            try {
                return evidence to promptBuilder.build(question, context, evidence, recentHistory)
            } catch (_: PromptBudgetExceededException) {
                candidateCount--
            }
        }
        return evidenceBuilder.build(query, emptyList()) to null
    }
}

class ChatSessionState(private val maxHistoryTurns: Int) {
    private val turns = ArrayDeque<ChatTurn>()

    var ticketId: String = "TCK-1001"
        private set
    var lastEvidence: KnowledgeEvidencePack? = null
        private set

    fun history(): List<ChatTurn> = turns.toList()

    fun switchTicket(newTicketId: String): Boolean {
        val safe = SupportDataPolicy.requireTicketId(newTicketId)
        if (safe == ticketId) return false
        ticketId = safe
        clear()
        return true
    }

    fun record(result: SupportAssistantResult) {
        turns.addLast(ChatTurn(result.ticketId, result.question, result.response.answer))
        while (turns.size > maxHistoryTurns) turns.removeFirst()
        lastEvidence = result.evidence
    }

    fun clear() {
        turns.clear()
        lastEvidence = null
    }
}
