import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.ZoneId
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val serverHost: String,
    val serverPort: Int,
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
    val scheduleTime: LocalTime,
    val scheduleZone: ZoneId,
    val scheduleIntervalSeconds: Long,
    val schedulerRuns: Int,
    val llmApiKey: String?,
    val llmAuthScheme: String,
    val llmApiUrl: String,
    val llmModel: String,
) {
    val serverUrl: String = "http://$serverHost:$serverPort/mcp"

    companion object {
        fun load(): AppConfig {
            val env = loadEnvFile(Path(".env")) + System.getenv()
            return AppConfig(
                serverHost = env["MCP_SERVER_HOST"] ?: "127.0.0.1",
                serverPort = env["MCP_SERVER_PORT"]?.toIntOrNull() ?: 3018,
                clientName = env["MCP_CLIENT_NAME"] ?: "ai-course-day-18-scheduler-agent",
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
                scheduleTime = LocalTime.parse(env["SCHEDULE_TIME"] ?: "13:00"),
                scheduleZone = ZoneId.of(env["SCHEDULE_ZONE"] ?: "Europe/Moscow"),
                scheduleIntervalSeconds = env["SCHEDULE_INTERVAL_SECONDS"]?.toLongOrNull()?.coerceAtLeast(1) ?: 30L,
                schedulerRuns = env["SCHEDULER_RUNS"]?.toIntOrNull()?.coerceAtLeast(1) ?: 2,
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
