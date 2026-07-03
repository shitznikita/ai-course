class IndexingPipeline(private val config: AppConfig) {
    fun run(embeddingBackend: String = config.embeddingBackend): List<IndexBuildResult> {
        val corpus = DocumentLoader(config).load()
        check(corpus.documents.isNotEmpty()) {
            "No documents found in ${config.documentsDir.toAbsolutePath()}."
        }
        val embeddingClient = EmbeddingFactory.create(embeddingBackend, config)
        val storage = IndexStorage(config)
        val fixedStrategy = FixedSizeChunker(config.fixedChunkTokens, config.fixedChunkOverlap)
        val structuredStrategy = StructuredChunker(config.structuredMaxTokens)

        printCorpus(corpus, embeddingClient)
        val fixedDrafts = corpus.documents.flatMap { fixedStrategy.chunk(it) }
        val structuredDrafts = corpus.documents.flatMap { structuredStrategy.chunk(it) }
        println("CHUNKING")
        println("fixed chunks: ${fixedDrafts.size} (${config.fixedChunkTokens} tokens, overlap ${config.fixedChunkOverlap})")
        println("structured chunks: ${structuredDrafts.size} (max ${config.structuredMaxTokens} tokens)")
        println()

        val fixed = storage.saveIndex(fixedStrategy, corpus, fixedDrafts, embeddingClient)
        val structured = storage.saveIndex(structuredStrategy, corpus, structuredDrafts, embeddingClient)
        val report = ComparisonReport(config).build(corpus, fixed, structured, embeddingClient)
        storage.saveComparison(report)

        printResult(fixed)
        printResult(structured)
        println("COMPARISON REPORT: ${storage.comparisonPath().toAbsolutePath()}")
        println()
        println("CHECK: documents -> chunks -> embeddings -> JSON indexes + metadata + comparison report")
        return listOf(fixed, structured)
    }

    private fun printCorpus(corpus: LoadedCorpus, embeddingClient: EmbeddingClient) {
        println("Day 21: Document indexing pipeline")
        println("DOCUMENTS DIR: ${config.documentsDir.toAbsolutePath().normalize()}")
        println("INDEX DIR: ${config.indexDir.toAbsolutePath().normalize()}")
        println("EMBEDDING BACKEND: ${embeddingClient.backend}")
        println("EMBEDDING MODEL: ${embeddingClient.model}")
        println("LOADED DOCUMENTS: ${corpus.documents.size}")
        println("APPROX TOKENS: ${corpus.approxTokens}")
        println("APPROX PAGES: ${corpus.approxPages.formatDigits(1)}")
        if (corpus.skipped.isNotEmpty()) {
            println("SKIPPED FILES: ${corpus.skipped.size}")
        }
        println()
    }

    private fun printResult(result: IndexBuildResult) {
        val index = result.index
        println("${index.strategy.uppercase()} INDEX")
        println("path: ${result.path.toAbsolutePath()}")
        println("chunks: ${index.chunkCount}")
        println("embedding dimensions: ${index.embeddingDimensions}")
        index.chunks.take(3).forEach { chunk ->
            println("- ${chunk.metadata.chunkId} ${chunk.metadata.source} / ${chunk.metadata.section} (${chunk.metadata.approxTokens} tokens)")
        }
        println()
    }
}
