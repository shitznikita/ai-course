fun main(args: Array<String>) {
    val config = AppConfig.load()
    when (args.firstOrNull() ?: "fixture-demo") {
        "fixture-demo" -> IndexingPipeline(config.copy(embeddingBackend = "hash")).run("hash")
        "ollama-demo" -> IndexingPipeline(config.copy(embeddingBackend = "ollama")).run("ollama")
        "index" -> IndexingPipeline(config).run()
        "search" -> runSearch(config, args.drop(1))
        else -> printUsage()
    }
}

private fun runSearch(config: AppConfig, args: List<String>) {
    if (args.size < 2) {
        printUsage()
        return
    }
    val strategy = args.first().lowercase()
    check(strategy == "fixed" || strategy == "structured") {
        "Strategy must be 'fixed' or 'structured'."
    }
    val query = args.drop(1).joinToString(" ").trim()
    check(query.isNotBlank()) { "Search query must not be blank." }

    val storage = IndexStorage(config)
    val index = storage.loadIndex(strategy)
    val embeddingClient = EmbeddingFactory.create(index.embeddingBackend, config)
    val results = VectorSearch(embeddingClient).search(index, query, config.retrievalTopK)

    println("Day 21: Search")
    println("strategy: ${index.strategy}")
    println("index: ${storage.indexPath(strategy).toAbsolutePath()}")
    println("embedding backend: ${index.embeddingBackend}")
    println("query: $query")
    println()
    results.forEachIndexed { position, result ->
        val chunk = result.chunk
        println("${position + 1}. score=${result.score.formatDigits()}")
        println("   source: ${chunk.metadata.source}")
        println("   title: ${chunk.metadata.title}")
        println("   section: ${chunk.metadata.section}")
        println("   chunk_id: ${chunk.metadata.chunkId}")
        println("   preview: ${chunk.text.shortPreview(220)}")
        println()
    }
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo                 Build fixed + structured JSON indexes with deterministic hash embeddings")
    println("  ollama-demo                  Build fixed + structured JSON indexes with local Ollama embeddings")
    println("  index                        Build indexes using EMBEDDING_BACKEND")
    println("  search <fixed|structured> <query>")
}
