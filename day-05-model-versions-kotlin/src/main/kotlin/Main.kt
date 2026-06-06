import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

private const val DEFAULT_PROMPT =
    "Проанализируй идею мобильного приложения для учета личных финансов. Дай 5 ключевых функций, 3 риска реализации и короткий план MVP. Ответь структурировано и кратко."

private const val TEMPERATURE = 0.2
private const val MAX_TOKENS = 700

private val models = listOf(
    ModelSpec(
        label = "WEAK",
        id = "google/gemma-3-4b-it",
        displayName = "Google: Gemma 3 4B",
        modelClass = "weak",
        size = "4B parameters",
        inputPricePerToken = BigDecimal("0.00000004"),
        outputPricePerToken = BigDecimal("0.00000008"),
        url = "https://openrouter.ai/google/gemma-3-4b-it",
        why = "Small 4B model: cheap and lightweight, but still below the 6B weak-model threshold.",
    ),
    ModelSpec(
        label = "MEDIUM",
        id = "meta-llama/llama-3.3-70b-instruct",
        displayName = "Meta: Llama 3.3 70B Instruct",
        modelClass = "medium",
        size = "70B parameters",
        inputPricePerToken = BigDecimal("0.0000001"),
        outputPricePerToken = BigDecimal("0.00000032"),
        url = "https://openrouter.ai/meta-llama/llama-3.3-70b-instruct",
        why = "70B model: stronger reasoning and writing quality with moderate cost.",
    ),
    ModelSpec(
        label = "STRONG",
        id = "nousresearch/hermes-3-llama-3.1-405b",
        displayName = "Nous: Hermes 3 405B Instruct",
        modelClass = "strong",
        size = "405B parameters",
        inputPricePerToken = BigDecimal("0.000001"),
        outputPricePerToken = BigDecimal("0.000001"),
        url = "https://openrouter.ai/nousresearch/hermes-3-llama-3.1-405b",
        why = "405B model: much larger model class for richer analysis and better instruction following.",
    ),
)

fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()

    val apiKey = requiredEnv(env, "LLM_API_KEY")
    val apiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions"
    val authScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth"
    val prompt = args.joinToString(" ").ifBlank { DEFAULT_PROMPT }

    val client = HttpClient.newHttpClient()
    val config = LlmConfig(apiUrl, authScheme, apiKey)
    val results = models.mapIndexed { index, model ->
        runModel(
            index = index + 1,
            client = client,
            config = config,
            model = model,
            prompt = prompt,
        )
    }

    printComparison(results)
}

private fun runModel(
    index: Int,
    client: HttpClient,
    config: LlmConfig,
    model: ModelSpec,
    prompt: String,
): ModelResult {
    val requestBody = buildRequestBody(model = model.id, prompt = prompt)

    println("=== MODEL $index: ${model.label} ===")
    println("Model: ${model.displayName}")
    println("Model id: ${model.id}")
    println("Class: ${model.modelClass}, ${model.size}")
    println("Why this class: ${model.why}")
    println("URL: ${model.url}")
    println("Temperature: $TEMPERATURE")
    println("Max tokens: $MAX_TOKENS")
    println("REST request body without API key:")
    println(json.encodeToString(json.parseToJsonElement(requestBody)))
    println()

    val startedAt = Instant.now()
    val startNanos = System.nanoTime()
    val response = sendChatCompletion(
        client = client,
        config = config,
        requestBody = requestBody,
    )
    val endedAt = Instant.now()
    val durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0

    val cost = response.usage?.cost ?: estimateCost(
        usage = response.usage,
        inputPricePerToken = model.inputPricePerToken,
        outputPricePerToken = model.outputPricePerToken,
    )

    println("Start time: $startedAt")
    println("End time: $endedAt")
    println("Duration: ${formatSeconds(durationSeconds)} s")
    println("Tokens: ${response.usage?.format() ?: "not provided by API"}")
    println("Estimated cost: ${formatCost(cost)}")
    println("Answer:")
    println(response.answer)
    println()

    return ModelResult(
        model = model,
        startedAt = startedAt,
        endedAt = endedAt,
        durationSeconds = durationSeconds,
        usage = response.usage,
        estimatedCost = cost,
        answer = response.answer,
    )
}

