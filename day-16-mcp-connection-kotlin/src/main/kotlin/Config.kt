import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val serverUrl: String,
    val clientName: String,
    val timeoutSeconds: Long,
) {
    companion object {
        fun load(): AppConfig {
            val env = loadEnvFile(Path(".env")) + System.getenv()
            return AppConfig(
                serverUrl = env["MCP_SERVER_URL"] ?: "https://mcp.deepwiki.com/mcp",
                clientName = env["MCP_CLIENT_NAME"] ?: "ai-course-day-16-mcp-client",
                timeoutSeconds = env["MCP_TIMEOUT_SECONDS"]?.toLongOrNull()?.coerceAtLeast(1) ?: 30,
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
