import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AppJson {
    val strict = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }
    val pretty = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }
}

@Serializable
data class IngredientCardsDocument(
    val version: String,
    val language: String,
    val updatedAt: String,
    val limitations: List<String>,
    val ingredients: List<IngredientCard>,
)

@Serializable
data class IngredientCard(
    val id: String,
    @SerialName("inci")
    val inciName: String,
    val aliases: List<String> = emptyList(),
    val functions: List<String>,
    val cautions: List<String> = emptyList(),
    val suitableFor: List<String> = emptyList(),
    val sourceIds: List<String>,
)

@Serializable
data class SourcesDocument(
    val version: String,
    val updatedAt: String,
    val sources: List<KnowledgeSource>,
)

@Serializable
data class KnowledgeSource(
    val id: String,
    val title: String,
    val organization: String,
    val type: String,
    val url: String,
    val notes: String,
)

@Serializable
data class ProductsDocument(
    val version: String,
    val updatedAt: String,
    val disclaimer: String,
    val products: List<CatalogProduct>,
)

@Serializable
data class CatalogProduct(
    val id: String,
    val brand: String,
    val name: String,
    val aliases: List<String>,
    val category: String,
    val isFictional: Boolean,
    val versions: List<ProductVersion>,
)

@Serializable
data class ProductVersion(
    val version: String,
    val market: String,
    val capturedAt: String,
    val effectiveFrom: String,
    val inci: String,
)

@Serializable
data class SkinProfile(
    val skinType: String? = null,
    val sensitive: Boolean = false,
    val allergies: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
)

@Serializable
data class AnalyzeTextRequest(
    val inciText: String,
    val productName: String? = null,
    val profile: SkinProfile = SkinProfile(),
)

@Serializable
data class AnalyzeNameRequest(
    val name: String,
    val profile: SkinProfile = SkinProfile(),
)

@Serializable
data class ChatRequest(
    val sessionId: String,
    val message: String,
)

@Serializable
data class AnalysisInputSummary(
    val type: String,
    val productName: String? = null,
    val matchedProductId: String? = null,
    val catalogVersion: String? = null,
    val catalogCategory: String? = null,
    val inciText: String,
    val parsedIngredientCount: Int,
    val recognizedIngredientCount: Int,
    val unknownIngredients: List<String>,
)

@Serializable
data class RoutineAdvice(
    val timeOfDay: List<String>,
    val step: String,
    val directions: String,
    val rinseOff: String,
    val sourceIds: List<String>,
)

@Serializable
data class IngredientInsight(
    val ingredientId: String,
    val whyItMatters: String,
    val sourceIds: List<String>,
)

@Serializable
data class CosmeticsReport(
    val status: String,
    val productType: String,
    val summary: String,
    val suitableSkinTypes: List<String>,
    val routine: RoutineAdvice,
    val keyIngredients: List<IngredientInsight>,
    val cautions: List<String>,
    val limitations: List<String>,
    val confidence: String,
    val sourceIds: List<String>,
    val disclaimer: String,
)

@Serializable
data class ModelAnalysisDecision(
    val status: String,
    val productType: String,
    val keyIngredientIds: List<String>,
    val confidence: String,
)

@Serializable
data class EvidenceSource(
    val id: String,
    val title: String,
    val organization: String,
    val url: String,
)

@Serializable
data class ModelMetrics(
    val name: String,
    val latencyMs: Long,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val outputTokensPerSecond: Double? = null,
)

@Serializable
data class AnalyzeResponse(
    val sessionId: String? = null,
    val input: AnalysisInputSummary,
    val report: CosmeticsReport,
    val sources: List<EvidenceSource>,
    val model: ModelMetrics? = null,
)

@Serializable
data class OcrResponse(
    val fileName: String,
    val format: String,
    val width: Int,
    val height: Int,
    val extractedText: String,
    val quality: String,
    val provider: String = "local_tesseract",
    val externalProcessing: Boolean = false,
    val uncertainFragments: List<String> = emptyList(),
    val notice: String? = null,
    val reviewRequired: Boolean = true,
)

@Serializable
data class ChatAnswer(
    val status: String,
    val answer: String,
    val sourceIds: List<String>,
    val limitations: List<String>,
)

@Serializable
data class ModelChatDecision(
    val status: String,
    val topic: String,
)

@Serializable
data class ChatResponse(
    val sessionId: String,
    val reply: ChatAnswer,
    val sources: List<EvidenceSource>,
    val model: ModelMetrics,
)

@Serializable
data class DeleteSessionResponse(
    val deleted: Boolean,
)

@Serializable
data class HealthResponse(
    val status: String,
    val model: String,
    val ollamaVersion: String? = null,
    val modelInstalled: Boolean,
    val ocrReady: Boolean,
    val ingredientCards: Int,
    val catalogProducts: Int,
    val photoOcrProvider: String = "local_tesseract",
    val externalPhotoProcessing: Boolean = false,
)

@Serializable
data class LivenessResponse(
    val status: String = "alive",
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val hint: String? = null,
)

data class ParsedInci(
    val rawText: String,
    val ingredients: List<String>,
)

data class RecognizedIngredient(
    val rawName: String,
    val card: IngredientCard,
)

data class EvidencePack(
    val parsed: ParsedInci,
    val recognized: List<RecognizedIngredient>,
    val unknown: List<String>,
    val sources: List<KnowledgeSource>,
)

data class UploadedPhoto(
    val fileName: String,
    val format: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
)

data class OcrResult(
    val text: String,
    val quality: String,
    val provider: String = "local_tesseract",
    val externalProcessing: Boolean = false,
    val uncertainFragments: List<String> = emptyList(),
    val notice: String? = null,
)

data class OllamaModel(
    val name: String,
    val alias: String?,
)

data class OllamaStatus(
    val version: String,
    val models: List<OllamaModel>,
) {
    fun hasModel(required: String): Boolean = models.any {
        it.name.equals(required, ignoreCase = true) || it.alias?.equals(required, ignoreCase = true) == true
    }
}

data class OllamaReply(
    val model: String,
    val content: String,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val elapsedNanos: Long,
    val evalDurationNanos: Long?,
) {
    fun metrics(): ModelMetrics = ModelMetrics(
        name = model,
        latencyMs = elapsedNanos / 1_000_000,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        outputTokensPerSecond = if (completionTokens != null && evalDurationNanos != null && evalDurationNanos > 0) {
            completionTokens * 1_000_000_000.0 / evalDurationNanos
        } else null,
    )
}

data class StoredChatMessage(
    val role: String,
    val content: String,
)

data class AnalysisSession(
    val id: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val input: AnalysisInputSummary,
    val profile: SkinProfile,
    val report: CosmeticsReport,
    val cards: List<IngredientCard>,
    val sources: List<KnowledgeSource>,
    val history: List<StoredChatMessage>,
)

open class LocalServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ConfigurationException(message: String) : LocalServiceException(message)
class KnowledgeException(message: String, cause: Throwable? = null) : LocalServiceException(message, cause)
class OcrUnavailableException(message: String, cause: Throwable? = null) : LocalServiceException(message, cause)
class OllamaUnavailableException(message: String, cause: Throwable? = null) : LocalServiceException(message, cause)
class OllamaHttpException(val statusCode: Int, val endpoint: String) : LocalServiceException("Ollama returned HTTP $statusCode at $endpoint.")
class OllamaProtocolException(message: String) : LocalServiceException(message)

open class ApiProblem(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val hint: String? = null,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)
