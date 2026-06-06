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

private const val DEFAULT_TASK =
    "Есть 12 монет, одна из них фальшивая и легче остальных. Как найти фальшивую монету за 3 взвешивания на чашечных весах?"

fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()

    val apiKey = requiredEnv(env, "LLM_API_KEY")
    val apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/raw/openai/v1/chat/completions"
    val authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth"
    val model = env["LLM_MODEL"] ?: "gpt-5-mini"
    val task = args.joinToString(" ").ifBlank { DEFAULT_TASK }

    val client = HttpClient.newHttpClient()
    val config = LlmConfig(apiUrl, authScheme, apiKey, model)

    println("Model: $model")
    println("Task: $task")
    println()

    val direct = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(ChatMessage(role = "user", content = task)),
    )

    println("=== 1. DIRECT ===")
    println(direct)
    println()

    val stepByStep = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(
            ChatMessage(
                role = "user",
                content = """
                    $task

                    Решай пошагово.
                """.trimIndent(),
            ),
        ),
    )

    println("=== 2. STEP BY STEP ===")
    println(stepByStep)
    println()

    val generatedPrompt = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(
            ChatMessage(
                role = "user",
                content = """
                    Составь хороший промпт для решения этой логической задачи.
                    Промпт должен быть адресован самой модели и помогать найти правильное проверяемое решение.
                    Верни только сам промпт без пояснений.

                    Задача:
                    $task
                """.trimIndent(),
            ),
        ),
    )

    val promptFirstAnswer = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(ChatMessage(role = "user", content = generatedPrompt)),
    )

    println("=== 3. PROMPT FIRST ===")
    println("Generated prompt:")
    println(generatedPrompt)
    println()
    println("Answer:")
    println(promptFirstAnswer)
    println()

    val experts = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(
            ChatMessage(
                role = "user",
                content = """
                    Реши задачу с помощью группы экспертов. Каждый эксперт должен дать свое решение.

                    Задача:
                    $task

                    Эксперты:
                    1. Аналитик: дай решение через анализ вариантов.
                    2. Инженер: дай решение как конкретный алгоритм взвешиваний.
                    3. Критик: дай решение и проверь слабые места других подходов.

                    В конце дай общее итоговое решение.
                """.trimIndent(),
            ),
        ),
    )

    println("=== 4. EXPERTS ===")
    println(experts)
    println()

    val comparison = sendChatCompletion(
        client = client,
        config = config,
        messages = listOf(
            ChatMessage(
                role = "system",
                content = "Ты сравниваешь ответы на логическую задачу кратко и проверяемо.",
            ),
            ChatMessage(
                role = "user",
                content = """
                    Есть правильный критерий: нужно найти легкую фальшивую монету среди 12 монет за 3 взвешивания на чашечных весах.
                    Эталонная идея проверки:
                    1. Взвесить 1-4 против 5-8.
                    2. Если равны, фальшивая среди 9-12; если не равны, фальшивая среди четырех монет на легкой чаше.
                    3. Для найденной четверки кандидатов достаточно сравнить первые две монеты, а при равенстве сравнить одну из оставшихся двух с известной настоящей монетой или друг с другом.
                    4. Ветки, где монета уже однозначно определена, не должны делать выводы из невозможных дополнительных исходов.

                    Сравни четыре ответа и скажи:
                    1. Какие ответы выглядят корректными.
                    2. Какой подход дал наиболее точный и проверяемый алгоритм.
                    3. Есть ли в ответах ошибки, лишние или невозможные ветки.
                    4. Почему выбранный подход лучше.

                    DIRECT:
                    $direct

                    STEP BY STEP:
                    $stepByStep

                    PROMPT FIRST:
                    $promptFirstAnswer

                    EXPERTS:
                    $experts

                    Ответь кратко, списком из 3-5 пунктов.
                """.trimIndent(),
            ),
        ),
    )

    println("=== COMPARISON (NOT A SOLUTION MODE) ===")
    println(comparison)
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
        .timeout(Duration.ofSeconds(180))
        .header("Authorization", "${config.authScheme} ${config.apiKey}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

    val response = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (error: SSLHandshakeException) {
        System.err.println("Не удалось проверить SSL-сертификат API.")
        System.err.println("Для Eliza запусти: day-03-reasoning-methods-kotlin/scripts/setup-yandex-ca.sh")
        System.err.println("Потом повтори запрос через: day-03-reasoning-methods-kotlin/scripts/run-eliza.sh")
        exitProcess(1)
    } catch (error: HttpTimeoutException) {
        return "Request timed out. Попробуй повторить запуск или сделать prompt короче."
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
