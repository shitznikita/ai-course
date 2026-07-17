package ru.ai.course.day32.codereview

class HybridRetriever {
    fun retrieve(query: String, chunks: List<SourceChunk>, limit: Int = 24): List<RetrievalHit> {
        val queryTokens = TextTools.tokens(query).filter { it.length >= 2 }.toSet()
        val queryVector = TextTools.hashEmbedding(query)
        val pathHints = query.lineSequence()
            .filter { it.startsWith("path=") }
            .map { it.removePrefix("path=").substringBeforeLast('/', "") }
            .filter(String::isNotBlank)
            .toSet()
        return chunks.map { chunk ->
            val searchable = "${chunk.path}\n${chunk.section}\n${chunk.content}"
            val chunkTokens = TextTools.tokens(searchable).toSet()
            val overlap = if (queryTokens.isEmpty()) 0.0 else {
                queryTokens.count(chunkTokens::contains).toDouble() / queryTokens.size
            }
            val vector = TextTools.cosine(queryVector, TextTools.hashEmbedding(searchable)).coerceAtLeast(0.0)
            val proximity = proximity(chunk, queryTokens, pathHints)
            RetrievalHit(
                chunk = chunk,
                score = (overlap * 0.50 + vector * 0.30 + proximity).coerceAtMost(1.0),
                lexicalScore = overlap,
                vectorScore = vector,
                proximityScore = proximity,
            )
        }.sortedWith(compareByDescending<RetrievalHit> { it.score }.thenBy { it.chunk.id })
            .take(limit)
    }

    private fun proximity(chunk: SourceChunk, queryTokens: Set<String>, pathHints: Set<String>): Double {
        var score = 0.0
        if (pathHints.any { hint -> hint.isNotBlank() && chunk.path.startsWith(hint) }) score += 0.12
        val pathTokens = TextTools.tokens(chunk.path).toSet()
        if (pathTokens.any(queryTokens::contains)) score += 0.05
        if (chunk.path == "AGENTS.md" || chunk.path == "README.md") score += 0.04
        if (chunk.category == CorpusCategory.DOCUMENTATION && queryTokens.any { it in architectureTerms }) score += 0.05
        return score.coerceAtMost(0.20)
    }

    companion object {
        private val architectureTerms = setOf(
            "architecture", "architectural", "архитектура", "module", "модуль", "boundary", "граница",
        )
    }
}
