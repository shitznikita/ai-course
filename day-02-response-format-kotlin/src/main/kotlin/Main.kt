import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

private const val DEFAULT_PROMPT =
    "Объясни начинающему разработчику, зачем управлять форматом ответа LLM через API."

fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()

    val apiKey = requiredEnv(env, "LLM_API_KEY")
    val apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/raw/openai/v1/chat/completions"
    val authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth"
    val model = env["LLM_MODEL"] ?: "gpt-5-mini"
    val prompt = args.joinToString(" ").ifBlank { DEFAULT_PROMPT }

    val client = HttpClient.newHttpClient()
    val config = LlmConfig(apiUrl, authScheme, apiKey, model)

    println("Model: $model")
    println("Prompt: $prompt")
    println()

    val withoutLimits = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(
            ChatMessage(role = "user", content = prompt),
        ),
    )

    println("=== WITHOUT LIMITS ===")
    println(withoutLimits)
    println()

    val controlledUserPrompt = """
        $prompt

        Верни ответ в строго заданном формате:
        {
          "summary": "одно короткое предложение",
          "rules": ["правило 1", "правило 2", "правило 3"],
          "finish": "END"
        }

        Ограничение длины: не больше 80 слов.
        Условие завершения: значение поля finish должно быть ровно "END".
        Не добавляй текст до или после JSON.
    """.trimIndent()

    val withLimits = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(
            ChatMessage(
                role = "system",
                content = "Ты отвечаешь кратко и строго соблюдаешь формат, который попросил пользователь.",
            ),
            ChatMessage(role = "user", content = controlledUserPrompt),
        ),
    )

    println("=== WITH LIMITS ===")
    println(withLimits)
}

private fun sendChatCompletion(
    client: HttpClient,
    config: LlmConfig,
    messages: List<ChatMessage>,
): String {
    val requestBody = buildJsonObject {
        put("model", config.model)
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                })
            }
        })
    }.toString()

    val request = HttpRequest.newBuilder()
        .uri(URI.create(config.apiUrl))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "${config.authScheme} ${config.apiKey}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

    val response = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (error: SSLHandshakeException) {
        System.err.println("Не удалось проверить SSL-сертификат API.")
        System.err.println("Для Eliza запусти: day-02-response-format-kotlin/scripts/setup-yandex-ca.sh")
        System.err.println("Потом повтори запрос через: day-02-response-format-kotlin/scripts/run-eliza.sh")
        exitProcess(1)
    }

    if (response.statusCode() !in 200..299) {
        return "HTTP status: ${response.statusCode()}\n${response.body()}"
    }

    val root = json.parseToJsonElement(response.body()).jsonObject
    return extractAssistantText(root)
}

private fun extractAssistantText(root: JsonObject): String {
    val responseRoot = root["response"]?.jsonObject ?: root
    val choices = responseRoot["choices"] as? JsonArray
    val firstChoice = choices?.firstOrNull()?.jsonObject
    val message = firstChoice?.get("message")?.jsonObject
    val content = message?.get("content")?.jsonPrimitive?.content

    return content ?: "Не удалось найти choices[0].message.content в ответе:\n${json.encodeToString(root)}"
}

private fun resolveEnvPath(): Path {
    val explicitPath = System.getenv("LLM_ENV_FILE")?.takeIf { it.isNotBlank() }
    if (explicitPath != null) return Path.of(explicitPath)
    return Path.of(".env")
}

private fun loadEnvFile(path: Path): Map<String, String> {
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

private data class LlmConfig(
    val apiUrl: String,
    val authScheme: String,
    val apiKey: String,
    val model: String,
)

private data class ChatMessage(
    val role: String,
    val content: String,
)
