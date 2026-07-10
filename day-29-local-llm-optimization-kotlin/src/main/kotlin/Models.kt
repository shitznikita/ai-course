import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AppJson {
    val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val pretty = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
}

@Serializable data class Day21IndexConfig(val documentsDir: String, val fixedChunkTokens: Int, val fixedChunkOverlap: Int, val structuredMaxTokens: Int)
@Serializable data class Week6Index(
    val generatedAtIso: String, val strategy: String, val embeddingBackend: String, val embeddingModel: String,
    val sourceDocumentCount: Int, val chunkCount: Int, val embeddingDimensions: Int, val config: Day21IndexConfig,
    val chunks: List<IndexedChunk>,
)
@Serializable data class ChunkMetadata(
    val source: String, val title: String, val section: String, @SerialName("chunkId") val chunkId: String,
    val strategy: String, val ordinal: Int, val approxTokens: Int,
)
@Serializable data class IndexedChunk(val metadata: ChunkMetadata, val text: String, val embedding: List<Double>)

data class PromptMessage(val role: String, val content: String)
data class OllamaModel(val name: String, val alias: String?)
data class OllamaStatus(val version: String, val models: List<OllamaModel>) {
    fun hasModel(required: String): Boolean = models.any { it.name.matches(required) || it.alias?.matches(required) == true }
}
private fun String.matches(required: String): Boolean = equals(required, true) || removeSuffix(":latest").equals(required.removeSuffix(":latest"), true)

data class OllamaModelDetails(val model: String, val parameterSize: String?, val quantizationLevel: String?, val contextLength: Long?)
@Serializable data class RunningModel(
    val name: String,
    val model: String? = null,
    val size: Long? = null,
    @SerialName("size_vram") val sizeVram: Long? = null,
    @SerialName("context_length") val contextLength: Long? = null,
    val processor: String? = null,
    val quantization: String? = null,
)
@Serializable data class ResourceSnapshot(
    val capturedAtIso: String,
    val runningModels: List<RunningModel>,
    val memoryPressure: String?,
)

data class EmbeddingReply(val model: String, val embeddings: List<List<Double>>, val promptTokens: Long?, val clientElapsedNanos: Long)
data class ChatReply(val model: String, val content: String, val promptTokens: Long?, val completionTokens: Long?, val clientElapsedNanos: Long, val evalDurationNanos: Long?)
data class SearchResult(val score: Double, val chunk: IndexedChunk)
data class RetrievalPackage(val question: String, val selected: List<SearchResult>, val context: String, val contextTokens: Int)

/** Compact output contract: source chunk IDs and quotes are resolved against the retrieved local chunks. */
@Serializable data class GroundedAnswer(val status: String, val answer: String, val sources: List<String>, val quotes: List<String>, val clarifyingQuestion: String?)
@Serializable data class GroundingValidation(
    val validJsonContract: Boolean, val statusValid: Boolean, val answerPresent: Boolean,
    val sourcesPresentWhenAnswered: Boolean, val quotesPresentWhenAnswered: Boolean,
    val sourcesMatchRetrieved: Boolean, val quotesMatchChunks: Boolean,
    val expectedPointsCovered: Int, val expectedPointsTotal: Int, val unknownBehaviorValid: Boolean,
    val errors: List<String> = emptyList(),
) {
    val grounded: Boolean get() = validJsonContract && statusValid && answerPresent && sourcesPresentWhenAnswered &&
        quotesPresentWhenAnswered && sourcesMatchRetrieved && quotesMatchChunks && unknownBehaviorValid
}
@Serializable data class GenerationMetrics(
    val model: String, val profile: String, val latencyMs: Long, val promptTokens: Long? = null,
    val completionTokens: Long? = null, val outputTokensPerSecond: Double? = null, val error: String? = null,
)
@Serializable data class GenerationOutcome(val transportSucceeded: Boolean, val answer: GroundedAnswer? = null, val validation: GroundingValidation? = null, val metrics: GenerationMetrics)
@Serializable data class BenchmarkQuestion(val id: String, val question: String, val expectedAnswerPoints: List<String>, val expectedSources: List<String>)
@Serializable data class RetrievedChunkSummary(
    val score: Double, val source: String, val title: String, val section: String,
    @SerialName("chunk_id") val chunkId: String, val approxTokens: Int,
)
@Serializable data class OptimizationRecord(
    val questionId: String, val question: String, val iteration: Int, val profile: String, val profileLabel: String,
    val temperature: Double, val numPredict: Int, val numCtx: Int, val retrievedChunks: List<RetrievedChunkSummary>,
    val outcome: GenerationOutcome, val before: ResourceSnapshot?, val after: ResourceSnapshot?,
)
@Serializable data class ProfileAggregate(
    val profile: String, val model: String, val runs: Int, val transportSuccessRate: Double, val groundedRate: Double,
    val validJsonRate: Double, val expectedPointRecall: Double?, val sourceSetStability: Double?,
    val latencyMinMs: Long?, val latencyMedianMs: Long?, val latencyMaxMs: Long?,
    val outputTokensPerSecondMedian: Double?, val maxModelBytes: Long?, val maxVramBytes: Long?, val maxContextLength: Long?,
)
@Serializable data class OptimizationRecommendation(val primaryProfile: String, val q8QualityDelta: Double?, val text: String)
@Serializable data class OptimizationReport(
    val generatedAtIso: String, val indexFile: String, val embeddingModel: String, val runsPerQuestion: Int,
    val records: List<OptimizationRecord>, val aggregates: List<ProfileAggregate>, val recommendation: OptimizationRecommendation,
)

open class OptimizationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ConfigurationException(message: String) : OptimizationException(message)
class IndexValidationException(message: String) : OptimizationException(message)
class OllamaUnavailableException(message: String, cause: Throwable? = null) : OptimizationException(message, cause)
class OllamaHttpException(val statusCode: Int, val endpoint: String) : OptimizationException("Ollama $endpoint returned HTTP $statusCode.")
class OllamaProtocolException(message: String) : OptimizationException(message)
