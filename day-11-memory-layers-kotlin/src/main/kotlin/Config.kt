import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

data class AppConfig(
    val apiUrl: String,
    val authScheme: String,
    val apiKey: String,
    val model: String,
    val memoryRoot: Path,
    val shortTermMessagesLimit: Int,
    val debug: Boolean,
    val inputPricePerMillionTokens: Double?,
    val outputPricePerMillionTokens: Double?,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): AppConfig {
            val apiKey = env["LLM_API_KEY"]?.takeIf { it.isNotBlank() }
                ?: requiredEnv(env, "LLM_API_KEY")

            return AppConfig(
                apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
                authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
                apiKey = apiKey,
                model = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
                memoryRoot = Path.of(env["MEMORY_ROOT"] ?: "memory"),
                shortTermMessagesLimit = env["SHORT_TERM_MESSAGES_LIMIT"]?.toIntOrNull() ?: 8,
                debug = env["AGENT_DEBUG"]?.toBooleanStrictOrNull() ?: false,
                inputPricePerMillionTokens = env["MODEL_INPUT_PRICE_PER_1M_TOKENS"]?.toDoubleOrNull(),
                outputPricePerMillionTokens = env["MODEL_OUTPUT_PRICE_PER_1M_TOKENS"]?.toDoubleOrNull(),
            )
        }
    }
}

fun resolveEnvPath(): Path {
    val explicitPath = System.getenv("LLM_ENV_FILE")?.takeIf { it.isNotBlank() }
    if (explicitPath != null) return Path.of(explicitPath)
    return Path.of(".env")
}

fun loadEnvFile(path: Path): Map<String, String> {
    if (!Files.exists(path)) return emptyMap()

    return Files.readAllLines(path)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separatorIndex = line.indexOf("=")
            if (separatorIndex == -1) return@mapNotNull null

            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim().trimMatchingQuotes()
            key to value
        }
        .toMap()
}

private fun String.trimMatchingQuotes(): String {
    return if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
        substring(1, lastIndex)
    } else {
        this
    }
}

private fun requiredEnv(env: Map<String, String>, name: String): String {
    val value = env[name]?.takeIf { it.isNotBlank() }
    if (value != null) return value

    System.err.println("Не найдена переменная $name.")
    System.err.println("Создай .env по примеру .env.example или экспортируй переменную окружения.")
    exitProcess(1)
}
