import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model))
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
        }.toString()

        if (config.debug) {
            println("=== DEBUG REQUEST ===")
            println(body)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.apiUrl))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Authorization", "${config.authScheme} ${config.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                LlmResponse("", null, "HTTP ${response.statusCode()}: ${response.body().take(600)}")
            } else {
                parseResponse(response.body())
            }
        } catch (e: Exception) {
            LlmResponse("", null, "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun parseResponse(body: String): LlmResponse {
        val root = appJson.parseToJsonElement(body).jsonObject
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
                promptTokens = it["prompt_tokens"].asInt(),
                completionTokens = it["completion_tokens"].asInt(),
                totalTokens = it["total_tokens"].asInt(),
                costUsd = it["cost"].asDouble(),
            )
        }
        return LlmResponse(content, usage, null)
    }
}
