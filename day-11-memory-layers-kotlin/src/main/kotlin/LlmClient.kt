import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LlmClient(
    private val config: AppConfig,
    private val debug: Boolean,
) {
    private val client = HttpClient.newHttpClient()

    fun complete(messages: List<ChatMessage>): LlmResponse {
        val requestBody = buildRequestBody(messages)
        if (debug) {
            println()
            println("=== DEBUG REST REQUEST BODY WITHOUT API KEY ===")
            println(appJson.encodeToString(appJson.parseToJsonElement(requestBody)))
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
            return LlmResponse(
                content = "Не удалось проверить SSL-сертификат API. Запусти scripts/setup-yandex-ca.sh и повтори запуск через run-eliza.sh.",
                usage = null,
                warningOrError = error.message,
            )
        } catch (error: HttpTimeoutException) {
            return LlmResponse(
                content = "Request timed out. Попробуй повторить сообщение.",
                usage = null,
                warningOrError = error.message,
            )
        }

        val parsedRoot = runCatching { appJson.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        if (response.statusCode() !in 200..299) {
            return LlmResponse(
                content = "HTTP status: ${response.statusCode()}\n${extractErrorSummary(parsedRoot) ?: "API error body is hidden."}",
                usage = parsedRoot?.let { extractUsage(it["response"]?.jsonObject ?: it) },
                warningOrError = "HTTP ${response.statusCode()}",
            )
        }

        if (parsedRoot == null) {
            return LlmResponse(
                content = "Не удалось разобрать JSON-ответ API:\n${response.body()}",
                usage = null,
                warningOrError = "Bad JSON response",
            )
        }

        val responseRoot = parsedRoot["response"]?.jsonObject ?: parsedRoot
        return LlmResponse(
            content = extractAssistantText(responseRoot),
            usage = extractUsage(responseRoot),
            warningOrError = null,
        )
    }

    private fun buildRequestBody(messages: List<ChatMessage>): String {
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

    private fun extractAssistantText(root: JsonObject): String {
        val choices = root["choices"] as? JsonArray
        val firstChoice = choices?.firstOrNull()?.jsonObject
        val message = firstChoice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content
        return content ?: "Не удалось найти choices[0].message.content в ответе:\n${appJson.encodeToString(root)}"
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
