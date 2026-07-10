import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

class OllamaClient(private val config: AppConfig) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(config.requestTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun diagnose(): OllamaStatus {
        val versionResponse = getJson("/api/version")
        val version = versionResponse.stringField("version")
            ?: throw OllamaProtocolException("Ollama /api/version response does not contain 'version'.")

        val tagsResponse = getJson("/api/tags")
        val models = (tagsResponse["models"] as? JsonArray)
            ?.mapNotNull { element ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val name = model.stringField("name") ?: return@mapNotNull null
                OllamaModel(name = name, alias = model.stringField("model"))
            }
            ?: throw OllamaProtocolException("Ollama /api/tags response does not contain 'models'.")

        return OllamaStatus(version = version, models = models)
    }

    fun chat(systemPrompt: String, userPrompt: String, responseSchema: JsonObject): OllamaReply {
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("content", JsonPrimitive(systemPrompt))
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(userPrompt))
                        },
                    )
                },
            )
            put("format", responseSchema)
            put("stream", JsonPrimitive(false))
            put("think", JsonPrimitive(false))
            put("keep_alive", JsonPrimitive(config.keepAlive))
            put(
                "options",
                buildJsonObject {
                    put("temperature", JsonPrimitive(0))
                },
            )
        }
        val request = HttpRequest.newBuilder()
            .uri(config.ollamaEndpoint("/api/chat"))
            .timeout(config.requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
            .build()

        val startedAt = System.nanoTime()
        val response = parseJsonObject(send(request, "/api/chat").body(), "/api/chat")
        val clientElapsedNanos = System.nanoTime() - startedAt
        if ((response["done"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw OllamaProtocolException("Ollama chat response did not complete successfully.")
        }
        val message = response["message"] as? JsonObject
            ?: throw OllamaProtocolException("Ollama chat response does not contain 'message'.")
        val content = message.stringField("content")?.trim()
            ?: throw OllamaProtocolException("Ollama chat response does not contain message.content.")
        if (content.isBlank()) {
            throw OllamaProtocolException("Ollama returned an empty analysis.")
        }

        return OllamaReply(
            model = response.stringField("model") ?: config.model,
            content = content,
            promptTokens = response.longField("prompt_eval_count"),
            completionTokens = response.longField("eval_count"),
            clientElapsedNanos = clientElapsedNanos,
            evalDurationNanos = response.longField("eval_duration"),
        )
    }

    private fun getJson(path: String): JsonObject {
        val request = HttpRequest.newBuilder()
            .uri(config.ollamaEndpoint(path))
            .timeout(config.requestTimeout)
            .GET()
            .build()
        return parseJsonObject(send(request, path).body(), path)
    }

    private fun send(request: HttpRequest, endpoint: String): HttpResponse<String> = try {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw OllamaHttpException(response.statusCode(), endpoint, errorPreview(response.body()))
        }
        response
    } catch (error: HttpTimeoutException) {
        throw OllamaUnavailableException("Ollama did not answer before the configured timeout.", error)
    } catch (error: ConnectException) {
        throw OllamaUnavailableException("Cannot connect to Ollama at ${config.ollamaBaseUrl}.", error)
    } catch (error: IOException) {
        throw OllamaUnavailableException("Cannot reach Ollama at ${config.ollamaBaseUrl}: ${error.message}", error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw OllamaUnavailableException("Ollama request was interrupted.", error)
    }

    private fun parseJsonObject(body: String, endpoint: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (error: Exception) {
        throw OllamaProtocolException("Ollama $endpoint response is not valid JSON.")
    }

    private fun errorPreview(body: String): String {
        val apiError = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)?.stringField("error")
        }.getOrNull()
        return (apiError ?: body).shortPreview()
    }
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longField(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

private fun String.shortPreview(limit: Int = 320): String =
    replace(Regex("\\s+"), " ").trim().let { if (it.length <= limit) it else it.take(limit) + "…" }
