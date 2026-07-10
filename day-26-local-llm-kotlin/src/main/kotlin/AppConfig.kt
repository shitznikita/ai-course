import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val baseUrl: String,
    val model: String,
    val requestTimeout: Duration,
    val keepAlive: String,
) {
    fun endpoint(path: String): URI {
        require(path.startsWith('/')) { "API path must start with '/'." }
        return URI.create("$baseUrl$path")
    }

    companion object {
        fun load(): AppConfig {
            val values = loadEnvFile(Path(".env")) + System.getenv()
            val baseUrl = values.optionalValue("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
            validateLoopbackBaseUrl(baseUrl)

            val model = values.optionalValue("OLLAMA_MODEL") ?: "qwen3:14b"
            val timeoutSeconds = values.timeoutSeconds()
            val keepAlive = values.optionalValue("OLLAMA_KEEP_ALIVE") ?: "5m"

            return AppConfig(
                baseUrl = baseUrl.trim().trimEnd('/'),
                model = model,
                requestTimeout = Duration.ofSeconds(timeoutSeconds),
                keepAlive = keepAlive,
            )
        }

        private fun Map<String, String>.timeoutSeconds(): Long {
            val raw = optionalValue("OLLAMA_REQUEST_TIMEOUT_SECONDS") ?: return 300L
            val value = raw.toLongOrNull()
                ?: throw ConfigurationException("OLLAMA_REQUEST_TIMEOUT_SECONDS must be an integer, got '$raw'.")
            if (value !in 5L..900L) {
                throw ConfigurationException("OLLAMA_REQUEST_TIMEOUT_SECONDS must be between 5 and 900, got $value.")
            }
            return value
        }

        private fun Map<String, String>.optionalValue(name: String): String? =
            this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun validateLoopbackBaseUrl(value: String) {
            val uri = try {
                URI.create(value.trim())
            } catch (error: IllegalArgumentException) {
                throw ConfigurationException("OLLAMA_BASE_URL is not a valid URL: '$value'.")
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            val isLoopback = host in setOf("localhost", "127.0.0.1", "::1")

            if (
                uri.scheme?.lowercase() != "http" ||
                !isLoopback ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null ||
                (uri.path != null && uri.path !in setOf("", "/"))
            ) {
                throw ConfigurationException(
                    "OLLAMA_BASE_URL must be a plain local HTTP URL, for example http://127.0.0.1:11434.",
                )
            }
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path)
                .mapNotNull { rawLine ->
                    val line = rawLine.trim()
                    if (line.isBlank() || line.startsWith("#") || "=" !in line) {
                        null
                    } else {
                        val key = line.substringBefore("=").trim().removePrefix("export ").trim()
                        val value = line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                        key.takeIf { it.isNotBlank() }?.let { it to value }
                    }
                }
                .toMap()
        }
    }
}
