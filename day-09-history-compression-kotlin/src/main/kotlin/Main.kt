import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.text.DecimalFormat
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.math.ceil
import kotlin.math.max
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

private val moneyFormat = DecimalFormat("0.000000")

private const val DEFAULT_SYSTEM_PROMPT =
    "Ты ContextCompressionAgent — учебный помощник. Отвечай кратко и используй доступный контекст: summary старой истории и последние сообщения."

fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()
    val mode = args.firstOrNull()?.lowercase() ?: env["DAY09_MODE"]?.lowercase() ?: "compare"
    val recentLimit = env["RECENT_MESSAGES_LIMIT"]?.toIntOrNull() ?: 10
    val apiKey = requiredEnv(env, "LLM_API_KEY")
    val modelConfig = ModelConfig.fromEnv(env)

    val agent = Agent(
        config = AgentConfig(
            apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
            authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
            apiKey = apiKey,
            model = env["LLM_MODEL"] ?: modelConfig.name,
        ),
        modelConfig = modelConfig,
        systemPrompt = env["AGENT_SYSTEM_PROMPT"] ?: DEFAULT_SYSTEM_PROMPT,
        recentMessagesPath = Path.of(env["RECENT_MESSAGES_FILE"] ?: "recent-messages.json"),
        summaryPath = Path.of(env["SUMMARY_FILE"] ?: "context-summary.md"),
        recentMessagesLimit = recentLimit,
        debug = env["AGENT_DEBUG"]?.toBooleanStrictOrNull() ?: false,
    )

    println("ContextCompressionAgent CLI")
    println("Mode: $mode")
    println("Model: ${agent.model}")
    println("Recent messages limit: ${agent.recentMessagesLimit}")
    println("Recent messages file: ${agent.recentMessagesPath.absolute()}")
    println("Summary file: ${agent.summaryPath.absolute()}")
    println("Tokenizer: approximate local estimator; API usage is printed when provider returns it.")
    println("Prices: ${modelConfig.priceLabel()}")
    println()

    when (mode) {
        "compare" -> runComparisonDemo(agent)
        "interactive" -> runInteractive(agent)
        "clear" -> {
            agent.clearSavedContext()
            println("Saved summary and recent messages were removed.")
        }
        else -> {
            println("Unknown mode: $mode")
            println("Available modes: compare, interactive, clear")
            exitProcess(1)
        }
    }
}

private fun runComparisonDemo(agent: Agent) {
    val demoHistory = buildDemoHistory()
    val finalQuestion = "Как меня зовут, что я сейчас изучаю и какие мои технические предпочтения и workflow ты помнишь?"

    println("=== DAY 9 COMPARISON DEMO ===")
    println("Demo history messages: ${demoHistory.size}")
    println("Final question: $finalQuestion")
    println()

    val summaryResult = agent.compressAndSave(demoHistory)
    println("=== SUMMARY CREATED BY LLM ===")
    println("Old messages summarized: ${summaryResult.oldMessagesCount}")
    println("Recent messages kept as is: ${summaryResult.recentMessagesCount}")
    println("Summary tokens: ${summaryResult.summaryTokens}")
    println("Summary request tokens: ${summaryResult.requestTokens}")
    println("Summary response tokens: ${summaryResult.responseTokens}")
    println("Summary cost: ${summaryResult.cost.label()}")
    println()
    println(compactForConsole(summaryResult.summary, maxChars = 1200))
    println()

    val comparison = agent.compareFullAndCompressed(demoHistory, finalQuestion, summaryResult.summary)
    printComparison(comparison, summaryResult)
}

private fun runInteractive(agent: Agent) {
    println("Interactive mode uses compressed context after the recent messages limit is exceeded.")
    println("Type `exit`, `quit`, or `/exit` to stop. Type `/summary` to print current summary. Type `/clear` to remove saved context.")
    println()

    while (true) {
        print("User: ")
        System.out.flush()
        val input = readLine()?.trim() ?: break
        if (input.isBlank()) continue

        when (input.lowercase()) {
            "exit", "quit", "/exit" -> return
            "/summary" -> {
                println(agent.currentSummary().ifBlank { "(summary is empty)" })
                continue
            }
            "/clear" -> {
                agent.clearSavedContext()
                println("Saved context cleared.")
                continue
            }
        }

        val result = agent.askCompressed(input)
        println("Agent: ${result.answer}")
        println("Tokens: current=${result.currentRequestTokens}, compressed_context=${result.promptTokens}, response=${result.responseTokens}")
        println("Cost: ${result.cost.label()}")
        result.compressionResult?.let {
            println("Compression: summarized ${it.oldMessagesCount} old messages, summary tokens=${it.summaryTokens}, cost=${it.cost.label()}")
        }
        println()
    }
}

private class Agent(
    private val config: AgentConfig,
    private val modelConfig: ModelConfig,
    private val systemPrompt: String,
    val recentMessagesPath: Path,
    val summaryPath: Path,
    val recentMessagesLimit: Int,
    var debug: Boolean,
) {
    private val client = HttpClient.newHttpClient()
    private val tokenCounter = ApproximateTokenCounter()
    private var recentMessages = loadRecentMessages()
    private var summary = loadSummary()

    val model: String
        get() = config.model

    fun compressAndSave(fullHistory: List<ChatMessage>): SummaryResult {
        val splitIndex = (fullHistory.size - recentMessagesLimit).coerceAtLeast(0)
        val oldMessages = fullHistory.take(splitIndex)
        val freshMessages = fullHistory.drop(splitIndex)

        val result = if (oldMessages.isEmpty()) {
            SummaryResult(
                summary = summary,
                oldMessagesCount = 0,
                recentMessagesCount = freshMessages.size,
                requestTokens = 0,
                responseTokens = 0,
                summaryTokens = tokenCounter.countText(summary),
                cost = CostEstimate.Known(0.0),
            )
        } else {
            createSummary(oldMessages, existingSummary = summary)
        }

        summary = result.summary
        recentMessages = freshMessages.toMutableList()
        saveSummary()
        saveRecentMessages()
        return result.copy(recentMessagesCount = freshMessages.size)
    }

    fun compareFullAndCompressed(
        fullHistory: List<ChatMessage>,
        finalQuestion: String,
        summaryText: String,
    ): ComparisonResult {
        val fullContext = buildFullContext(fullHistory, finalQuestion)
        val compressedContext = buildCompressedContext(summaryText, recentMessages, finalQuestion)

        val fullPromptTokens = tokenCounter.countMessages(fullContext)
        val compressedPromptTokens = tokenCounter.countMessages(compressedContext)
        val summaryTokens = tokenCounter.countText(summaryText)

        val fullReply = callLLM(fullContext)
        val compressedReply = callLLM(compressedContext)

        val fullResponseTokens = fullReply.usage?.completionTokens ?: tokenCounter.countText(fullReply.content)
        val compressedResponseTokens = compressedReply.usage?.completionTokens ?: tokenCounter.countText(compressedReply.content)
        val fullCost = fullReply.usage?.costUsd?.let { CostEstimate.Known(it) }
            ?: modelConfig.estimateCost(fullPromptTokens, fullResponseTokens)
        val compressedCost = compressedReply.usage?.costUsd?.let { CostEstimate.Known(it) }
            ?: modelConfig.estimateCost(compressedPromptTokens, compressedResponseTokens)

        return ComparisonResult(
            full = ModeResult(
                messagesSent = fullContext.size,
                promptTokens = fullPromptTokens,
                responseTokens = fullResponseTokens,
                answer = fullReply.content,
                apiUsage = fullReply.usage,
                cost = fullCost,
            ),
            compressed = ModeResult(
                messagesSent = compressedContext.size,
                promptTokens = compressedPromptTokens,
                responseTokens = compressedResponseTokens,
                answer = compressedReply.content,
                apiUsage = compressedReply.usage,
                cost = compressedCost,
            ),
            summaryTokens = summaryTokens,
            recentMessagesSent = recentMessages.size,
            localQuality = compareQuality(fullReply.content, compressedReply.content),
        )
    }

    fun askCompressed(userMessage: String): AskResult {
        val user = ChatMessage(role = "user", content = userMessage)
        val currentRequestTokens = tokenCounter.countMessage(user)
        recentMessages += user

        val compressionResult = if (recentMessages.size > recentMessagesLimit) {
            val splitIndex = recentMessages.size - recentMessagesLimit
            val oldMessages = recentMessages.take(splitIndex)
            val freshMessages = recentMessages.drop(splitIndex)
            val result = createSummary(oldMessages, existingSummary = summary)
            summary = result.summary
            recentMessages = freshMessages.toMutableList()
            result.copy(recentMessagesCount = freshMessages.size)
        } else {
            null
        }

        saveSummary()
        saveRecentMessages()

        val context = buildCompressedContext(summary, recentMessages.dropLast(1), userMessage)
        val promptTokens = tokenCounter.countMessages(context)
        val reply = callLLM(context)
        val responseTokens = reply.usage?.completionTokens ?: tokenCounter.countText(reply.content)
        val cost = reply.usage?.costUsd?.let { CostEstimate.Known(it) }
            ?: modelConfig.estimateCost(promptTokens, responseTokens)

        if (reply.rememberInHistory) {
            recentMessages += ChatMessage(role = "assistant", content = reply.content)
            saveRecentMessages()
        }

        return AskResult(
            answer = reply.content,
            currentRequestTokens = currentRequestTokens,
            promptTokens = promptTokens,
            responseTokens = responseTokens,
            cost = cost,
            compressionResult = compressionResult,
        )
    }

    fun currentSummary(): String = summary

    fun clearSavedContext() {
        recentMessages.clear()
        summary = ""
        recentMessagesPath.deleteIfExists()
        summaryPath.deleteIfExists()
    }

    private fun createSummary(oldMessages: List<ChatMessage>, existingSummary: String): SummaryResult {
        val oldDialog = oldMessages.joinToString(separator = "\n") { "${it.role}: ${it.content}" }
        val summaryPrompt = buildString {
            appendLine("Сожми старую часть диалога в компактное summary для будущего контекста агента.")
            appendLine("Сохрани факты, решения, предпочтения пользователя, незавершенные задачи и важные детали.")
            appendLine("Не добавляй ничего от себя.")
            appendLine()
            if (existingSummary.isNotBlank()) {
                appendLine("Уже существующее summary:")
                appendLine(existingSummary)
                appendLine()
                appendLine("Обнови его с учетом новой старой части диалога.")
                appendLine()
            }
            appendLine("Старая часть диалога:")
            appendLine(oldDialog)
        }

        val messages = listOf(
            ChatMessage(role = "system", content = "Ты делаешь только сжатие истории диалога. Не отвечай пользователю, верни только summary."),
            ChatMessage(role = "user", content = summaryPrompt),
        )
        val requestTokens = tokenCounter.countMessages(messages)
        val reply = callLLM(messages)
        val responseTokens = reply.usage?.completionTokens ?: tokenCounter.countText(reply.content)
        val cost = reply.usage?.costUsd?.let { CostEstimate.Known(it) }
            ?: modelConfig.estimateCost(requestTokens, responseTokens)

        return SummaryResult(
            summary = reply.content.trim(),
            oldMessagesCount = oldMessages.size,
            recentMessagesCount = 0,
            requestTokens = requestTokens,
            responseTokens = responseTokens,
            summaryTokens = tokenCounter.countText(reply.content),
            cost = cost,
        )
    }

    private fun buildFullContext(fullHistory: List<ChatMessage>, finalQuestion: String): List<ChatMessage> {
        return listOf(ChatMessage(role = "system", content = systemPrompt)) +
            fullHistory +
            ChatMessage(role = "user", content = finalQuestion)
    }

    private fun buildCompressedContext(
        summaryText: String,
        recentMessagesForContext: List<ChatMessage>,
        finalQuestion: String,
    ): List<ChatMessage> {
        val summaryMessage = if (summaryText.isBlank()) {
            emptyList()
        } else {
            listOf(
                ChatMessage(
                    role = "system",
                    content = "Summary of older dialog history. Use it as memory, but prefer exact recent messages when they conflict:\n$summaryText",
                ),
            )
        }

        return listOf(ChatMessage(role = "system", content = systemPrompt)) +
            summaryMessage +
            recentMessagesForContext +
            ChatMessage(role = "user", content = finalQuestion)
    }

    private fun callLLM(messages: List<ChatMessage>): AgentReply {
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

        if (debug) {
            println()
            println("=== DEBUG REST REQUEST BODY WITHOUT API KEY ===")
            println(json.encodeToString(json.parseToJsonElement(requestBody)))
            println("=== END DEBUG ===")
            println()
        }

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
                content = "SSL error. Run day-09-history-compression-kotlin/scripts/setup-yandex-ca.sh and repeat via run-eliza.sh.",
                rememberInHistory = false,
                usage = null,
                warningOrError = error.message,
            )
        } catch (error: HttpTimeoutException) {
            return AgentReply(
                content = "Request timed out. Try again.",
                rememberInHistory = false,
                usage = null,
                warningOrError = error.message,
            )
        }

        val parsedRoot = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()

        if (response.statusCode() !in 200..299) {
            return AgentReply(
                content = "HTTP status: ${response.statusCode()}\n${extractErrorSummary(parsedRoot) ?: "API error body is hidden to avoid leaking internal metadata."}",
                rememberInHistory = false,
                usage = parsedRoot?.let { extractUsage(it["response"]?.jsonObject ?: it) },
                warningOrError = "HTTP ${response.statusCode()}",
            )
        }

        if (parsedRoot == null) {
            return AgentReply(
                content = "Could not parse API JSON response.",
                rememberInHistory = false,
                usage = null,
                warningOrError = "Bad JSON response",
            )
        }

        val responseRoot = parsedRoot["response"]?.jsonObject ?: parsedRoot
        return AgentReply(
            content = extractAssistantText(responseRoot),
            rememberInHistory = true,
            usage = extractUsage(responseRoot),
            warningOrError = null,
        )
    }

    private fun loadRecentMessages(): MutableList<ChatMessage> {
        if (!recentMessagesPath.exists()) return mutableListOf()
        return runCatching {
            json.decodeFromString<List<ChatMessage>>(Files.readString(recentMessagesPath)).toMutableList()
        }.getOrElse { error ->
            System.err.println("Failed to read recent messages: ${error.message}")
            mutableListOf()
        }
    }

    private fun loadSummary(): String {
        if (!summaryPath.exists()) return ""
        return runCatching { Files.readString(summaryPath) }.getOrElse { error ->
            System.err.println("Failed to read summary: ${error.message}")
            ""
        }
    }

    private fun saveRecentMessages() {
        recentMessagesPath.parent?.createDirectories()
        Files.writeString(recentMessagesPath, json.encodeToString(recentMessages))
    }

    private fun saveSummary() {
        summaryPath.parent?.createDirectories()
        Files.writeString(summaryPath, summary)
    }

    private fun extractAssistantText(root: JsonObject): String {
        val choices = root["choices"] as? JsonArray
        val firstChoice = choices?.firstOrNull()?.jsonObject
        val message = firstChoice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content
        return content ?: "Could not find choices[0].message.content in API response."
    }

    private fun extractUsage(root: JsonObject): ApiUsage? {
        val usage = root["usage"]?.jsonObject ?: return null
        return ApiUsage(
            promptTokens = usage["prompt_tokens"]?.asInt(),
            completionTokens = usage["completion_tokens"]?.asInt(),
            totalTokens = usage["total_tokens"]?.asInt(),
            costUsd = usage["cost"]?.asDouble(),
        )
    }

    private fun extractErrorSummary(root: JsonObject?): String? {
        if (root == null) return null
        val responseRoot = root["response"]?.jsonObject ?: root
        val errorElement = responseRoot["error"] ?: root["error"]

        val message = when (errorElement) {
            is JsonObject -> errorElement["message"]?.jsonPrimitive?.content
            else -> errorElement?.jsonPrimitive?.content
        }

        return message?.let { "API error: $it" }
    }
}

private fun printComparison(comparison: ComparisonResult, summaryResult: SummaryResult) {
    println("=== CONTEXT MODE: FULL HISTORY ===")
    println("Messages sent: ${comparison.full.messagesSent}")
    println("Prompt tokens: ${comparison.full.promptTokens}")
    println("Response tokens: ${comparison.full.responseTokens}")
    println("API usage: ${formatApiUsage(comparison.full.apiUsage)}")
    println("Cost: ${comparison.full.cost.label()}")
    println("Response:")
    println(comparison.full.answer)
    println()

    println("=== CONTEXT MODE: COMPRESSED HISTORY ===")
    println("Summary tokens: ${comparison.summaryTokens}")
    println("Recent messages: ${comparison.recentMessagesSent}")
    println("Messages sent: ${comparison.compressed.messagesSent}")
    println("Prompt tokens: ${comparison.compressed.promptTokens}")
    println("Response tokens: ${comparison.compressed.responseTokens}")
    println("API usage: ${formatApiUsage(comparison.compressed.apiUsage)}")
    println("Cost: ${comparison.compressed.cost.label()}")
    println("Response:")
    println(comparison.compressed.answer)
    println()

    val savedTokens = comparison.full.promptTokens - comparison.compressed.promptTokens
    val savedPercent = if (comparison.full.promptTokens == 0) {
        0.0
    } else {
        savedTokens * 100.0 / comparison.full.promptTokens
    }

    println("=== TOKEN SAVINGS ===")
    println("Full history tokens: ${comparison.full.promptTokens}")
    println("Compressed context tokens: ${comparison.compressed.promptTokens}")
    println("Saved tokens: $savedTokens")
    println("Saved percent: ${formatPercent(savedPercent)}")
    println("Summary creation tokens: ${summaryResult.requestTokens + summaryResult.responseTokens}")
    println()

    println("=== COST COMPARISON ===")
    println("Full history answer cost: ${comparison.full.cost.label()}")
    println("Compressed answer cost: ${comparison.compressed.cost.label()}")
    println("Summary creation cost: ${summaryResult.cost.label()}")
    println("Compressed total for this demo: ${sumCosts(comparison.compressed.cost, summaryResult.cost).label()}")
    println("Note: summary costs extra now, but can save tokens on later requests.")
    println()

    println("=== QUALITY COMPARISON ===")
    println("Expected facts: Никита; курс по AI-агентам; Kotlin CLI; direct REST; JSON history; GitHub PR workflow; concise explanations.")
    println("Full history answer: ${comparison.localQuality.fullLabel}")
    println("Compressed history answer: ${comparison.localQuality.compressedLabel}")
    println("Lost facts: ${comparison.localQuality.lostFacts.ifEmpty { "none detected by local keyword check" }}")
    println()
}

