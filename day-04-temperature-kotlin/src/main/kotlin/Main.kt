import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
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
    "Придумай 5 идей для мобильного приложения, которое помогает студентам учиться эффективнее. Для каждой идеи дай название, очень короткое описание в 1 предложении и одну ключевую функцию."

private val temperatures = listOf(0.0, 0.7, 1.2)

fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()

    val apiKey = requiredEnv(env, "LLM_API_KEY")
    val apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions"
    val authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth"
    val model = env["LLM_MODEL"] ?: "deepseek/deepseek-v3.1-terminus"
    val prompt = args.joinToString(" ").ifBlank { DEFAULT_PROMPT }

    val client = HttpClient.newHttpClient()
    val config = LlmConfig(apiUrl, authScheme, apiKey, model)
    val answers = mutableListOf<TemperatureAnswer>()

    println("Model: $model")
    println("API URL: $apiUrl")
    println("Prompt: $prompt")
    println()

    temperatures.forEach { temperature ->
        val requestBody = buildRequestBody(
            model = model,
            prompt = prompt,
            temperature = temperature,
        )

        println("=== REST REQUEST BODY, TEMPERATURE ${formatTemperature(temperature)} ===")
        println(json.encodeToString(json.parseToJsonElement(requestBody)))
        println()

        val answer = sendChatCompletion(
            client = client,
            config = config,
            requestBody = requestBody,
        )

        answers += TemperatureAnswer(temperature = temperature, answer = answer)

        println("=== TEMPERATURE ${formatTemperature(temperature)} ===")
        println(answer)
        println()
    }

    println("=== COMPARISON ===")
    printComparison(prompt = prompt, answers = answers)
}

private fun buildRequestBody(
    model: String,
    prompt: String,
    temperature: Double,
): String {
    return buildJsonObject {
        put("model", model)
        put("temperature", temperature)
        put("max_tokens", 800)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        })
    }.toString()
}

private fun sendChatCompletion(
    client: HttpClient,
    config: LlmConfig,
    requestBody: String,
): String {
    val request = HttpRequest.newBuilder()
        .uri(URI.create(config.apiUrl))
        .timeout(Duration.ofSeconds(180))
        .header("Authorization", "${config.authScheme} ${config.apiKey}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

    val response = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (error: SSLHandshakeException) {
        System.err.println("Не удалось проверить SSL-сертификат API.")
        System.err.println("Для Eliza запусти: day-04-temperature-kotlin/scripts/setup-yandex-ca.sh")
        System.err.println("Потом повтори запрос через: day-04-temperature-kotlin/scripts/run-eliza.sh")
        exitProcess(1)
    } catch (error: HttpTimeoutException) {
        return "Request timed out. Попробуй повторить запуск."
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

private fun printComparison(
    prompt: String,
    answers: List<TemperatureAnswer>,
) {
    println("Prompt:")
    println(prompt)
    println()

    println("Observed metrics:")
    answers.forEach { answer ->
        val lineCount = answer.answer.lineSequence().count { it.isNotBlank() }
        println("- temperature ${formatTemperature(answer.temperature)}: ${answer.answer.length} chars, $lineCount non-empty lines")
    }
    println()

    println("How to read the difference:")
    println("- `0` is usually best when accuracy and reproducibility matter most.")
    println("- `0.7` is usually the best balance between accuracy and creativity.")
    println("- `1.2` is usually best for brainstorming, alternative ideas, and more varied wording.")
    println()

    println("Short conclusion:")
    println("Inspect the three answers above: higher temperature should usually add more variation, while lower temperature should stay closer to the prompt.")
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

private fun formatTemperature(temperature: Double): String {
    return if (temperature % 1.0 == 0.0) temperature.toInt().toString() else temperature.toString()
}

private data class LlmConfig(
    val apiUrl: String,
    val authScheme: String,
    val apiKey: String,
    val model: String,
)

private data class TemperatureAnswer(
    val temperature: Double,
    val answer: String,
)