private fun buildRequestBody(model: String, prompt: String): String {
    return buildJsonObject {
        put("model", model)
        put("temperature", TEMPERATURE)
        put("max_tokens", MAX_TOKENS)
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
): LlmResponse {
    val request = HttpRequest.newBuilder()
        .uri(URI.create(config.apiUrl))
        .timeout(Duration.ofSeconds(240))
        .header("Authorization", "${config.authScheme} ${config.apiKey}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

    val response = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (error: SSLHandshakeException) {
        System.err.println("Не удалось проверить SSL-сертификат API.")
        System.err.println("Для Eliza запусти: day-05-model-versions-kotlin/scripts/setup-yandex-ca.sh")
        System.err.println("Потом повтори запрос через: day-05-model-versions-kotlin/scripts/run-eliza.sh")
        exitProcess(1)
    } catch (error: HttpTimeoutException) {
        return LlmResponse(
            answer = "Request timed out. Попробуй повторить запуск.",
            usage = null,
        )
    }

    val root = json.parseToJsonElement(response.body()).jsonObject
    val responseRoot = root["response"]?.jsonObject ?: root
    val assistantText = extractAssistantText(responseRoot)
    val usage = extractUsage(responseRoot)

    if (response.statusCode() !in 200..299) {
        return LlmResponse(
            answer = "HTTP status: ${response.statusCode()}\nProvider returned partial response:\n$assistantText",
            usage = usage,
        )
    }

    return LlmResponse(
        answer = assistantText,
        usage = usage,
    )
}

private fun extractAssistantText(root: JsonObject): String {
    val choices = root["choices"] as? JsonArray
    val firstChoice = choices?.firstOrNull()?.jsonObject
    val message = firstChoice?.get("message")?.jsonObject
    val content = message?.get("content")?.jsonPrimitive?.content

    return content ?: "Не удалось найти choices[0].message.content в ответе:\n${json.encodeToString(root)}"
}

private fun extractUsage(root: JsonObject): Usage? {
    val usage = root["usage"]?.jsonObject ?: return null

    return Usage(
        promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull,
        completionTokens = usage["completion_tokens"]?.jsonPrimitive?.longOrNull,
        totalTokens = usage["total_tokens"]?.jsonPrimitive?.longOrNull,
        cost = usage["cost"]?.jsonPrimitive?.doubleOrNull?.let { BigDecimal.valueOf(it) },
    )
}

private fun estimateCost(
    usage: Usage?,
    inputPricePerToken: BigDecimal,
    outputPricePerToken: BigDecimal,
): BigDecimal? {
    if (usage?.promptTokens == null || usage.completionTokens == null) return usage?.cost

    return inputPricePerToken.multiply(BigDecimal.valueOf(usage.promptTokens))
        .plus(outputPricePerToken.multiply(BigDecimal.valueOf(usage.completionTokens)))
}

private fun printComparison(results: List<ModelResult>) {
    println("=== COMPARISON ===")
    println("Quality:")
    println("- Compare the answers above: look for correctness, completeness, concrete MVP steps, and whether risks are realistic.")
    println("- Weak models may be verbose, mix languages, or miss business nuance; medium and strong models are usually more stable on analytical prompts.")
    println("- In the verified run, the 70B and 405B answers were more concise and complete than the 4B answer.")
    println()

    println("Speed:")
    results.sortedBy { it.durationSeconds }.forEach { result ->
        println("- ${result.model.label}: ${formatSeconds(result.durationSeconds)} s (${result.model.displayName})")
    }
    println()

    println("Resource usage:")
    results.forEach { result ->
        println("- ${result.model.label}: tokens=${result.usage?.totalTokens ?: "not provided"}, cost=${formatCost(result.estimatedCost)}")
    }
    println()

    println("Conclusion:")
    println("Small models are cheaper and can be fast, but may lose meaning on analytical tasks. Medium and strong models usually give more reliable structure and reasoning, while strong models cost more per token.")
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

private fun Usage.format(): String {
    return "prompt=${promptTokens ?: "not provided"}, completion=${completionTokens ?: "not provided"}, total=${totalTokens ?: "not provided"}"
}

private fun formatSeconds(seconds: Double): String {
    return BigDecimal.valueOf(seconds).setScale(2, RoundingMode.HALF_UP).toPlainString()
}

private fun formatCost(cost: BigDecimal?): String {
    if (cost == null) return "not provided by API"
    return "$" + cost.setScale(8, RoundingMode.HALF_UP).toPlainString()
}

private data class LlmConfig(
    val apiUrl: String,
    val authScheme: String,
    val apiKey: String,
)

private data class ModelSpec(
    val label: String,
    val id: String,
    val displayName: String,
    val modelClass: String,
    val size: String,
    val inputPricePerToken: BigDecimal,
    val outputPricePerToken: BigDecimal,
    val url: String,
    val why: String,
)

private data class ModelResult(
    val model: ModelSpec,
    val startedAt: Instant,
    val endedAt: Instant,
    val durationSeconds: Double,
    val usage: Usage?,
    val estimatedCost: BigDecimal?,
    val answer: String,
)

private data class LlmResponse(
    val answer: String,
    val usage: Usage?,
)

private data class Usage(
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
    val cost: BigDecimal?,
)
