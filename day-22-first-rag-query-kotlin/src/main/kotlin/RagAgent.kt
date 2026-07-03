class RagAgent(private val config: AppConfig) {
    private val embeddingClient = EmbeddingFactory.create(config)
    private val indexManager = IndexManager(config, embeddingClient)
    private val retriever = Retriever(embeddingClient)
    private val promptBuilder = PromptBuilder(config)

    fun retrieve(question: String): List<SearchResult> {
        val index = indexManager.ensureIndex()
        return retriever.search(index, question, config.retrievalTopK)
    }

    fun promptPreview(question: String): String =
        promptBuilder.renderPromptPreview(question, retrieve(question))

    fun askNoRag(question: String): AnswerRun {
        val response = LlmClient(config).chat(promptBuilder.noRagMessages(question))
        return AnswerRun(
            mode = "no-rag",
            question = question,
            answer = response.content,
            usage = response.usage,
            warningOrError = response.warningOrError,
        )
    }

    fun askRag(question: String): AnswerRun {
        val results = retrieve(question)
        val response = LlmClient(config).chat(promptBuilder.ragMessages(question, results))
        return AnswerRun(
            mode = "rag",
            question = question,
            answer = response.content,
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
