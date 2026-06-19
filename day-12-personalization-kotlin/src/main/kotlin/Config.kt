import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

data class AppConfig(
    val apiKey: String,
    val authScheme: String,
    val apiUrl: String,
    val model: String,
    val debug: Boolean,
    val memoryRoot: Path,
    val profilesRoot: Path,
    val shortTermMessagesLimit: Int,
    val prompt1kCostUsd: Double,
    val completion1kCostUsd: Double,
) {
    companion object {
        fun load(): AppConfig {
            val env = loadEnvFile(Path(".env")) + System.getenv()
            val apiKey = env["LLM_API_KEY"] ?: error("LLM_API_KEY is required in .env or environment")
            return AppConfig(
                apiKey = apiKey,
                authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
                apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
                model = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
                debug = env["AGENT_DEBUG"]?.toBooleanStrictOrNull() ?: false,
                memoryRoot = Path(env["MEMORY_ROOT"] ?: "memory"),
                profilesRoot = Path(env["PROFILES_ROOT"] ?: "profiles"),
                shortTermMessagesLimit = env["SHORT_TERM_MESSAGES_LIMIT"]?.toIntOrNull() ?: 8,
                prompt1kCostUsd = env["PROMPT_1K_COST_USD"]?.toDoubleOrNull() ?: 0.0,
                completion1kCostUsd = env["COMPLETION_1K_COST_USD"]?.toDoubleOrNull() ?: 0.0,
            )
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }
                .associate { line ->
                    val key = line.substringBefore("=").trim()
                    val rawValue = line.substringAfter("=").trim()
                    key to rawValue.removeSurrounding("\"").removeSurrounding("'")
                }
        }
    }
}
