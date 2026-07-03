data class SearchResult(
    val score: Double,
    val chunk: IndexedChunk,
)

class VectorSearch(private val embeddingClient: EmbeddingClient) {
    fun search(index: DocumentIndex, query: String, topK: Int): List<SearchResult> {
        val queryEmbedding = embeddingClient.embed(listOf(query)).single()
        return index.chunks
            .map { SearchResult(cosineSimilarity(queryEmbedding, it.embedding), it) }
            .sortedByDescending { it.score }
            .take(topK)
    }
}
