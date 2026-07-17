package ru.ai.course.day33.supportassistant

class HybridRetriever(
    private val embeddings: EmbeddingClient,
    private val topK: Int,
    private val minRelevance: Double,
) {
    fun retrieve(question: String, enrichedQuery: String, index: RagIndexFile): List<RetrievedKnowledge> {
        val queryEmbedding = embeddings.embed(enrichedQuery)
        val queryTokens = TextTools.tokens(enrichedQuery).toSet()
        val questionTokens = TextTools.tokens(question).toSet()
        if (questionTokens.isEmpty()) return emptyList()

        return index.chunks.map { chunk ->
            val chunkTokens = TextTools.tokens("${chunk.heading} ${chunk.text}").toSet()
            val lexical = overlap(queryTokens, chunkTokens)
            val questionScore = overlap(questionTokens, chunkTokens)
            val vector = ((TextTools.cosine(queryEmbedding, chunk.embedding) + 1.0) / 2.0)
                .coerceIn(0.0, 1.0)
            val exactCodeBoost = queryTokens
                .filter { '_' in it || it.any(Char::isDigit) }
                .any { it in chunkTokens }
                .let { if (it) 0.12 else 0.0 }
            val score = (0.48 * lexical + 0.38 * vector + 0.14 * questionScore + exactCodeBoost)
                .coerceAtMost(1.0)
            RetrievedKnowledge(chunk, score, lexical, vector, questionScore)
        }
            .filter { it.score >= minRelevance && it.questionScore >= 0.045 }
            .sortedWith(compareByDescending<RetrievedKnowledge> { it.score }.thenBy { it.chunk.sourceId })
            .take(topK)
    }

    private fun overlap(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.size.coerceAtMost(right.size)
    }
}
