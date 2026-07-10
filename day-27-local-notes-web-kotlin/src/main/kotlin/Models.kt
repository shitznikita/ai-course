import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AppJson {
    val instance = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

data class UploadedNote(
    val fileName: String,
    val format: String,
    val text: String,
) {
    val charCount: Int
        get() = text.length
}

@Serializable
data class AnalysisReport(
    val summary: String,
    val decisions: List<String>,
    val actionItems: List<ActionItem>,
    val risks: List<String>,
    val openQuestions: List<String>,
)

@Serializable
data class ActionItem(
    val task: String,
    val owner: String? = null,
    val deadline: String? = null,
)

@Serializable
data class AnalyzeResponse(
    val source: SourceMetadata,
    val report: AnalysisReport,
    val model: ModelMetrics,
)

@Serializable
data class SourceMetadata(
    val fileName: String,
    val format: String,
    val charCount: Int,
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
data class HealthResponse(
    val ready: Boolean,
    val model: String,
    val ollamaVersion: String,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val hint: String? = null,
)

data class OllamaModel(
    val name: String,
    val alias: String?,
)

data class OllamaStatus(
    val version: String,
    val models: List<OllamaModel>,
) {
    fun hasModel(requiredModel: String): Boolean =
        models.any {
            it.name.equals(requiredModel, ignoreCase = true) ||
                it.alias?.equals(requiredModel, ignoreCase = true) == true
        }
}

data class OllamaReply(
    val model: String,
    val content: String,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val clientElapsedNanos: Long,
    val evalDurationNanos: Long?,
)

open class LocalLlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ConfigurationException(message: String) : LocalLlmException(message)

class OllamaUnavailableException(message: String, cause: Throwable? = null) : LocalLlmException(message, cause)

class OllamaHttpException(
    val statusCode: Int,
    val endpoint: String,
    responsePreview: String,
) :
    LocalLlmException("Ollama returned HTTP $statusCode: $responsePreview")

class OllamaProtocolException(message: String) : LocalLlmException(message)

class ModelNotInstalledException(message: String) : LocalLlmException(message)

open class ApiProblem(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val hint: String? = null,
) : RuntimeException(message)
