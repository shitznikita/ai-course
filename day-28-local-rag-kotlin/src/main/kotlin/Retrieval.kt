import kotlin.math.sqrt

interface EmbeddingGateway {
    fun embed(texts: List<String>): EmbeddingReply
}

interface RetrievalEngine {
    fun retrieve(index: Week6Index, question: String): RetrievalPackage
}

class LocalRetriever(
    private val embeddingGateway: EmbeddingGateway,
    private val config: AppConfig,
) : RetrievalEngine {
    override fun retrieve(index: Week6Index, question: String): RetrievalPackage {
        require(question.isNotBlank()) { "Question must not be blank." }
        val reply = embeddingGateway.embed(listOf(question))
        val queryEmbedding = reply.embeddings.singleOrNull()
            ?: throw OllamaProtocolException("Ollama /api/embed must return exactly one query embedding.")
        Week6IndexValidator.validateQueryEmbedding(index, queryEmbedding)
        val normalizedQuery = normalizeVector(queryEmbedding)
        val results = index.chunks
            .map { chunk -> SearchResult(strictCosine(normalizedQuery, chunk.embedding), chunk) }
            .sortedByDescending { it.score }
            .take(config.retrievalTopK)
        return ContextBuilder(config.ragMaxContextTokens).build(question, results)
    }
}

class ContextBuilder(private val maxTokens: Int) {
    fun build(question: String, candidates: List<SearchResult>): RetrievalPackage {
        val selected = mutableListOf<SearchResult>()
        val rendered = StringBuilder()
        var tokens = 0

        candidates.forEachIndexed { index, result ->
            val chunk = result.chunk
            val block = buildString {
                appendLine("[${index + 1}] source=${chunk.metadata.source}; title=${chunk.metadata.title}; " +
                    "section=${chunk.metadata.section}; chunk_id=${chunk.metadata.chunkId}; score=${result.score.formatScore()}")
                append(chunk.text.trim())
            }
            val blockTokens = Tokenizer.approxTokens(block)
            if (blockTokens > maxTokens || tokens + blockTokens > maxTokens) return@forEachIndexed
            if (rendered.isNotEmpty()) rendered.appendLine().appendLine()
            rendered.append(block)
            selected += result
            tokens += blockTokens
        }

        return RetrievalPackage(
            question = question,
            selected = selected,
            context = rendered.toString().trim(),
            contextTokens = tokens,
        )
    }
}

object Tokenizer {
    fun approxTokens(text: String): Int =
        text.trim().takeIf { it.isNotEmpty() }?.let { (it.length + 3) / 4 } ?: 0

    fun words(text: String): List<String> =
        Regex("[\\p{L}\\p{N}_-]+").findAll(text.lowercase()).map { it.value }.toList()
}

fun strictCosine(left: List<Double>, right: List<Double>): Double {
    require(left.size == right.size) { "Embedding dimensions differ: ${left.size} vs ${right.size}." }
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        dot += left[index] * right[index]
        leftNorm += left[index] * left[index]
        rightNorm += right[index] * right[index]
    }
    require(leftNorm > 0.0 && rightNorm > 0.0) { "Cosine similarity requires non-zero vectors." }
    return dot / (sqrt(leftNorm) * sqrt(rightNorm))
}

fun normalizeVector(vector: List<Double>): List<Double> {
    val norm = sqrt(vector.sumOf { it * it })
    require(norm > 0.0) { "Embedding vector must not be zero." }
    return vector.map { it / norm }
}

fun SearchResult.toSummary(): RetrievedChunkSummary = RetrievedChunkSummary(
    score = score,
    source = chunk.metadata.source,
    title = chunk.metadata.title,
    section = chunk.metadata.section,
    chunkId = chunk.metadata.chunkId,
    approxTokens = chunk.metadata.approxTokens,
)

private fun Double.formatScore(): String = "%.4f".format(java.util.Locale.US, this)
