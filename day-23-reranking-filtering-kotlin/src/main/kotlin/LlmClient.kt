import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LlmClient(private val config: AppConfig) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun chat(messages: List<ChatMessage>): LlmResponse {
        val apiKey = config.llmApiKey
            ?: return LlmResponse("", null, "LLM_API_KEY is required for live LLM modes.")
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.llmModel))
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive(message.role))
                                put("content", JsonPrimitive(message.content))
                            },
                        )
                    }
                },
            )
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.llmApiUrl))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Authorization", "${config.llmAuthScheme} $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.compact.encodeToString(body)))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                LlmResponse("", null, "HTTP ${response.statusCode()}: ${response.body().shortPreview(600)}")
            } else {
                parse(response.body())
            }
        } catch (e: Exception) {
            LlmResponse("", null, "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun parse(body: String): LlmResponse {
        val root = AppJson.compact.parseToJsonElement(body).jsonObject
        val responseRoot = root["response"]?.jsonObject ?: root
        val choices = responseRoot["choices"]?.jsonArray ?: JsonArray(emptyList())
        val content = choices.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
            ?: ""
        val usageObject = responseRoot["usage"]?.jsonObject ?: root["usage"]?.jsonObject
        val usage = usageObject?.let {
            ApiUsage(
                promptTokens = it["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                completionTokens = it["completion_tokens"]?.jsonPrimitive?.intOrNull,
                totalTokens = it["total_tokens"]?.jsonPrimitive?.intOrNull,
                costUsd = it["cost"]?.jsonPrimitive?.doubleOrNull,
            )
        }
        return LlmResponse(content, usage, null)
    }
}
