import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val apiKey: String,
    val authScheme: String,
    val apiUrl: String,
    val model: String,
    val debug: Boolean,
    val stateFile: Path,
    val invariantsFile: Path,
    val checkerMode: String,
) {
    companion object {
        fun load(): AppConfig {
            val env = loadEnvFile(Path(".env")) + System.getenv()
            return AppConfig(
                apiKey = env["LLM_API_KEY"] ?: error("LLM_API_KEY is required in .env or environment"),
                authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
                apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
                model = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
                debug = env["AGENT_DEBUG"]?.toBooleanStrictOrNull() ?: false,
                stateFile = Path(env["TASK_STATE_FILE"] ?: "state/task_state.json"),
                invariantsFile = Path(env["INVARIANTS_FILE"] ?: "memory/invariants.json"),
                checkerMode = env["CHECKER_MODE"] ?: "combined",
            )
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }
                .associate {
                    val key = it.substringBefore("=").trim()
                    val value = it.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                    key to value
                }
        }
    }
}
