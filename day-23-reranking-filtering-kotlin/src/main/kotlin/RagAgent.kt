class RagAgent(private val config: AppConfig) {
    private val embeddingClient = EmbeddingFactory.create(config)
    private val indexManager = IndexManager(config, embeddingClient)
    private val retriever = Retriever(embeddingClient)
    private val queryRewriter = QueryRewriter(config)
    private val reranker = HeuristicReranker(config)
    private val promptBuilder = PromptBuilder(config)

    fun retrieveBaseline(question: String): List<SearchResult> {
        val index = indexManager.ensureIndex()
        return retriever.search(index, question, config.rerankTopKAfter)
    }

    fun retrieveReranked(question: String): RerankedRetrieval {
        val index = indexManager.ensureIndex()
        val rewrite = queryRewriter.rewrite(question)
        val candidates = retriever.search(index, rewrite.rewritten, config.retrievalTopKBefore)
        return reranker.rerank(question, rewrite, candidates)
    }

    fun promptPreview(question: String): String =
        promptBuilder.renderPromptPreview(question, retrieveReranked(question).selected.map { it.toSearchResult() })

    fun askBaselineRag(question: String): AnswerRun {
        val results = retrieveBaseline(question)
        val response = LlmClient(config).chat(promptBuilder.ragMessages(question, results))
        return AnswerRun(
            mode = "baseline-rag",
            question = question,
            answer = response.content,
            sources = results.map { it.chunk.metadata.source }.distinct(),
            retrievedChunks = results.map { it.toSummary() },
            usage = response.usage,
            warningOrError = response.warningOrError,
        )
    }

    fun askRerankedRag(question: String): AnswerRun {
        val reranked = retrieveReranked(question)
        val results = reranked.selected.map { it.toSearchResult() }
        val response = LlmClient(config).chat(promptBuilder.ragMessages(question, results))
        return AnswerRun(
            mode = "reranked-rag",
            question = question,
            rewrittenQuery = reranked.rewrite.rewritten,
            answer = response.content,
            lowConfidence = reranked.lowConfidence,
            sources = results.map { it.chunk.metadata.source }.distinct(),
            retrievedChunks = results.map { it.toSummary() },
            usage = response.usage,
            warningOrError = response.warningOrError,
        )
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
