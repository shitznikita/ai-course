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

private const val DEFAULT_SYSTEM_PROMPT =
    "Ты StudyAgent — учебный помощник. Ты отвечаешь кратко, понятно и задаешь уточняющие вопросы, если запрос пользователя неполный. Используй историю текущей сессии, чтобы помнить факты, которые пользователь уже сообщил."

fun main() {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()
    val agent = Agent(
        config = AgentConfig(
            apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
            authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
            apiKey = requiredEnv(env, "LLM_API_KEY"),
            model = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
        ),
        systemPrompt = env["AGENT_SYSTEM_PROMPT"] ?: DEFAULT_SYSTEM_PROMPT,
        debug = env["AGENT_DEBUG"]?.toBooleanStrictOrNull() ?: false,
    )

    println("StudyAgent CLI")
    println("Model: ${agent.model}")
    println("Type `exit`, `quit`, or `/exit` to stop.")
    println("Type `/debug` to toggle request body output.")
    println()

    while (true) {
        print("User: ")
        System.out.flush()
        val input = readLine()?.trim() ?: break
        if (input.isBlank()) continue

        when (input.lowercase()) {
            "exit", "quit", "/exit" -> {
                println("Agent: До встречи!")
                return
            }

            "/debug" -> {
                agent.debug = !agent.debug
                println("Agent: Debug mode is now ${if (agent.debug) "ON" else "OFF"}.")
                continue
            }
        }

        val answer = agent.ask(input)
        println("Agent: $answer")
        println()
    }
}

private class Agent(
    private val config: AgentConfig,
    private val systemPrompt: String,
    debug: Boolean,
) {
    private val client = HttpClient.newHttpClient()
    private val messages = mutableListOf(ChatMessage(role = "system", content = systemPrompt))

    var debug: Boolean = debug

    val model: String
        get() = config.model

    fun ask(userMessage: String): String {
        messages += ChatMessage(role = "user", content = userMessage)

        val requestBody = buildRequest()
        if (debug) {
            println()
            println("=== DEBUG REST REQUEST BODY WITHOUT API KEY ===")
            println(json.encodeToString(json.parseToJsonElement(requestBody)))
            println("=== END DEBUG ===")
            println()
        }

        val assistantReply = callLLM(requestBody)
        if (assistantReply.rememberInHistory) {
            messages += ChatMessage(role = "assistant", content = assistantReply.content)
        }
        return assistantReply.content
    }

    fun buildRequest(): String {
        return buildJsonObject {
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
    }

    fun callLLM(requestBody: String): AgentReply {
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
            return AgentReply(
                content = "Не удалось проверить SSL-сертификат API. Запусти day-06-first-agent-kotlin/scripts/setup-yandex-ca.sh и повтори запуск через run-eliza.sh.",
                rememberInHistory = false,
            )
        } catch (error: HttpTimeoutException) {
            return AgentReply(
                content = "Request timed out. Попробуй повторить сообщение.",
                rememberInHistory = false,
            )
        }

        if (response.statusCode() !in 200..299) {
            return AgentReply(
                content = "HTTP status: ${response.statusCode()}\n${response.body()}",
                rememberInHistory = false,
            )
        }

        val root = json.parseToJsonElement(response.body()).jsonObject
        val responseRoot = root["response"]?.jsonObject ?: root
        return AgentReply(
            content = extractAssistantText(responseRoot),
            rememberInHistory = true,
        )
    }

    private fun extractAssistantText(root: JsonObject): String {
        val choices = root["choices"] as? JsonArray
        val firstChoice = choices?.firstOrNull()?.jsonObject
        val message = firstChoice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content

        return content ?: "Не удалось найти choices[0].message.content в ответе:\n${json.encodeToString(root)}"
    }
}

private data class AgentConfig(
    val apiUrl: String,
    val authScheme: String,
    val apiKey: String,
    val model: String,
)

private data class AgentReply(
    val content: String,
    val rememberInHistory: Boolean,
)

private data class ChatMessage(
    val role: String,
    val content: String,
)

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
