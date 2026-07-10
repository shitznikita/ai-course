import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

class CloudClient(private val config: AppConfig) : AnswerGenerator {
    override val kind: String = "cloud"
    override val configuredModel: String = config.cloudModel

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(config.cloudRequestTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun generate(messages: List<PromptMessage>): ChatReply {
        val apiKey = config.cloudApiKey
            ?: throw CloudConfigurationException("LLM_API_KEY is required for compare and benchmark commands.")
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.cloudModel))
            put("messages", messages.toJsonMessagesForCloud())
            config.cloudTemperature?.let { put("temperature", JsonPrimitive(it)) }
        }
        val request = HttpRequest.newBuilder()
            .uri(config.cloudApiUrl)
            .timeout(config.cloudRequestTimeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "${config.cloudAuthScheme} $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.compact.encodeToString(body)))
            .build()
        val startedAt = System.nanoTime()
        val response = send(request)
        val elapsed = System.nanoTime() - startedAt
        return parse(response.body(), elapsed)
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw CloudRequestException(
                "Cloud request returned HTTP ${response.statusCode()}.",
                statusCode = response.statusCode(),
            )
        }
        response
    } catch (error: HttpTimeoutException) {
        throw CloudRequestException("Cloud request timed out.", error)
    } catch (error: IOException) {
        throw CloudRequestException("Cloud request could not be completed.", error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CloudRequestException("Cloud request was interrupted.", error)
    }

    private fun parse(body: String, elapsed: Long): ChatReply {
        val root = try {
            AppJson.compact.parseToJsonElement(body).jsonObject
        } catch (error: Exception) {
            throw CloudRequestException("Cloud response is not valid JSON.")
        }
        val responseRoot = root["response"] as? JsonObject ?: root
        val choice = (responseRoot["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw CloudRequestException("Cloud response has no chat choice.")
        val content = ((choice["message"] as? JsonObject)?.stringField("content"))?.trim()
            ?: throw CloudRequestException("Cloud response has no message content.")
        if (content.isBlank()) throw CloudRequestException("Cloud returned an empty answer.")
        val usage = (responseRoot["usage"] as? JsonObject) ?: (root["usage"] as? JsonObject)
        return ChatReply(
            model = responseRoot.stringField("model") ?: config.cloudModel,
            content = content,
            promptTokens = usage?.longField("prompt_tokens"),
            completionTokens = usage?.longField("completion_tokens"),
            clientElapsedNanos = elapsed,
            evalDurationNanos = null,
        )
    }
}

private fun List<PromptMessage>.toJsonMessagesForCloud(): JsonArray = buildJsonArray {
    this@toJsonMessagesForCloud.forEach { message ->
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role))
            put("content", JsonPrimitive(message.content))
        })
    }
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longField(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull
