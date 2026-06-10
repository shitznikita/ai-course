import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    "Ты StudyAgent — учебный помощник. Ты отвечаешь кратко, понятно и задаешь уточняющие вопросы, если запрос пользователя неполный. Используй сохраненную историю диалога, чтобы помнить факты, которые пользователь сообщил в прошлых сессиях."

fun main() {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()
    val historyPath = Path.of(env["AGENT_HISTORY_FILE"] ?: "agent-history.json")

    val agent = Agent(
        config = AgentConfig(
            apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
            authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
            apiKey = requiredEnv(env, "LLM_API_KEY"),
            model = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
        ),
        systemPrompt = env["AGENT_SYSTEM_PROMPT"] ?: DEFAULT_SYSTEM_PROMPT,
        historyPath = historyPath,
        debug = env["AGENT_DEBUG"]?.toBooleanStrictOrNull() ?: false,
    )

    println("StudyAgent CLI with persistent context")
    println("Model: ${agent.model}")
    println("History file: ${agent.historyPath.absolute()}")
    println("Loaded messages: ${agent.messageCount}")
    println("Type `exit`, `quit`, or `/exit` to stop.")
    println("Type `/debug` to toggle request body output.")
    println("Type `/clear` to delete saved history and start a new dialog.")
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

            "/clear" -> {
                agent.clearHistory()
                println("Agent: История очищена. Начинаем новый диалог.")
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
    val historyPath: Path,
    debug: Boolean,
) {
    private val client = HttpClient.newHttpClient()
    private val messages = loadHistory()

    var debug: Boolean = debug

    val model: String
        get() = config.model

    val messageCount: Int
        get() = messages.size

    fun ask(userMessage: String): String {
        messages += ChatMessage(role = "user", content = userMessage)
        saveHistory()

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
            saveHistory()
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
                content = "Не удалось проверить SSL-сертификат API. Запусти day-07-persistent-context-kotlin/scripts/setup-yandex-ca.sh и повтори запуск через run-eliza.sh.",
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

    fun clearHistory() {
        messages.clear()
        messages += ChatMessage(role = "system", content = systemPrompt)
        historyPath.deleteIfExists()
        saveHistory()
    }

    private fun loadHistory(): MutableList<ChatMessage> {
        if (!historyPath.exists()) {
            return mutableListOf(ChatMessage(role = "system", content = systemPrompt))
        }

        val restoredMessages = runCatching {
            json.decodeFromString<List<ChatMessage>>(Files.readString(historyPath))
        }.getOrElse { error ->
            System.err.println("Failed to read history file: ${error.message}")
            emptyList()
        }

        val normalizedMessages = when {
            restoredMessages.isEmpty() -> listOf(ChatMessage(role = "system", content = systemPrompt))
            restoredMessages.first().role != "system" -> listOf(ChatMessage(role = "system", content = systemPrompt)) + restoredMessages
            else -> restoredMessages
        }

        return normalizedMessages.toMutableList()
    }

    private fun saveHistory() {
        historyPath.parent?.createDirectories()
        Files.writeString(historyPath, json.encodeToString(messages))
        println("History saved: ${historyPath.absolute()} (${messages.size} messages)")
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

@Serializable
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
