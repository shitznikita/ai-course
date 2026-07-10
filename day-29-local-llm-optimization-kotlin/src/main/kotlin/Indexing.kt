import kotlinx.serialization.decodeFromString
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.sqrt

class Week6IndexReader(private val config: AppConfig) {
    fun load(): Week6Index {
        val path = config.week6IndexFile
        if (!path.exists()) throw IndexValidationException("Week 6 index is missing at ${path.toAbsolutePath()}. ${rebuildIndexHint(config.embeddingModel)}")
        val index = try { AppJson.compact.decodeFromString<Week6Index>(path.readText()) } catch (error: Exception) {
            throw IndexValidationException("Week 6 index cannot be parsed: ${error.message}")
        }
        Week6IndexValidator.validate(index, config.embeddingModel)
        return index
    }
}

object Week6IndexValidator {
    fun validate(index: Week6Index, expectedEmbeddingModel: String) {
        val errors = mutableListOf<String>()
        if (index.strategy != "structured") errors += "strategy must be 'structured', got '${index.strategy}'"
        if (index.embeddingBackend != "ollama") errors += "embeddingBackend must be 'ollama', got '${index.embeddingBackend}'"
        if (!index.embeddingModel.equals(expectedEmbeddingModel, true)) errors += "embeddingModel is '${index.embeddingModel}', expected '$expectedEmbeddingModel'"
        if (index.sourceDocumentCount <= 0 || index.chunkCount <= 0 || index.chunks.isEmpty()) errors += "index has no chunks"
        if (index.chunkCount != index.chunks.size) errors += "chunkCount=${index.chunkCount} differs from chunks.size=${index.chunks.size}"
        if (index.embeddingDimensions <= 0) errors += "embeddingDimensions must be positive"
        index.chunks.forEachIndexed { position, chunk ->
            if (chunk.metadata.source.isBlank() || chunk.metadata.section.isBlank() || chunk.metadata.chunkId.isBlank()) errors += "chunk ${position + 1} has incomplete metadata"
            if (chunk.text.isBlank()) errors += "chunk ${position + 1} is blank"
            if (chunk.embedding.size != index.embeddingDimensions || chunk.embedding.any { !it.isFinite() } || chunk.embedding.sumOf { it * it } <= 0.0) {
                errors += "chunk ${position + 1} has invalid embedding"
            }
        }
        if (errors.isNotEmpty()) throw IndexValidationException("Week 6 index is incompatible: ${errors.take(4).joinToString("; ")}. ${rebuildIndexHint(expectedEmbeddingModel)}")
    }

    fun validateQueryEmbedding(index: Week6Index, embedding: List<Double>) {
        if (embedding.size != index.embeddingDimensions) throw IndexValidationException(
            "Query embedding has ${embedding.size} dimensions, but Week 6 index requires ${index.embeddingDimensions}. ${rebuildIndexHint(index.embeddingModel)}",
        )
        if (embedding.any { !it.isFinite() } || embedding.sumOf { it * it } <= 0.0) throw IndexValidationException("Query embedding is invalid.")
    }
}

fun rebuildIndexHint(embeddingModel: String): String =
    "Rebuild it locally with: CORPUS_MAX_FILES=400 FIXED_CHUNK_TOKENS=120 FIXED_CHUNK_OVERLAP=20 " +
        "STRUCTURED_MAX_TOKENS=120 OLLAMA_EMBED_MODEL=$embeddingModel EMBEDDING_BACKEND=ollama " +
        "day-21-document-indexing-kotlin/scripts/run-indexer.sh --args=\"ollama-demo\""

interface EmbeddingGateway { fun embed(texts: List<String>): EmbeddingReply }
interface RetrievalEngine { fun retrieve(index: Week6Index, question: String): RetrievalPackage }

class LocalRetriever(private val embeddings: EmbeddingGateway, private val config: AppConfig) : RetrievalEngine {
    override fun retrieve(index: Week6Index, question: String): RetrievalPackage {
        require(question.isNotBlank()) { "Question must not be blank." }
        val query = embeddings.embed(listOf(question)).embeddings.singleOrNull()
            ?: throw OllamaProtocolException("Ollama /api/embed must return exactly one query embedding.")
        Week6IndexValidator.validateQueryEmbedding(index, query)
        val results = index.chunks.map { SearchResult(strictCosine(query, it.embedding), it) }
            .sortedByDescending { it.score }.take(config.retrievalTopK)
        return ContextBuilder(config.ragMaxContextTokens).build(question, results)
    }
}

class ContextBuilder(private val maxTokens: Int) {
    fun build(question: String, candidates: List<SearchResult>): RetrievalPackage {
        val selected = mutableListOf<SearchResult>()
        val context = StringBuilder()
        var tokens = 0
        candidates.forEachIndexed { position, result ->
            val metadata = result.chunk.metadata
            val block = "[${position + 1}] source=${metadata.source}; title=${metadata.title}; section=${metadata.section}; " +
                "chunk_id=${metadata.chunkId}; score=${"%.4f".format(java.util.Locale.US, result.score)}\n${result.chunk.text.trim()}"
            val blockTokens = Tokenizer.approxTokens(block)
            if (blockTokens > maxTokens || tokens + blockTokens > maxTokens) return@forEachIndexed
            if (context.isNotEmpty()) context.appendLine().appendLine()
            context.append(block)
            selected += result
            tokens += blockTokens
        }
        return RetrievalPackage(question, selected, context.toString().trim(), tokens)
    }
}

object Tokenizer {
    fun approxTokens(text: String): Int = text.trim().takeIf { it.isNotEmpty() }?.let { (it.length + 3) / 4 } ?: 0
    fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}_-]+").findAll(text.lowercase()).map { it.value }.toList()
}

fun strictCosine(left: List<Double>, right: List<Double>): Double {
    require(left.size == right.size) { "Embedding dimensions differ: ${left.size} vs ${right.size}." }
    var dot = 0.0; var leftNorm = 0.0; var rightNorm = 0.0
    left.indices.forEach { index -> dot += left[index] * right[index]; leftNorm += left[index] * left[index]; rightNorm += right[index] * right[index] }
    require(leftNorm > 0.0 && rightNorm > 0.0) { "Cosine similarity requires non-zero vectors." }
    return dot / (sqrt(leftNorm) * sqrt(rightNorm))
}

fun SearchResult.toSummary() = RetrievedChunkSummary(score, chunk.metadata.source, chunk.metadata.title, chunk.metadata.section, chunk.metadata.chunkId, chunk.metadata.approxTokens)
