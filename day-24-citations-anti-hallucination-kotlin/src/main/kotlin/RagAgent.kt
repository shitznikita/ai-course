class RagAgent(private val config: AppConfig) {
    private val embeddingClient = EmbeddingFactory.create(config)
    private val indexManager = IndexManager(config, embeddingClient)
    private val retriever = Retriever(embeddingClient)
    private val queryRewriter = QueryRewriter(config)
    private val reranker = HeuristicReranker(config)
    private val quoteExtractor = QuoteExtractor(config)
    private val promptBuilder = PromptBuilder(config)
    private val validator = GroundingValidator()

    fun retrieveReranked(question: String): RerankedRetrieval {
        val index = indexManager.ensureIndex()
        val rewrite = queryRewriter.rewrite(question)
        val candidates = retriever.search(index, rewrite.rewritten, config.retrievalTopKBefore)
        return reranker.rerank(question, rewrite, candidates)
    }

    fun askDryRun(
        question: String,
        expectedAnswerPoints: List<String> = emptyList(),
    ): GroundedRun {
        val retrieval = retrieveReranked(question)
        val quoteCandidates = quoteExtractor.extract(question, retrieval, expectedAnswerPoints)
        val promptPreview = promptBuilder.renderGroundedPromptPreview(question, retrieval, quoteCandidates)
        val answer = localAnswer(question, retrieval, quoteCandidates, expectedAnswerPoints)
        val validation = validator.validate(answer, retrieval, quoteCandidates, expectedAnswerPoints)
        return GroundedRun(
            mode = "dry-run",
            question = question,
            retrieval = retrieval,
            quoteCandidates = quoteCandidates,
            answer = answer,
            validation = validation,
            promptPreview = promptPreview,
        )
    }

    fun askLive(
        question: String,
        expectedAnswerPoints: List<String> = emptyList(),
    ): GroundedRun {
        val retrieval = retrieveReranked(question)
        val quoteCandidates = quoteExtractor.extract(question, retrieval, expectedAnswerPoints)
        val promptPreview = promptBuilder.renderGroundedPromptPreview(question, retrieval, quoteCandidates)
        if (!hasEnoughEvidence(retrieval, quoteCandidates)) {
            val answer = unknownAnswer(question)
            val validation = validator.validate(answer, retrieval, quoteCandidates, expectedAnswerPoints)
            return GroundedRun(
                mode = "live-gated",
                question = question,
                retrieval = retrieval,
                quoteCandidates = quoteCandidates,
                answer = answer,
                validation = validation,
                promptPreview = promptPreview,
            )
        }

        val response = LlmClient(config).chat(promptBuilder.groundedMessages(question, retrieval, quoteCandidates))
        val parsed = runCatching { GroundedAnswerParser.parse(response.content) }
        val answer = parsed.getOrElse {
            GroundedAnswer(
                status = "unknown",
                answer = "не знаю",
                clarifyingQuestion = "Не удалось разобрать JSON-ответ модели. Уточните вопрос или повторите запрос.",
            )
        }
        val validation = validator.validate(answer, retrieval, quoteCandidates, expectedAnswerPoints)
        return GroundedRun(
            mode = "live",
            question = question,
            retrieval = retrieval,
            quoteCandidates = quoteCandidates,
            answer = answer,
            validation = validation,
            promptPreview = promptPreview,
            warningOrError = response.warningOrError ?: parsed.exceptionOrNull()?.message,
        )
    }

    private fun localAnswer(
        question: String,
        retrieval: RerankedRetrieval,
        quoteCandidates: List<QuoteCandidate>,
        expectedAnswerPoints: List<String>,
    ): GroundedAnswer {
        if (!hasEnoughEvidence(retrieval, quoteCandidates)) return unknownAnswer(question)
        val sources = quoteCandidates
            .map { CitationSource(it.source, it.section, it.chunkId) }
            .distinct()
        val quotes = quoteCandidates.map {
            AnswerQuote(
                quoteId = it.id,
                source = it.source,
                section = it.section,
                chunkId = it.chunkId,
                text = it.text,
            )
        }
        val answer = if (expectedAnswerPoints.isNotEmpty()) {
            "Ответ должен включать: ${expectedAnswerPoints.joinToString("; ")}."
        } else {
            "Найден релевантный контекст. Live-режим сформирует финальный ответ только по указанным источникам и цитатам."
        }
        return GroundedAnswer(
            status = "answered",
            answer = answer,
            sources = sources,
            quotes = quotes,
            clarifyingQuestion = null,
        )
    }

    private fun unknownAnswer(question: String): GroundedAnswer =
        GroundedAnswer(
            status = "unknown",
            answer = "не знаю",
            sources = emptyList(),
            quotes = emptyList(),
            clarifyingQuestion = "Уточните, пожалуйста, какие документы или день курса нужно использовать для ответа на вопрос: $question",
        )

    private fun hasEnoughEvidence(retrieval: RerankedRetrieval, quoteCandidates: List<QuoteCandidate>): Boolean {
        val maxRelevance = retrieval.selected.maxOfOrNull { it.rerankScore } ?: 0.0
        return maxRelevance >= config.answerMinRelevance &&
            !retrieval.lowConfidence &&
            quoteCandidates.size >= config.minQuotes
    }
}

fun SearchResult.toSummary(): RetrievedChunkSummary =
    RetrievedChunkSummary(
        score = score,
        source = chunk.metadata.source,
        title = chunk.metadata.title,
        section = chunk.metadata.section,
        chunkId = chunk.metadata.chunkId,
        approxTokens = chunk.metadata.approxTokens,
    )

fun RerankedChunk.toSummary(): RerankedChunkSummary =
    RerankedChunkSummary(
        baseScore = result.score,
        rerankScore = rerankScore,
        lexicalOverlap = lexicalOverlap,
        metadataBoost = metadataBoost,
        passedThreshold = passedThreshold,
        fallback = fallback,
        source = result.chunk.metadata.source,
        title = result.chunk.metadata.title,
        section = result.chunk.metadata.section,
        chunkId = result.chunk.metadata.chunkId,
        approxTokens = result.chunk.metadata.approxTokens,
    )
