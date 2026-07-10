import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

class LocalOllamaClient(private val config: AppConfig) : EmbeddingGateway, AnswerGenerator {
    override val kind: String = "local"
    override val configuredModel: String = config.localModel

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(config.ollamaRequestTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun diagnose(): OllamaStatus {
        val versionResponse = getJson("/api/version")
        val version = versionResponse.stringField("version")
            ?: throw OllamaProtocolException("Ollama /api/version response does not contain version.")
        val tagsResponse = getJson("/api/tags")
        val models = (tagsResponse["models"] as? JsonArray)
            ?.mapNotNull { entry ->
                val model = entry as? JsonObject ?: return@mapNotNull null
                val name = model.stringField("name") ?: return@mapNotNull null
                OllamaModel(name, model.stringField("model"))
            }
            ?: throw OllamaProtocolException("Ollama /api/tags response does not contain models.")
        return OllamaStatus(version, models)
    }

    override fun embed(texts: List<String>): EmbeddingReply {
        require(texts.isNotEmpty()) { "At least one text is required for embedding." }
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.embeddingModel))
            put("input", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
            put("truncate", JsonPrimitive(false))
            put("keep_alive", JsonPrimitive(config.keepAlive))
        }
        val request = jsonRequest("/api/embed", body)
        val startedAt = System.nanoTime()
        val response = parseJsonObject(send(request, "/api/embed").body(), "/api/embed")
        val elapsed = System.nanoTime() - startedAt
        val embeddings = (response["embeddings"] as? JsonArray)
            ?.map { element ->
                val vector = (element as? JsonArray)
                    ?: throw OllamaProtocolException("Ollama /api/embed returned a non-array embedding.")
                normalizeVector(vector.map { value -> (value as? JsonPrimitive)?.double ?: throw OllamaProtocolException("Embedding contains a non-number.") })
            }
            ?: throw OllamaProtocolException("Ollama /api/embed response does not contain embeddings.")
        if (embeddings.size != texts.size) {
            throw OllamaProtocolException("Ollama /api/embed returned ${embeddings.size} vectors for ${texts.size} inputs.")
        }
        return EmbeddingReply(
            model = response.stringField("model") ?: config.embeddingModel,
            embeddings = embeddings,
            promptTokens = response.longField("prompt_eval_count"),
            clientElapsedNanos = elapsed,
            totalDurationNanos = response.longField("total_duration"),
        )
    }

    override fun generate(messages: List<PromptMessage>): ChatReply {
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.localModel))
            put("messages", messages.toJsonMessages())
            put("format", groundedAnswerSchema())
            put("stream", JsonPrimitive(false))
            put("think", JsonPrimitive(false))
            put("keep_alive", JsonPrimitive(config.keepAlive))
            put("options", buildJsonObject { put("temperature", JsonPrimitive(0)) })
        }
        val request = jsonRequest("/api/chat", body)
        val startedAt = System.nanoTime()
        val response = parseJsonObject(send(request, "/api/chat").body(), "/api/chat")
        val elapsed = System.nanoTime() - startedAt
        if ((response["done"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw OllamaProtocolException("Ollama /api/chat did not complete successfully.")
        }
        val content = ((response["message"] as? JsonObject)?.stringField("content"))?.trim()
            ?: throw OllamaProtocolException("Ollama /api/chat response does not contain message.content.")
        if (content.isBlank()) throw OllamaProtocolException("Ollama returned an empty answer.")
        return ChatReply(
            model = response.stringField("model") ?: config.localModel,
            content = content,
            promptTokens = response.longField("prompt_eval_count"),
            completionTokens = response.longField("eval_count"),
            clientElapsedNanos = elapsed,
            evalDurationNanos = response.longField("eval_duration"),
        )
    }

    private fun getJson(path: String): JsonObject {
        val request = HttpRequest.newBuilder()
            .uri(config.ollamaEndpoint(path))
            .timeout(config.ollamaRequestTimeout)
            .GET()
            .build()
        return parseJsonObject(send(request, path).body(), path)
    }

    private fun jsonRequest(path: String, body: JsonObject): HttpRequest = HttpRequest.newBuilder()
        .uri(config.ollamaEndpoint(path))
        .timeout(config.ollamaRequestTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(AppJson.compact.encodeToString(body)))
        .build()

    private fun send(request: HttpRequest, endpoint: String): HttpResponse<String> = try {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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

    private fun parseJsonObject(body: String, endpoint: String): JsonObject = try {
        AppJson.compact.parseToJsonElement(body).jsonObject
    } catch (error: Exception) {
        throw OllamaProtocolException("Ollama $endpoint response is not valid JSON.")
    }
}

private fun List<PromptMessage>.toJsonMessages(): JsonArray = buildJsonArray {
    this@toJsonMessages.forEach { message ->
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role))
            put("content", JsonPrimitive(message.content))
        })
    }
}

private fun groundedAnswerSchema(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("additionalProperties", JsonPrimitive(false))
    put(
        "properties",
        buildJsonObject {
            put("status", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray { add(JsonPrimitive("answered")); add(JsonPrimitive("unknown")) })
            })
            put("answer", stringSchema())
            put("sources", sourceArraySchema())
            put("quotes", quoteArraySchema())
            put("clarifyingQuestion", nullableStringSchema())
        },
    )
    put(
        "required",
        buildJsonArray {
            add(JsonPrimitive("status"))
            add(JsonPrimitive("answer"))
            add(JsonPrimitive("sources"))
            add(JsonPrimitive("quotes"))
            add(JsonPrimitive("clarifyingQuestion"))
        },
    )
}

private fun stringSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("string")) }

private fun nullableStringSchema(): JsonObject = buildJsonObject {
    put("type", buildJsonArray { add(JsonPrimitive("string")); add(JsonPrimitive("null")) })
}

private fun sourceArraySchema(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("array"))
    put("items", buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("additionalProperties", JsonPrimitive(false))
        put("properties", buildJsonObject {
            put("source", stringSchema())
            put("section", stringSchema())
            put("chunk_id", stringSchema())
        })
        put("required", buildJsonArray { add(JsonPrimitive("source")); add(JsonPrimitive("section")); add(JsonPrimitive("chunk_id")) })
    })
}

private fun quoteArraySchema(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("array"))
    put("items", buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("additionalProperties", JsonPrimitive(false))
        put("properties", buildJsonObject {
            put("quote_id", nullableStringSchema())
            put("source", stringSchema())
            put("section", stringSchema())
            put("chunk_id", stringSchema())
            put("text", stringSchema())
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("quote_id"))
            add(JsonPrimitive("source"))
            add(JsonPrimitive("section"))
            add(JsonPrimitive("chunk_id"))
            add(JsonPrimitive("text"))
        })
    })
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longField(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull
