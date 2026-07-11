import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val serverHost: String,
    val serverPort: Int,
    val apiToken: String?,
    val allowInsecureNoAuth: Boolean,
    val ollamaBaseUrl: String,
    val model: String,
    val requestTimeout: Duration,
    val keepAlive: String,
    val contextLength: Int,
    val maxOutputTokens: Int,
    val knowledgeFile: Path,
    val sourcesFile: Path,
    val productCatalogFile: Path,
    val maxKnowledgeCards: Int,
    val ocrCommand: String,
    val ocrLanguages: String,
    val ocrTimeout: Duration,
    val maxPhotoBytes: Int,
    val maxImagePixels: Long,
    val maxInciChars: Int,
    val maxChatChars: Int,
    val maxChatMessages: Int,
    val maxContextTokens: Int,
    val maxSessions: Int,
    val sessionTtl: Duration,
    val rateLimitRequests: Int,
    val rateLimitWindow: Duration,
    val maxConcurrentInference: Int,
    val inferenceQueueCapacity: Int,
) {
    val serverUrl: String
        get() = "http://${if (serverHost == "::1") "[$serverHost]" else serverHost}:$serverPort"

    fun ollamaEndpoint(path: String): URI {
        require(path.startsWith('/')) { "Ollama API path must start with '/'." }
        return URI.create("$ollamaBaseUrl$path")
    }

    companion object {
        fun load(requireAuth: Boolean = true): AppConfig = fromValues(
            loadEnvFile(Path(".env")) + System.getenv(),
            requireAuth = requireAuth,
        )

        fun fromValues(values: Map<String, String>, requireAuth: Boolean = true): AppConfig {
            val serverHost = (values.optional("APP_HOST") ?: "127.0.0.1").lowercase()
            validateLoopbackHost(serverHost)
            val allowInsecureNoAuth = values.boolean("APP_ALLOW_INSECURE_NO_AUTH", false)
            val apiToken = values.optional("APP_API_TOKEN")
            if (requireAuth && !allowInsecureNoAuth) validateApiToken(apiToken)

            val ollamaBaseUrl = (values.optional("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434")
                .trim().trimEnd('/')
            validateLoopbackBaseUrl(ollamaBaseUrl)

            val contextLength = values.int("OLLAMA_CONTEXT_LENGTH", 8192, 512..32768)
            val maxOutputTokens = values.int("OLLAMA_MAX_OUTPUT_TOKENS", 192, 64..2048)
            val maxContextTokens = values.int("MAX_CONTEXT_TOKENS", 8192, 512..32768)
            val effectiveContext = minOf(contextLength, maxContextTokens)
            if (maxOutputTokens + 128 >= effectiveContext) {
                throw ConfigurationException(
                    "OLLAMA_MAX_OUTPUT_TOKENS plus a 128-token safety reserve must be smaller than " +
                        "both OLLAMA_CONTEXT_LENGTH and MAX_CONTEXT_TOKENS.",
                )
            }

            return AppConfig(
                serverHost = serverHost,
                serverPort = values.int("APP_PORT", 8787, 1024..65535),
                apiToken = apiToken,
                allowInsecureNoAuth = allowInsecureNoAuth,
                ollamaBaseUrl = ollamaBaseUrl,
                model = values.optional("OLLAMA_MODEL") ?: "qwen3:4b",
                requestTimeout = Duration.ofSeconds(values.long("OLLAMA_REQUEST_TIMEOUT_SECONDS", 300, 5L..900L)),
                keepAlive = values.optional("OLLAMA_KEEP_ALIVE") ?: "30m",
                contextLength = contextLength,
                maxOutputTokens = maxOutputTokens,
                knowledgeFile = Path(values.optional("KNOWLEDGE_FILE") ?: "knowledge/ingredient-cards.json").normalize(),
                sourcesFile = Path(values.optional("SOURCES_FILE") ?: "knowledge/sources.json").normalize(),
                productCatalogFile = Path(values.optional("PRODUCT_CATALOG_FILE") ?: "catalog/products.json").normalize(),
                maxKnowledgeCards = values.int("MAX_KNOWLEDGE_CARDS", 12, 1..40),
                ocrCommand = values.optional("OCR_COMMAND") ?: "tesseract",
                ocrLanguages = values.optional("OCR_LANGUAGES") ?: "eng+rus",
                ocrTimeout = Duration.ofSeconds(values.long("OCR_TIMEOUT_SECONDS", 60, 5L..300L)),
                maxPhotoBytes = values.int("MAX_PHOTO_BYTES", 5_242_880, 65_536..20_971_520),
                maxImagePixels = values.long("MAX_IMAGE_PIXELS", 20_000_000, 1_000_000L..100_000_000L),
                maxInciChars = values.int("MAX_INCI_CHARS", 12_000, 100..100_000),
                maxChatChars = values.int("MAX_CHAT_CHARS", 2_000, 20..20_000),
                maxChatMessages = values.int("MAX_CHAT_MESSAGES", 8, 2..40),
                maxContextTokens = maxContextTokens,
                maxSessions = values.int("MAX_SESSIONS", 100, 1..10_000),
                sessionTtl = Duration.ofMinutes(values.long("SESSION_TTL_MINUTES", 30, 1L..1440L)),
                rateLimitRequests = values.int("RATE_LIMIT_REQUESTS", 12, 1..10_000),
                rateLimitWindow = Duration.ofSeconds(values.long("RATE_LIMIT_WINDOW_SECONDS", 60, 1L..3600L)),
                maxConcurrentInference = values.int("MAX_CONCURRENT_INFERENCE", 1, 1..8),
                inferenceQueueCapacity = values.int("INFERENCE_QUEUE_CAPACITY", 2, 0..100),
            )
        }

        private fun validateApiToken(token: String?) {
            val normalized = token?.trim().orEmpty()
            if (normalized.length < 24 || normalized.lowercase().let { "replace" in it || "change-me" in it }) {
                throw ConfigurationException(
                    "APP_API_TOKEN must be a unique secret with at least 24 characters, or enable " +
                        "APP_ALLOW_INSECURE_NO_AUTH=true for loopback-only local development.",
                )
            }
        }

        private fun validateLoopbackHost(value: String) {
            if (value !in setOf("localhost", "127.0.0.1", "::1")) {
                throw ConfigurationException("APP_HOST must be localhost, 127.0.0.1, or ::1.")
            }
        }

        private fun validateLoopbackBaseUrl(value: String) {
            val uri = try {
                URI.create(value)
            } catch (_: IllegalArgumentException) {
                throw ConfigurationException("OLLAMA_BASE_URL is not a valid URL: '$value'.")
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            if (
                uri.scheme?.lowercase() != "http" ||
                host !in setOf("localhost", "127.0.0.1", "::1") ||
                uri.userInfo != null || uri.query != null || uri.fragment != null ||
                (uri.path != null && uri.path !in setOf("", "/"))
            ) {
                throw ConfigurationException(
                    "OLLAMA_BASE_URL must be a plain loopback HTTP URL such as http://127.0.0.1:11434.",
                )
            }
        }

        private fun Map<String, String>.optional(name: String): String? = this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun Map<String, String>.boolean(name: String, default: Boolean): Boolean {
            val raw = optional(name) ?: return default
            return when (raw.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw ConfigurationException("$name must be true or false, got '$raw'.")
            }
        }

        private fun Map<String, String>.int(name: String, default: Int, range: IntRange): Int {
            val raw = optional(name) ?: return default
            val value = raw.toIntOrNull() ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (value !in range) throw ConfigurationException("$name must be between ${range.first} and ${range.last}.")
            return value
        }

        private fun Map<String, String>.long(name: String, default: Long, range: LongRange): Long {
            val raw = optional(name) ?: return default
            val value = raw.toLongOrNull() ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (value !in range) throw ConfigurationException("$name must be between ${range.first} and ${range.last}.")
            return value
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path).mapNotNull { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#") || '=' !in line) null else {
                    val key = line.substringBefore('=').trim().removePrefix("export ").trim()
                    val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                    key.takeIf { it.isNotBlank() }?.let { it to value }
                }
            }.toMap()
        }
    }
}