private fun buildDemoHistory(): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    fun user(text: String) {
        messages += ChatMessage(role = "user", content = text)
    }
    fun assistant(text: String) {
        messages += ChatMessage(role = "assistant", content = text)
    }

    user("Привет, меня зовут Никита. Я прохожу курс по AI-агентам.")
    assistant("Привет, Никита. Запомнил, что ты проходишь курс по AI-агентам.")
    user("Мои технические предпочтения: Kotlin CLI, прямой REST-запрос к LLM API, без высокоуровневых SDK.")
    assistant("Запомнил: Kotlin CLI, direct REST, без LLM SDK.")
    user("Историю я хочу хранить в JSON, API-ключ только через .env, код выкладывать на GitHub через отдельный PR на каждый день.")
    assistant("Запомнил JSON-хранилище, .env для ключей и GitHub PR workflow.")
    user("Объяснения хочу короткие и понятные, без лишней теории.")
    assistant("Ок, буду отвечать кратко и практично.")

    repeat(16) { index ->
        user("Сообщение-заполнитель ${index + 1}: обсуждаем детали учебного проекта, чтобы история стала длиннее и появилась причина делать summary.")
        assistant("Принял сообщение-заполнитель ${index + 1}. Продолжаем копить контекст для демонстрации сжатия истории.")
    }

    user("Перед финальным вопросом напомню: последние сообщения могут не содержать ранние важные факты.")
    assistant("Да, поэтому summary должно сохранить ранние факты и предпочтения.")
    return messages
}

