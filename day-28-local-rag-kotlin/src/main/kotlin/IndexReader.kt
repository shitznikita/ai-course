import kotlinx.serialization.decodeFromString
import kotlin.io.path.exists
import kotlin.io.path.readText

class Week6IndexReader(private val config: AppConfig) {
    fun load(): Week6Index {
        val path = config.week6IndexFile
        if (!path.exists()) {
            throw IndexValidationException(
                "Week 6 index is missing at ${path.toAbsolutePath()}. ${rebuildIndexHint(config.embeddingModel)}",
            )
        }
        val index = try {
            AppJson.compact.decodeFromString<Week6Index>(path.readText())
        } catch (error: Exception) {
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
        if (!index.embeddingModel.equals(expectedEmbeddingModel, ignoreCase = true)) {
            errors += "embeddingModel is '${index.embeddingModel}', expected '$expectedEmbeddingModel'"
        }
        if (index.sourceDocumentCount <= 0) errors += "sourceDocumentCount must be positive"
        if (index.chunkCount <= 0 || index.chunks.isEmpty()) errors += "index has no chunks"
        if (index.chunkCount != index.chunks.size) {
            errors += "chunkCount=${index.chunkCount} differs from chunks.size=${index.chunks.size}"
        }
        if (index.embeddingDimensions <= 0) errors += "embeddingDimensions must be positive"
        index.chunks.forEachIndexed { position, chunk ->
            if (chunk.metadata.source.isBlank() || chunk.metadata.section.isBlank() || chunk.metadata.chunkId.isBlank()) {
                errors += "chunk ${position + 1} has incomplete metadata"
            }
            if (chunk.text.isBlank()) errors += "chunk ${position + 1} is blank"
            if (chunk.embedding.size != index.embeddingDimensions) {
                errors += "chunk ${position + 1} has ${chunk.embedding.size} dimensions, expected ${index.embeddingDimensions}"
            }
            if (chunk.embedding.any { !it.isFinite() }) errors += "chunk ${position + 1} has non-finite embedding values"
            val squaredLength = chunk.embedding.sumOf { it * it }
            if (!squaredLength.isFinite() || squaredLength <= 0.0) {
                errors += "chunk ${position + 1} has a zero or invalid embedding length"
            }
        }
        if (errors.isNotEmpty()) {
            throw IndexValidationException("Week 6 index is incompatible: ${errors.take(4).joinToString("; ")}. ${rebuildIndexHint(expectedEmbeddingModel)}")
        }
    }

    fun validateQueryEmbedding(index: Week6Index, embedding: List<Double>) {
        if (embedding.size != index.embeddingDimensions) {
            throw IndexValidationException(
                "Query embedding has ${embedding.size} dimensions, but Week 6 index requires ${index.embeddingDimensions}. " +
                    rebuildIndexHint(index.embeddingModel),
            )
        }
        if (embedding.any { !it.isFinite() }) {
            throw IndexValidationException("Query embedding contains non-finite values.")
        }
        if (embedding.sumOf { it * it } <= 0.0) {
            throw IndexValidationException("Query embedding has zero length.")
        }
    }
}

fun rebuildIndexHint(embeddingModel: String): String =
    "Rebuild it locally with: CORPUS_MAX_FILES=400 FIXED_CHUNK_TOKENS=120 FIXED_CHUNK_OVERLAP=20 " +
        "STRUCTURED_MAX_TOKENS=120 OLLAMA_EMBED_MODEL=$embeddingModel EMBEDDING_BACKEND=ollama " +
        "day-21-document-indexing-kotlin/scripts/run-indexer.sh --args=\"ollama-demo\""
