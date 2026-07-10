import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val serverHost: String,
    val serverPort: Int,
    val ollamaBaseUrl: String,
    val model: String,
    val requestTimeout: Duration,
    val keepAlive: String,
    val maxUploadBytes: Int,
    val maxNoteChars: Int,
) {
    val serverUrl: String
        get() = "http://${if (serverHost == "::1") "[$serverHost]" else serverHost}:$serverPort"

    fun ollamaEndpoint(path: String): URI {
        require(path.startsWith('/')) { "API path must start with '/'." }
        return URI.create("$ollamaBaseUrl$path")
    }

    companion object {
        fun load(): AppConfig = fromValues(loadEnvFile(Path(".env")) + System.getenv())

        fun fromValues(values: Map<String, String>): AppConfig {
            val serverHost = values.optionalValue("APP_HOST") ?: "127.0.0.1"
            validateLoopbackHost(serverHost, "APP_HOST")

            val serverPort = values.intValue("APP_PORT", default = 8787, range = 1024..65535)
            val ollamaBaseUrl = values.optionalValue("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
            validateLoopbackBaseUrl(ollamaBaseUrl)

            val model = values.optionalValue("OLLAMA_MODEL") ?: "qwen3:14b"
            val timeoutSeconds = values.longValue(
                "OLLAMA_REQUEST_TIMEOUT_SECONDS",
                default = 300L,
                range = 5L..900L,
            )
            val keepAlive = values.optionalValue("OLLAMA_KEEP_ALIVE") ?: "5m"
            val maxUploadBytes = values.intValue(
                "NOTES_MAX_UPLOAD_BYTES",
                default = 1_048_576,
                range = 1_024..10_485_760,
            )
            val maxNoteChars = values.intValue(
                "NOTES_MAX_CHARS",
                default = 24_000,
                range = 1..200_000,
            )

            return AppConfig(
                serverHost = serverHost.lowercase(),
                serverPort = serverPort,
                ollamaBaseUrl = ollamaBaseUrl.trim().trimEnd('/'),
                model = model,
                requestTimeout = Duration.ofSeconds(timeoutSeconds),
                keepAlive = keepAlive,
                maxUploadBytes = maxUploadBytes,
                maxNoteChars = maxNoteChars,
            )
        }

        private fun Map<String, String>.optionalValue(name: String): String? =
            this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun Map<String, String>.intValue(name: String, default: Int, range: IntRange): Int {
            val raw = optionalValue(name) ?: return default
            val value = raw.toIntOrNull()
                ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (value !in range) {
                throw ConfigurationException("$name must be between ${range.first} and ${range.last}, got $value.")
            }
            return value
        }

        private fun Map<String, String>.longValue(name: String, default: Long, range: LongRange): Long {
            val raw = optionalValue(name) ?: return default
            val value = raw.toLongOrNull()
                ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (value !in range) {
                throw ConfigurationException("$name must be between ${range.first} and ${range.last}, got $value.")
            }
            return value
        }

        private fun validateLoopbackHost(value: String, name: String) {
            if (value.trim().lowercase() !in setOf("localhost", "127.0.0.1", "::1")) {
                throw ConfigurationException("$name must be localhost, 127.0.0.1, or ::1.")
            }
        }

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