private fun compareQuality(fullAnswer: String, compressedAnswer: String): QualityComparison {
    val facts = listOf(
        ExpectedFact("Никита", listOf("никита")),
        ExpectedFact("курс по AI-агентам", listOf("ai-агент", "ai агент", "агент")),
        ExpectedFact("Kotlin CLI", listOf("kotlin", "cli")),
        ExpectedFact("direct REST", listOf("rest")),
        ExpectedFact("JSON history", listOf("json")),
        ExpectedFact("GitHub PR workflow", listOf("github", "pr", "pull request")),
        ExpectedFact("concise explanations", listOf("крат", "корот", "понят")),
    )

    val fullDetected = facts.filter { it.matches(fullAnswer) }.map { it.label }.toSet()
    val compressedDetected = facts.filter { it.matches(compressedAnswer) }.map { it.label }.toSet()
    val lostFacts = (fullDetected - compressedDetected).joinToString(", ")

    return QualityComparison(
        fullLabel = "${fullDetected.size}/${facts.size} expected facts detected",
        compressedLabel = "${compressedDetected.size}/${facts.size} expected facts detected",
        lostFacts = lostFacts,
    )
}

private data class ExpectedFact(
    val label: String,
    val keywords: List<String>,
) {
    fun matches(answer: String): Boolean {
        val normalized = answer.lowercase()
        return keywords.any { normalized.contains(it) }
    }
}

private class ApproximateTokenCounter {
    private val tokenLikePattern = Regex("""[\p{L}\p{N}_]+|[^\s]""")

    fun countText(text: String): Int {
        if (text.isBlank()) return 0
        val lexicalEstimate = tokenLikePattern.findAll(text).count()
        val charEstimate = ceil(text.length / 4.0).toInt()
        return max(1, max(lexicalEstimate, charEstimate))
    }

