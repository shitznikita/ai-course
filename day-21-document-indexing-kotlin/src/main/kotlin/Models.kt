import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object IndexJson {
    val compact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}

@Serializable
data class SourceDocument(
    val source: String,
    val title: String,
    val type: String,
    val text: String,
)

@Serializable
data class ChunkMetadata(
    val source: String,
    val title: String,
    val section: String,
    val chunkId: String,
    val strategy: String,
    val ordinal: Int,
    val approxTokens: Int,
)

@Serializable
data class ChunkDraft(
    val metadata: ChunkMetadata,
    val text: String,
)

@Serializable
data class IndexedChunk(
    val metadata: ChunkMetadata,
    val text: String,
    val embedding: List<Double>,
)

@Serializable
data class IndexConfigSnapshot(
    val documentsDir: String,
    val fixedChunkTokens: Int,
    val fixedChunkOverlap: Int,
    val structuredMaxTokens: Int,
)

@Serializable
data class DocumentIndex(
    val generatedAtIso: String,
    val strategy: String,
    val embeddingBackend: String,
    val embeddingModel: String,
    val sourceDocumentCount: Int,
    val chunkCount: Int,
    val embeddingDimensions: Int,
    val config: IndexConfigSnapshot,
    val chunks: List<IndexedChunk>,
)

data class LoadedCorpus(
    val documentsDir: String,
    val documents: List<SourceDocument>,
    val skipped: List<String>,
) {
    val approxTokens: Int = documents.sumOf { Tokenizer.approxTokens(it.text) }
    val approxPages: Double = approxTokens / 450.0
}

data class IndexBuildResult(
    val index: DocumentIndex,
    val path: java.nio.file.Path,
)
