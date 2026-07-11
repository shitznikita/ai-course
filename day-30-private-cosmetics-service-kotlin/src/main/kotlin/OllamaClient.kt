import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

interface LocalLlmGateway {
    fun diagnose(): OllamaStatus
    fun chat(systemPrompt: String, userPrompt: String, responseSchema: JsonObject): OllamaReply
}

class OllamaClient(private val config: AppConfig) : LocalLlmGateway {
    private val http = HttpClient.newBuilder()
        .connectTimeout(config.requestTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun diagnose(): OllamaStatus {
        val diagnosticTimeout = Duration.ofSeconds(3)
        val version = getJson("/api/version", diagnosticTimeout).stringField("version")
            ?: throw OllamaProtocolException("Ollama /api/version response has no version.")
        val models = (getJson("/api/tags", diagnosticTimeout)["models"] as? JsonArray)?.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            model.stringField("name")?.let { OllamaModel(it, model.stringField("model")) }
        } ?: throw OllamaProtocolException("Ollama /api/tags response has no models array.")
        return OllamaStatus(version, models)
    }

    override fun chat(systemPrompt: String, userPrompt: String, responseSchema: JsonObject): OllamaReply {
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("messages", buildJsonArray {
                add(message("system", systemPrompt))
                add(message("user", userPrompt))
            })
            put("format", responseSchema)
            put("stream", JsonPrimitive(false))
            put("think", JsonPrimitive(false))
            put("keep_alive", JsonPrimitive(config.keepAlive))
            put("options", buildJsonObject {
                put("temperature", JsonPrimitive(0))
                put("num_ctx", JsonPrimitive(config.contextLength))
                put("num_predict", JsonPrimitive(config.maxOutputTokens))
            })
        }
        val started = System.nanoTime()
        val response = postJson("/api/chat", body)
        val elapsed = System.nanoTime() - started
        if ((response["done"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw OllamaProtocolException("Ollama /api/chat did not finish successfully.")
        }
        val content = ((response["message"] as? JsonObject)?.stringField("content"))?.trim()
            ?: throw OllamaProtocolException("Ollama /api/chat response has no message.content.")
        if (content.isBlank()) throw OllamaProtocolException("Ollama returned an empty response.")
        return OllamaReply(
            model = response.stringField("model") ?: config.model,
            content = content,
            promptTokens = response.longField("prompt_eval_count"),
            completionTokens = response.longField("eval_count"),
            elapsedNanos = elapsed,
            evalDurationNanos = response.longField("eval_duration"),
        )
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(content))
    }

    private fun getJson(path: String, timeout: Duration = config.requestTimeout): JsonObject {
        val request = HttpRequest.newBuilder()
            .uri(config.ollamaEndpoint(path))
            .timeout(timeout)
            .GET()
            .build()
        return parse(send(request, path).body(), path)
    }

    private fun postJson(path: String, body: JsonObject): JsonObject {
        val request = HttpRequest.newBuilder()
            .uri(config.ollamaEndpoint(path))
            .timeout(config.requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.strict.encodeToString(body)))
            .build()
        return parse(send(request, path).body(), path)
    }

    private fun send(request: HttpRequest, endpoint: String): HttpResponse<String> = try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw OllamaHttpException(response.statusCode(), endpoint)
        response
    } catch (error: HttpTimeoutException) {
        throw OllamaUnavailableException("Ollama did not answer before the configured timeout.", error)
    } catch (error: ConnectException) {
        throw OllamaUnavailableException("Cannot connect to local Ollama at ${config.ollamaBaseUrl}.", error)
    } catch (error: IOException) {
        throw OllamaUnavailableException("Cannot reach local Ollama at ${config.ollamaBaseUrl}.", error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw OllamaUnavailableException("Ollama request was interrupted.", error)
    }

    private fun parse(body: String, endpoint: String): JsonObject = try {
        AppJson.strict.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        throw OllamaProtocolException("Ollama $endpoint response is not valid JSON.")
    }
}

private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.longField(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
