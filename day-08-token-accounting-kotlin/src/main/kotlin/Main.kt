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
    "Ты TokenStudyAgent — учебный помощник. Отвечай кратко, чтобы демонстрации токенов не тратили лишние деньги. Используй историю сообщений, если она есть."

fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()
    val mode = args.firstOrNull()?.lowercase() ?: env["DAY08_MODE"]?.lowercase() ?: "interactive"
    val historyPath = Path.of(env["AGENT_HISTORY_FILE"] ?: "token-agent-history.json")
    val modelConfig = ModelConfig.fromEnv(env)
    val apiKey = env["LLM_API_KEY"]?.takeIf { it.isNotBlank() }
        ?: if (mode == "file-dry-run") "not-used-in-file-dry-run" else requiredEnv(env, "LLM_API_KEY")

    val agent = Agent(
        config = AgentConfig(
            apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
            authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
            apiKey = apiKey,
            model = env["LLM_MODEL"] ?: modelConfig.name,
        ),
        modelConfig = modelConfig,
        systemPrompt = env["AGENT_SYSTEM_PROMPT"] ?: DEFAULT_SYSTEM_PROMPT,
        historyPath = historyPath,
        debug = env["AGENT_DEBUG"]?.toBooleanStrictOrNull() ?: false,
    )

    println("TokenStudyAgent CLI")
    println("Mode: $mode")
    println("Model: ${agent.model}")
    println("History file: ${agent.historyPath.absolute()}")
    println("Tokenizer: approximate local estimator; API usage is printed when provider returns it.")
    println("Model context window: ${modelConfig.contextWindowTokens} tokens")
    println("App context limit: ${modelConfig.appContextLimitTokens} tokens")
    println("Prices: ${modelConfig.priceLabel()}")
    println()

    when (mode) {
        "interactive" -> runInteractive(agent)
        "short" -> runShortDialog(agent)
        "long" -> runLongDialog(agent)
        "overflow" -> runOverflowDemo(agent)
        "file-dry-run" -> runBigFileDryRun(env, agent)
        "file-send" -> runBigFileSend(env, agent)
        else -> {
            println("Unknown mode: $mode")
            println("Available modes: interactive, short, long, overflow, file-dry-run, file-send")
            exitProcess(1)
        }
    }
}

