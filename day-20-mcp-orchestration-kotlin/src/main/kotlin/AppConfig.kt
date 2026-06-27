import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

data class McpEndpointConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
) {
    val url: String = "http://$host:$port/mcp"
}

data class AppConfig(
    val serverHost: String,
    val sourcePort: Int,
    val windowPort: Int,
    val briefPort: Int,
    val storagePort: Int,
    val clientName: String,
    val timeoutSeconds: Long,
    val telegramBackend: String,
    val telegramChat: String,
    val telegramLimit: Int,
    val telegramApiId: Int?,
    val telegramApiHash: String?,
    val telegramPhone: String?,
    val telegramCode: String?,
    val telegramPassword: String?,
    val telegramResendCode: Boolean,
    val telegramQrWaitSeconds: Long,
    val tdlibLibraryPath: String?,
    val tdlibSessionDir: Path,
    val tdlibFilesDir: Path,
    val courseDay: String,
    val stateDir: Path,
    val orchestrationRequest: String,
    val orchestrationMaxSteps: Int,
    val chunkMessagesPerChunk: Int,
    val llmApiKey: String?,
    val llmAuthScheme: String,
    val llmApiUrl: String,
    val llmModel: String,
) {
    val endpoints: List<McpEndpointConfig> = listOf(
        McpEndpointConfig("source", "source-mcp", serverHost, sourcePort),
        McpEndpointConfig("window", "window-mcp", serverHost, windowPort),
        McpEndpointConfig("brief", "brief-mcp", serverHost, briefPort),
        McpEndpointConfig("storage", "storage-mcp", serverHost, storagePort),
    )

    fun endpoint(id: String): McpEndpointConfig =
        endpoints.firstOrNull { it.id == id } ?: error("Unknown MCP server id: $id")

    companion object {
        fun load(): AppConfig {
            val env = loadEnvFile(Path(".env")) + System.getenv()
            return AppConfig(
                serverHost = env["MCP_SERVER_HOST"] ?: "127.0.0.1",
                sourcePort = env["MCP_SOURCE_PORT"]?.toIntOrNull() ?: 3020,
                windowPort = env["MCP_WINDOW_PORT"]?.toIntOrNull() ?: 3021,
                briefPort = env["MCP_BRIEF_PORT"]?.toIntOrNull() ?: 3022,
                storagePort = env["MCP_STORAGE_PORT"]?.toIntOrNull() ?: 3023,
                clientName = env["MCP_CLIENT_NAME"] ?: "ai-course-day-20-orchestration-agent",
                timeoutSeconds = env["MCP_TIMEOUT_SECONDS"]?.toLongOrNull()?.coerceAtLeast(1) ?: 120L,
                telegramBackend = env["TELEGRAM_BACKEND"] ?: "fixture",
                telegramChat = env["TELEGRAM_CHAT"] ?: "fixture-course-chat",
                telegramLimit = env["TELEGRAM_LIMIT"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100,
                telegramApiId = env["TELEGRAM_API_ID"]?.toIntOrNull(),
                telegramApiHash = env["TELEGRAM_API_HASH"]?.secretValueOrNull(),
                telegramPhone = env["TELEGRAM_PHONE"]?.secretValueOrNull(),
                telegramCode = env["TELEGRAM_CODE"]?.blankToNull(),
                telegramPassword = env["TELEGRAM_PASSWORD"]?.blankToNull(),
                telegramResendCode = env["TELEGRAM_RESEND_CODE"]?.toBooleanStrictOrNull() ?: false,
                telegramQrWaitSeconds = env["TELEGRAM_QR_WAIT_SECONDS"]?.toLongOrNull()?.coerceAtLeast(1) ?: 180L,
                tdlibLibraryPath = env["TDLIB_LIBRARY_PATH"]?.secretValueOrNull(),
                tdlibSessionDir = Path(env["TDLIB_SESSION_DIR"] ?: "telegram-session"),
                tdlibFilesDir = Path(env["TDLIB_FILES_DIR"] ?: "telegram-files"),
                courseDay = env["COURSE_DAY"]?.blankToNull() ?: "auto",
                stateDir = Path(env["STATE_DIR"] ?: "state"),
                orchestrationRequest = env["ORCHESTRATION_REQUEST"]?.blankToNull()
                    ?: "Найди задание дня курса в Telegram, выдели дискуссию, подготовь execution brief и prompt, затем сохрани результат.",
                orchestrationMaxSteps = env["ORCHESTRATION_MAX_STEPS"]?.toIntOrNull()?.coerceIn(7, 12) ?: 7,
                chunkMessagesPerChunk = env["ORCHESTRATION_CHUNK_MESSAGES"]?.toIntOrNull()?.coerceIn(2, 20) ?: 3,
                llmApiKey = env["LLM_API_KEY"]?.secretValueOrNull(),
                llmAuthScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
                llmApiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
                llmModel = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
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

private fun String.blankToNull(): String? = trim().ifBlank { null }

private fun String.secretValueOrNull(): String? =
    blankToNull()?.takeUnless {
        it.startsWith("replace-with", ignoreCase = true) ||
            it.startsWith("/absolute/path", ignoreCase = true)
    }
