import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AppJson {
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
data class DocumentIndex(
    val generatedAtIso: String,
    val strategy: String,
    val embeddingBackend: String,
    val embeddingModel: String,
    val corpusMaxFiles: Int = 0,
    val structuredMaxTokens: Int = 0,
    val sourceDocumentCount: Int,
    val chunkCount: Int,
    val embeddingDimensions: Int,
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

data class SearchResult(
    val score: Double,
    val chunk: IndexedChunk,
)

@Serializable
data class ControlQuestion(
    val id: String,
    val question: String,
    val expectedAnswerPoints: List<String>,
    val expectedSources: List<String>,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ApiUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
)

@Serializable
data class LlmResponse(
    val content: String,
    val usage: ApiUsage? = null,
    val warningOrError: String? = null,
)

@Serializable
data class AnswerRun(
    val mode: String,
    val question: String,
    val answer: String,
    val sources: List<String> = emptyList(),
    val retrievedChunks: List<RetrievedChunkSummary> = emptyList(),
    val usage: ApiUsage? = null,
    val warningOrError: String? = null,
)

@Serializable
data class RetrievedChunkSummary(
    val score: Double,
    val source: String,
    val title: String,
    val section: String,
    val chunkId: String,
    val approxTokens: Int,
)

@Serializable
data class EvaluationRecord(
    val id: String,
    val question: String,
    val expectedAnswerPoints: List<String>,
    val expectedSources: List<String>,
    val retrievedSources: List<String>,
    val expectedSourceHits: List<String>,
    val noRag: AnswerRun? = null,
    val rag: AnswerRun? = null,
)

@Serializable
data class EvaluationReport(
    val generatedAtIso: String,
    val mode: String,
    val questionCount: Int,
    val expectedSourceHitCount: Int,
    val records: List<EvaluationRecord>,
)
