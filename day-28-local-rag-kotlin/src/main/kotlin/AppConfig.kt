import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val week6IndexFile: Path,
    val ollamaBaseUrl: String,
    val localModel: String,
    val embeddingModel: String,
    val ollamaRequestTimeout: Duration,
    val keepAlive: String,
    val retrievalTopK: Int,
    val ragMaxContextTokens: Int,
    val benchmarkQuestionsFile: Path,
    val benchmarkRuns: Int,
    val reportsDir: Path,
    val cloudApiKey: String?,
    val cloudAuthScheme: String,
    val cloudApiUrl: URI,
    val cloudModel: String,
    val cloudTemperature: Double?,
    val cloudRequestTimeout: Duration,
) {
    fun ollamaEndpoint(path: String): URI {
        require(path.startsWith('/')) { "Ollama API path must start with '/'." }
        return URI.create("$ollamaBaseUrl$path")
    }

    val cloudEndpointHost: String
        get() = cloudApiUrl.host ?: "unknown"

    val cloudConfigured: Boolean
        get() = cloudApiKey != null

    companion object {
        fun load(): AppConfig = fromValues(loadEnvFile(Path(".env")) + System.getenv())

        fun fromValues(values: Map<String, String>): AppConfig {
            val ollamaBaseUrl = values.optional("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
            validateLoopbackOllamaUrl(ollamaBaseUrl)

            val cloudApiUrl = values.optional("LLM_API_URL")
                ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions"
            val cloudUri = validateCloudUrl(cloudApiUrl)

            return AppConfig(
                week6IndexFile = Path(
                    values.optional("WEEK6_INDEX_FILE")
                        ?: "../day-21-document-indexing-kotlin/index/structured-index.json",
                ).normalize(),
                ollamaBaseUrl = ollamaBaseUrl.trim().trimEnd('/'),
                localModel = values.optional("OLLAMA_MODEL") ?: "qwen3:14b",
                embeddingModel = values.optional("OLLAMA_EMBED_MODEL") ?: "nomic-embed-text",
                ollamaRequestTimeout = Duration.ofSeconds(
                    values.long("OLLAMA_REQUEST_TIMEOUT_SECONDS", default = 300, range = 5L..900L),
                ),
                keepAlive = values.optional("OLLAMA_KEEP_ALIVE") ?: "5m",
                retrievalTopK = values.int("RETRIEVAL_TOP_K", default = 4, range = 1..12),
                ragMaxContextTokens = values.int("RAG_MAX_CONTEXT_TOKENS", default = 2200, range = 300..8000),
                benchmarkQuestionsFile = Path(
                    values.optional("BENCHMARK_QUESTIONS_FILE") ?: "eval/benchmark-questions.json",
                ).normalize(),
                benchmarkRuns = values.int("BENCHMARK_RUNS", default = 3, range = 1..10),
                reportsDir = Path(values.optional("REPORTS_DIR") ?: "reports").normalize(),
                cloudApiKey = values.optional("LLM_API_KEY")?.takeUnless {
                    it.startsWith("replace-with", ignoreCase = true)
                },
                cloudAuthScheme = values.optional("LLM_AUTH_SCHEME") ?: "OAuth",
                cloudApiUrl = cloudUri,
                cloudModel = values.optional("LLM_MODEL") ?: "meta-llama/llama-3.3-70b-instruct",
                cloudTemperature = values.optionalDouble("CLOUD_TEMPERATURE", range = 0.0..2.0),
                cloudRequestTimeout = Duration.ofSeconds(
                    values.long("CLOUD_REQUEST_TIMEOUT_SECONDS", default = 300, range = 5L..900L),
                ),
            )
        }

        private fun Map<String, String>.optional(name: String): String? =
            this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun Map<String, String>.int(name: String, default: Int, range: IntRange): Int {
            val raw = optional(name) ?: return default
            val value = raw.toIntOrNull()
                ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (value !in range) {
                throw ConfigurationException("$name must be between ${range.first} and ${range.last}, got $value.")
            }
            return value
        }

        private fun Map<String, String>.long(name: String, default: Long, range: LongRange): Long {
            val raw = optional(name) ?: return default
            val value = raw.toLongOrNull()
                ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (value !in range) {
                throw ConfigurationException("$name must be between ${range.first} and ${range.last}, got $value.")
            }
            return value
        }

        private fun Map<String, String>.optionalDouble(name: String, range: ClosedFloatingPointRange<Double>): Double? {
            val raw = optional(name) ?: return null
            val value = raw.toDoubleOrNull()
                ?: throw ConfigurationException("$name must be a number, got '$raw'.")
            if (value !in range) {
                throw ConfigurationException("$name must be between ${range.start} and ${range.endInclusive}, got $value.")
            }
            return value
        }

        private fun validateLoopbackOllamaUrl(value: String) {
            val uri = try {
                URI.create(value.trim())
            } catch (error: IllegalArgumentException) {
                throw ConfigurationException("OLLAMA_BASE_URL is not a valid URL: '$value'.")
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            val loopback = host in setOf("localhost", "127.0.0.1", "::1")
            if (
                uri.scheme?.lowercase() != "http" ||
                !loopback ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null ||
                (uri.path != null && uri.path !in setOf("", "/"))
            ) {
                throw ConfigurationException(
                    "OLLAMA_BASE_URL must be a plain loopback HTTP URL, for example http://127.0.0.1:11434.",
                )
            }
        }

        private fun validateCloudUrl(value: String): URI {
            val uri = try {
                URI.create(value.trim())
            } catch (error: IllegalArgumentException) {
                throw ConfigurationException("LLM_API_URL is not a valid URL.")
            }
            if (
                uri.scheme?.lowercase() != "https" ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null
            ) {
                throw ConfigurationException("LLM_API_URL must be a plain HTTPS endpoint before course chunks can be sent to cloud.")
            }
            return uri
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
