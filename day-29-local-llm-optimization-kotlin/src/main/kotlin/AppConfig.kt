import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class OptimizationProfile(
    val id: String,
    val label: String,
    val model: String,
    val temperature: Double,
    val numPredict: Int,
    val numCtx: Int,
    val optimizedPrompt: Boolean,
)

data class AppConfig(
    val week6IndexFile: Path,
    val ollamaBaseUrl: String,
    val q4Model: String,
    val q8Model: String,
    val embeddingModel: String,
    val ollamaRequestTimeout: Duration,
    val keepAlive: String,
    val retrievalTopK: Int,
    val ragMaxContextTokens: Int,
    val benchmarkQuestionsFile: Path,
    val benchmarkRuns: Int,
    val reportsDir: Path,
) {
    val profiles: List<OptimizationProfile> = listOf(
        OptimizationProfile("baseline-q4", "До: Q4 + defaults", q4Model, 0.6, 512, 32768, false),
        OptimizationProfile("optimized-q4", "После: Q4 optimized", q4Model, 0.0, 220, 8192, true),
        OptimizationProfile("optimized-q8", "Контроль: Q8 optimized", q8Model, 0.0, 220, 8192, true),
    )

    fun profile(id: String): OptimizationProfile = profiles.firstOrNull { it.id == id.lowercase() }
        ?: throw ConfigurationException("Unknown profile '$id'. Use: ${profiles.joinToString { it.id }}.")

    fun ollamaEndpoint(path: String): URI {
        require(path.startsWith('/')) { "Ollama API path must start with '/'." }
        return URI.create("$ollamaBaseUrl$path")
    }

    companion object {
        fun load(): AppConfig = fromValues(loadEnvFile(Path(".env")) + System.getenv())

        fun fromValues(values: Map<String, String>): AppConfig {
            val baseUrl = values.optional("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
            validateLoopbackOllamaUrl(baseUrl)
            return AppConfig(
                week6IndexFile = Path(values.optional("WEEK6_INDEX_FILE")
                    ?: "../day-21-document-indexing-kotlin/index/structured-index.json").normalize(),
                ollamaBaseUrl = baseUrl.trim().trimEnd('/'),
                q4Model = values.optional("OLLAMA_Q4_MODEL") ?: "qwen3:14b",
                q8Model = values.optional("OLLAMA_Q8_MODEL") ?: "qwen3:14b-q8_0",
                embeddingModel = values.optional("OLLAMA_EMBED_MODEL") ?: "nomic-embed-text",
                ollamaRequestTimeout = Duration.ofSeconds(values.long("OLLAMA_REQUEST_TIMEOUT_SECONDS", 300, 5L..900L)),
                keepAlive = values.optional("OLLAMA_KEEP_ALIVE") ?: "5m",
                retrievalTopK = values.int("RETRIEVAL_TOP_K", 4, 1..12),
                ragMaxContextTokens = values.int("RAG_MAX_CONTEXT_TOKENS", 2200, 300..8000),
                benchmarkQuestionsFile = Path(values.optional("BENCHMARK_QUESTIONS_FILE")
                    ?: "eval/benchmark-questions.json").normalize(),
                benchmarkRuns = values.int("BENCHMARK_RUNS", 3, 1..10),
                reportsDir = Path(values.optional("REPORTS_DIR") ?: "reports").normalize(),
            )
        }

        private fun Map<String, String>.optional(name: String): String? = this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun Map<String, String>.int(name: String, default: Int, range: IntRange): Int {
            val raw = optional(name) ?: return default
            val result = raw.toIntOrNull() ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (result !in range) throw ConfigurationException("$name must be between ${range.first} and ${range.last}, got $result.")
            return result
        }

        private fun Map<String, String>.long(name: String, default: Long, range: LongRange): Long {
            val raw = optional(name) ?: return default
            val result = raw.toLongOrNull() ?: throw ConfigurationException("$name must be an integer, got '$raw'.")
            if (result !in range) throw ConfigurationException("$name must be between ${range.first} and ${range.last}, got $result.")
            return result
        }

        private fun validateLoopbackOllamaUrl(value: String) {
            val uri = try { URI.create(value.trim()) } catch (_: IllegalArgumentException) {
                throw ConfigurationException("OLLAMA_BASE_URL is not a valid URL: '$value'.")
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            val loopback = host in setOf("localhost", "127.0.0.1", "::1")
            if (uri.scheme?.lowercase() != "http" || !loopback || uri.userInfo != null || uri.query != null ||
                uri.fragment != null || (uri.path != null && uri.path !in setOf("", "/"))) {
                throw ConfigurationException("OLLAMA_BASE_URL must be a plain loopback HTTP URL, for example http://127.0.0.1:11434.")
            }
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path).mapNotNull { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#") || "=" !in line) null else {
                    val key = line.substringBefore("=").trim().removePrefix("export ").trim()
                    val value = line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                    key.takeIf { it.isNotBlank() }?.let { it to value }
                }
            }.toMap()
        }
    }
}