    fun countMessage(message: ChatMessage): Int {
        val chatOverhead = 4
        return chatOverhead + countText(message.role) + countText(message.content)
    }

    fun countMessages(messages: List<ChatMessage>): Int {
        val assistantPrimerOverhead = 3
        return assistantPrimerOverhead + messages.sumOf { countMessage(it) }
    }
}

private data class AgentConfig(
    val apiUrl: String,
    val authScheme: String,
    val apiKey: String,
    val model: String,
)

private data class ModelConfig(
    val name: String,
    val inputPricePerMillionTokens: Double?,
    val outputPricePerMillionTokens: Double?,
) {
    fun estimateCost(promptTokens: Int, completionTokens: Int): CostEstimate {
        val inputPrice = inputPricePerMillionTokens ?: return CostEstimate.Unknown
        val outputPrice = outputPricePerMillionTokens ?: return CostEstimate.Unknown
        val inputCost = promptTokens / 1_000_000.0 * inputPrice
        val outputCost = completionTokens / 1_000_000.0 * outputPrice
        return CostEstimate.Known(inputCost + outputCost)
    }

    fun priceLabel(): String {
        val input = inputPricePerMillionTokens?.let { "$$it / 1M input tokens" } ?: "input price unknown"
        val output = outputPricePerMillionTokens?.let { "$$it / 1M output tokens" } ?: "output price unknown"
        return "$input, $output"
    }

    companion object {
        fun fromEnv(env: Map<String, String>): ModelConfig {
            return ModelConfig(
                name = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
                inputPricePerMillionTokens = env["MODEL_INPUT_PRICE_PER_1M_TOKENS"]?.toDoubleOrNull(),
                outputPricePerMillionTokens = env["MODEL_OUTPUT_PRICE_PER_1M_TOKENS"]?.toDoubleOrNull(),
            )
        }
    }
}

private sealed interface CostEstimate {
    fun label(): String

    data object Unknown : CostEstimate {
        override fun label(): String = "unknown"
    }

    data class Known(val usd: Double) : CostEstimate {
        override fun label(): String = "$${moneyFormat.format(usd)}"
    }
}

private data class AgentReply(
    val content: String,
    val rememberInHistory: Boolean,
    val usage: ApiUsage?,
    val warningOrError: String?,
)

private data class ApiUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val costUsd: Double?,
)

private data class SummaryResult(
    val summary: String,
    val oldMessagesCount: Int,
    val recentMessagesCount: Int,
    val requestTokens: Int,
    val responseTokens: Int,
    val summaryTokens: Int,
    val cost: CostEstimate,
)

private data class ModeResult(
    val messagesSent: Int,
    val promptTokens: Int,
    val responseTokens: Int,
    val answer: String,
    val apiUsage: ApiUsage?,
    val cost: CostEstimate,
)

private data class ComparisonResult(
    val full: ModeResult,
    val compressed: ModeResult,
    val summaryTokens: Int,
    val recentMessagesSent: Int,
    val localQuality: QualityComparison,
)

private data class QualityComparison(
    val fullLabel: String,
    val compressedLabel: String,
    val lostFacts: String,
)

private data class AskResult(
    val answer: String,
    val currentRequestTokens: Int,
    val promptTokens: Int,
    val responseTokens: Int,
    val cost: CostEstimate,
    val compressionResult: SummaryResult?,
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

private fun JsonElement.asInt(): Int? = jsonPrimitive.intOrNull

private fun JsonElement.asDouble(): Double? = jsonPrimitive.doubleOrNull

private fun formatPercent(value: Double): String = "${DecimalFormat("0.0").format(value)}%"

private fun sumCosts(first: CostEstimate, second: CostEstimate): CostEstimate {
    return if (first is CostEstimate.Known && second is CostEstimate.Known) {
        CostEstimate.Known(first.usd + second.usd)
    } else {
        CostEstimate.Unknown
    }
}

private fun formatApiUsage(usage: ApiUsage?): String {
    if (usage == null) return "not provided"
    val prompt = usage.promptTokens?.toString() ?: "?"
    val completion = usage.completionTokens?.toString() ?: "?"
    val total = usage.totalTokens?.toString() ?: "?"
    return "prompt=$prompt, completion=$completion, total=$total"
}

private fun compactForConsole(text: String, maxChars: Int = 700): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    return if (normalized.length <= maxChars) {
        normalized
    } else {
        normalized.take(maxChars) + "... [truncated]"
    }
}