private fun runInteractive(agent: Agent) {
    println("Type `exit`, `quit`, or `/exit` to stop.")
    println("Type `/debug` to toggle request body output.")
    println("Type `/clear` to delete saved history and start a new dialog.")
    println("Type `/stats` to print the measurement table.")
    println()

    while (true) {
        print("User: ")
        System.out.flush()
        val input = readLine()?.trim() ?: break
        if (input.isBlank()) continue

        when (input.lowercase()) {
            "exit", "quit", "/exit" -> {
                println("Agent: До встречи!")
                agent.printMeasurementTable()
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

            "/stats" -> {
                agent.printMeasurementTable()
                continue
            }
        }

        agent.askAndPrint(input)
    }
}

private fun runShortDialog(agent: Agent) {
    agent.resetDemoHistory()
    println("=== SHORT DIALOG DEMO ===")
    listOf(
        "Привет! Меня зовут Никита.",
        "Объясни в 2 коротких предложениях, что такое токены в LLM.",
        "Как меня зовут и что ты мне сейчас объясняешь?",
    ).forEach { agent.askAndPrint(it) }
    agent.printMeasurementTable()
}

private fun runLongDialog(agent: Agent) {
    agent.resetDemoHistory()
    println("=== LONG DIALOG DEMO ===")
    val messages = listOf(
        "Запомни факт 1: я делаю задание восьмого дня про токены.",
        "Запомни факт 2: каждый новый запрос отправляет всю историю messages.",
        "Запомни факт 3: max_tokens ограничивает ответ, а не контекстное окно.",
        "Запомни факт 4: если история растет, prompt tokens тоже растут.",
        "Запомни факт 5: стоимость запроса зависит от input и output tokens.",
        "Запомни факт 6: при переполнении контекста старые сообщения могут исчезнуть.",
        "Ответь одним предложением: почему длинный чат дороже короткого?",
        "Ответь одним предложением: что обычно хранится в messages?",
        "Ответь одним предложением: чем опасна автоматическая обрезка контекста?",
        "Теперь перечисли 3 самых важных факта, которые я просил запомнить.",
    )
    messages.forEach { agent.askAndPrint(it) }
    agent.printMeasurementTable()
}

private fun runOverflowDemo(agent: Agent) {
    agent.resetDemoHistory()
    println("=== OVERFLOW DEMO WITH ARTIFICIAL APP LIMIT ===")
    println("This mode intentionally creates a large local message. The app stops before API call if the request exceeds APP_CONTEXT_LIMIT_TOKENS.")
    println()

    val largeMessage = buildString {
        appendLine("Это безопасная демонстрация переполнения контекста.")
        appendLine("Не отправляй это в модель, если приложение видит превышение лимита.")
        repeat(260) { index ->
            append("Фрагмент ${index + 1}: длинная учебная строка про токены, историю messages, стоимость запроса и лимит контекстного окна. ")
        }
    }

    agent.askAndPrint(largeMessage)
    agent.printMeasurementTable()
}

private fun runBigFileDryRun(env: Map<String, String>, agent: Agent) {
    val file = Path.of(env["BIG_CONTEXT_FILE"] ?: "/Users/shitznikita/Downloads/skills-all.md")
    println("=== BIG FILE DRY RUN ===")
    agent.printBigFileEstimate(file)
    println()
    println("No API request was sent. To send the file deliberately, use mode `file-send` and set CONFIRM_BIG_CONTEXT_SEND=YES_I_UNDERSTAND_THE_COST.")
}

private fun runBigFileSend(env: Map<String, String>, agent: Agent) {
    val file = Path.of(env["BIG_CONTEXT_FILE"] ?: "/Users/shitznikita/Downloads/skills-all.md")
    println("=== BIG FILE SEND ===")
    val canSend = env["CONFIRM_BIG_CONTEXT_SEND"] == "YES_I_UNDERSTAND_THE_COST"
    agent.printBigFileEstimate(file)
    if (!canSend) {
        println()
        println("API request blocked: set CONFIRM_BIG_CONTEXT_SEND=YES_I_UNDERSTAND_THE_COST only after checking token count and expected cost.")
        return
    }

    val content = runCatching { Files.readString(file) }.getOrElse { error ->
        println()
        println("API request blocked: cannot read file `${file.absolute()}`.")
        println("Reason: ${error.message}")
        println("Grant the terminal/Java process access to the file or set BIG_CONTEXT_FILE to a readable path.")
        return
    }
    agent.askAndPrint(
        "Ниже большой контекст из локального файла. Не пересказывай его полностью. Ответь только: сколько примерно разделов ты видишь и какая общая тема?\n\n$content",
    )
    agent.printMeasurementTable()
}

private class Agent(
    private val config: AgentConfig,
    private val modelConfig: ModelConfig,
    private val systemPrompt: String,
    val historyPath: Path,
    debug: Boolean,
) {
    private val client = HttpClient.newHttpClient()
    private val tokenCounter = ApproximateTokenCounter()
    private val messages = loadHistory()
    private val measurements = mutableListOf<MeasurementRow>()

    var debug: Boolean = debug

    val model: String
        get() = config.model

    fun askAndPrint(userMessage: String) {
        val result = ask(userMessage)
        printResult(userMessage, result)
    }

    fun ask(userMessage: String): AgentResult {
        val userTokens = tokenCounter.countMessage(ChatMessage(role = "user", content = userMessage))
        messages += ChatMessage(role = "user", content = userMessage)
        saveHistory(quiet = true)

        val requestTokens = tokenCounter.countMessages(messages)
        val before = TokenStatsBefore(
            currentUserMessageTokens = userTokens,
            historyTokensBeforeRequest = requestTokens,
            requestTotalTokens = requestTokens,
            contextLimitTokens = modelConfig.appContextLimitTokens,
            limitUsagePercent = requestTokens * 100.0 / modelConfig.appContextLimitTokens,
            warning = limitWarning(requestTokens),
        )

        if (requestTokens > modelConfig.appContextLimitTokens) {
            messages.removeLast()
            saveHistory(quiet = true)
            val error = "Context limit exceeded: current request has $requestTokens tokens, app limit is ${modelConfig.appContextLimitTokens}. Request was not sent to API."
            recordMeasurement(userTokens, requestTokens, null, 0, null, error)
            return AgentResult(
                answer = error,
                before = before,
                after = TokenStatsAfter(
                    responseTokens = 0,
                    historyTokensAfterResponse = tokenCounter.countMessages(messages),
                    apiUsage = null,
                    estimatedCost = modelConfig.estimateCost(requestTokens, 0),
                    warningOrError = error,
                ),
                sentToApi = false,
            )
        }

        val requestBody = buildRequest()
        if (debug) {
            println()
            println("=== DEBUG REST REQUEST BODY WITHOUT API KEY ===")
            println(json.encodeToString(json.parseToJsonElement(requestBody)))
            println("=== END DEBUG ===")
            println()
        }

        val assistantReply = callLLM(requestBody)
        val responseTokens = assistantReply.usage?.completionTokens ?: tokenCounter.countText(assistantReply.content)
        val promptTokensForCost = assistantReply.usage?.promptTokens ?: requestTokens
        val completionTokensForCost = assistantReply.usage?.completionTokens ?: responseTokens

        if (assistantReply.rememberInHistory) {
            messages += ChatMessage(role = "assistant", content = assistantReply.content)
            saveHistory(quiet = true)
        } else {
            messages.removeLast()
            saveHistory(quiet = true)
        }

        val historyAfterResponse = tokenCounter.countMessages(messages)
        val warningOrError = assistantReply.warningOrError ?: limitWarning(historyAfterResponse)
        val estimatedCost = assistantReply.usage?.costUsd?.let { CostEstimate.Known(it) }
            ?: modelConfig.estimateCost(promptTokensForCost, completionTokensForCost)

        recordMeasurement(
            userTokens = userTokens,
            requestTokens = requestTokens,
            usage = assistantReply.usage,
            responseTokens = responseTokens,
            estimatedCost = estimatedCost,
            warningOrError = warningOrError,
        )

        return AgentResult(
            answer = assistantReply.content,
            before = before,
            after = TokenStatsAfter(
                responseTokens = responseTokens,
                historyTokensAfterResponse = historyAfterResponse,
                apiUsage = assistantReply.usage,
                estimatedCost = estimatedCost,
                warningOrError = warningOrError,
            ),
            sentToApi = true,
        )
    }

    fun clearHistory() {
        messages.clear()
        messages += ChatMessage(role = "system", content = systemPrompt)
        historyPath.deleteIfExists()
        saveHistory(quiet = false)
    }

    fun resetDemoHistory() {
        measurements.clear()
        clearHistory()
    }

    fun printMeasurementTable() {
        if (measurements.isEmpty()) {
            println("No measurements yet.")
            return
        }

        println()
        println("=== MEASUREMENT TABLE ===")
        println("| Step | User tokens | History before | Response tokens | History after | API total | Cost | Limit % |")
        println("|---:|---:|---:|---:|---:|---:|---:|---:|")
        measurements.forEach { row ->
            println(
                "| ${row.step} | ${row.userTokens} | ${row.historyBeforeRequestTokens} | ${row.responseTokens} | " +
                    "${row.historyAfterResponseTokens} | ${row.apiTotalTokens?.toString() ?: "n/a"} | ${row.costLabel} | ${formatPercent(row.limitUsagePercent)} |",
            )
        }
        println()
    }

    fun printBigFileEstimate(file: Path) {
        if (!file.exists()) {
            println("File not found: ${file.absolute()}")
            return
        }

        val sizeBytes = runCatching { Files.size(file) }.getOrElse { error ->
            println("Cannot read file metadata: ${file.absolute()}")
            println("Reason: ${error.message}")
            return
        }
        val content = runCatching { Files.readString(file) }.getOrElse { error ->
            println("Cannot read file content: ${file.absolute()}")
            println("Reason: ${error.message}")
            println("No API request was sent.")
            println("If this is a macOS privacy restriction, grant access to Downloads or copy the file to a readable path and set BIG_CONTEXT_FILE.")
            return
        }
        val fileTokens = tokenCounter.countText(content)
        val requestTokens = tokenCounter.countMessages(
            messages + ChatMessage(
                role = "user",
                content = "Большой файл из BIG_CONTEXT_FILE. Содержимое не печатается в консоль.\n\n$content",
            ),
        )
        val inputOnlyCost = modelConfig.estimateCost(requestTokens, 0)

        println("File path: ${file.absolute()}")
        println("File size: $sizeBytes bytes")
        println("Approximate file tokens: $fileTokens")
        println("Approximate request tokens if sent now: $requestTokens")
        println("Model context window: ${modelConfig.contextWindowTokens} tokens")
        println("App context limit: ${modelConfig.appContextLimitTokens} tokens")
        println("Approximate input cost if sent: ${inputOnlyCost.label()}")

        when {
            requestTokens > modelConfig.contextWindowTokens ->
                println("WARNING: request is above configured model context window.")
            requestTokens > modelConfig.appContextLimitTokens ->
                println("WARNING: request is above APP_CONTEXT_LIMIT_TOKENS and will be stopped by the app.")
            requestTokens > modelConfig.contextWindowTokens * 0.8 ->
                println("WARNING: request is close to configured model context window.")
        }
    }

    private fun buildRequest(): String {
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

    private fun callLLM(requestBody: String): AgentReply {
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
                content = "Не удалось проверить SSL-сертификат API. Запусти day-08-token-accounting-kotlin/scripts/setup-yandex-ca.sh и повтори запуск через run-eliza.sh.",
                rememberInHistory = false,
                usage = null,
                warningOrError = error.message,
            )
        } catch (error: HttpTimeoutException) {
            return AgentReply(
                content = "Request timed out. Попробуй повторить сообщение.",
                rememberInHistory = false,
                usage = null,
                warningOrError = error.message,
            )
        }

        val parsedRoot = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()

        if (response.statusCode() !in 200..299) {
            return AgentReply(
                content = "HTTP status: ${response.statusCode()}\n${response.body()}",
                rememberInHistory = false,
                usage = parsedRoot?.let { extractUsage(it["response"]?.jsonObject ?: it) },
                warningOrError = "HTTP ${response.statusCode()}",
            )
        }

        if (parsedRoot == null) {
            return AgentReply(
                content = "Не удалось разобрать JSON-ответ API:\n${response.body()}",
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

    private fun saveHistory(quiet: Boolean) {
        historyPath.parent?.createDirectories()
        Files.writeString(historyPath, json.encodeToString(messages))
        if (!quiet) {
            println("History saved: ${historyPath.absolute()} (${messages.size} messages)")
        }
    }

    private fun extractAssistantText(root: JsonObject): String {
        val choices = root["choices"] as? JsonArray
        val firstChoice = choices?.firstOrNull()?.jsonObject
        val message = firstChoice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content

        return content ?: "Не удалось найти choices[0].message.content в ответе:\n${json.encodeToString(root)}"
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

    private fun limitWarning(tokens: Int): String? {
        val usage = tokens.toDouble() / modelConfig.appContextLimitTokens
        return when {
            tokens > modelConfig.appContextLimitTokens ->
                "Context limit exceeded: current history has $tokens tokens, limit is ${modelConfig.appContextLimitTokens}."
            usage >= 0.9 ->
                "WARNING: context is above 90% of APP_CONTEXT_LIMIT_TOKENS."
            usage >= 0.75 ->
                "WARNING: context is above 75% of APP_CONTEXT_LIMIT_TOKENS."
            else -> null
        }
    }

    private fun recordMeasurement(
        userTokens: Int,
        requestTokens: Int,
        usage: ApiUsage?,
        responseTokens: Int,
        estimatedCost: CostEstimate?,
        warningOrError: String?,
    ) {
        measurements += MeasurementRow(
            step = measurements.size + 1,
            userTokens = userTokens,
            historyBeforeRequestTokens = requestTokens,
            responseTokens = responseTokens,
            historyAfterResponseTokens = tokenCounter.countMessages(messages),
            apiTotalTokens = usage?.totalTokens,
            costLabel = (usage?.costUsd?.let { CostEstimate.Known(it) } ?: estimatedCost ?: CostEstimate.Unknown).label(),
            limitUsagePercent = requestTokens * 100.0 / modelConfig.appContextLimitTokens,
            warningOrError = warningOrError,
        )
    }

    private fun printResult(userMessage: String, result: AgentResult) {
        println("=== USER MESSAGE ===")
        println(compactForConsole(userMessage))
        println()
        println("=== TOKEN STATS BEFORE REQUEST ===")
        println("Current message tokens: ${result.before.currentUserMessageTokens}")
        println("History tokens before request: ${result.before.historyTokensBeforeRequest}")
        println("Estimated request tokens: ${result.before.requestTotalTokens}")
        println("Context limit: ${result.before.contextLimitTokens}")
        println("Limit usage: ${formatPercent(result.before.limitUsagePercent)}")
        result.before.warning?.let { println("Warning: $it") }
        println()
        println("=== MODEL RESPONSE ===")
        println(result.answer)
        println()
        println("=== TOKEN STATS AFTER RESPONSE ===")
        println("Response tokens: ${result.after.responseTokens}")
        println("History tokens after response: ${result.after.historyTokensAfterResponse}")
        println("API prompt tokens: ${result.after.apiUsage?.promptTokens?.toString() ?: "not provided"}")
        println("API completion tokens: ${result.after.apiUsage?.completionTokens?.toString() ?: "not provided"}")
        println("API total tokens: ${result.after.apiUsage?.totalTokens?.toString() ?: "not provided"}")
        println("Estimated/API cost: ${result.after.estimatedCost.label()}")
        result.after.warningOrError?.let {
            println()
            println("=== WARNING / ERROR ===")
            println(it)
        }
        println()
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
    val contextWindowTokens: Int,
    val appContextLimitTokens: Int,
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
                contextWindowTokens = env["MODEL_CONTEXT_WINDOW_TOKENS"]?.toIntOrNull() ?: 131_072,
                appContextLimitTokens = env["APP_CONTEXT_LIMIT_TOKENS"]?.toIntOrNull() ?: 3_000,
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

private data class AgentResult(
    val answer: String,
    val before: TokenStatsBefore,
    val after: TokenStatsAfter,
    val sentToApi: Boolean,
)

private data class TokenStatsBefore(
    val currentUserMessageTokens: Int,
    val historyTokensBeforeRequest: Int,
    val requestTotalTokens: Int,
    val contextLimitTokens: Int,
    val limitUsagePercent: Double,
    val warning: String?,
)

private data class TokenStatsAfter(
    val responseTokens: Int,
    val historyTokensAfterResponse: Int,
    val apiUsage: ApiUsage?,
    val estimatedCost: CostEstimate,
    val warningOrError: String?,
)

private data class ApiUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val costUsd: Double?,
)

private data class MeasurementRow(
    val step: Int,
    val userTokens: Int,
    val historyBeforeRequestTokens: Int,
    val responseTokens: Int,
    val historyAfterResponseTokens: Int,
    val apiTotalTokens: Int?,
    val costLabel: String,
    val limitUsagePercent: Double,
    val warningOrError: String?,
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

private fun compactForConsole(text: String, maxChars: Int = 700): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    return if (normalized.length <= maxChars) {
        normalized
    } else {
        normalized.take(maxChars) + "... [truncated in console, full text is not printed]"
    }
}
