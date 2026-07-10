data class OllamaModel(
    val name: String,
    val modelAlias: String?,
    val sizeBytes: Long?,
    val parameterSize: String?,
    val quantizationLevel: String?,
)

data class OllamaStatus(
    val version: String,
    val models: List<OllamaModel>,
) {
    fun hasModel(requiredModel: String): Boolean =
        models.any {
            it.name.equals(requiredModel, ignoreCase = true) ||
                it.modelAlias?.equals(requiredModel, ignoreCase = true) == true
        }
}

data class OllamaReply(
    val model: String,
    val content: String,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val clientElapsedNanos: Long,
    val totalDurationNanos: Long?,
    val loadDurationNanos: Long?,
    val promptEvalDurationNanos: Long?,
    val evalDurationNanos: Long?,
)

data class DemoPrompt(
    val title: String,
    val prompt: String,
)

open class LocalLlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ConfigurationException(message: String) : LocalLlmException(message)

class OllamaUnavailableException(message: String, cause: Throwable? = null) : LocalLlmException(message, cause)

class OllamaHttpException(val statusCode: Int, responsePreview: String) :
    LocalLlmException("Ollama returned HTTP $statusCode: $responsePreview")

class OllamaProtocolException(message: String) : LocalLlmException(message)

class ModelNotInstalledException(message: String) : LocalLlmException(message)
