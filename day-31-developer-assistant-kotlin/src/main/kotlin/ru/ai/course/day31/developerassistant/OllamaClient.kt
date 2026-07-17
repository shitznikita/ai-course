package ru.ai.course.day31.developerassistant

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

interface AssistantAnswerGenerator {
    fun answer(prompt: PromptPack, schema: JsonObject): OllamaReply
}

class OllamaClient(private val config: AppConfig) : AssistantAnswerGenerator {
    private val http = HttpClient.newBuilder()
        .connectTimeout(config.ollamaTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun diagnose(): OllamaStatus {
        val timeout = Duration.ofSeconds(3)
        val version = getJson("/api/version", timeout)["version"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw OllamaProtocolException("Ollama /api/version response has no version.")
        val models = (getJson("/api/tags", timeout)["models"] as? JsonArray).orEmpty().mapNotNull { item ->
            val name = (item as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
            name?.let(::OllamaModel)
        }
        return OllamaStatus(version = version, models = models)
    }

    override fun answer(prompt: PromptPack, schema: JsonObject): OllamaReply {
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.ollamaModel))
            put("messages", buildJsonArray {
                add(message("system", prompt.system))
                add(message("user", prompt.user))
            })
            put("format", schema)
            put("stream", JsonPrimitive(false))
            put("think", JsonPrimitive(false))
            put("options", buildJsonObject {
                put("temperature", JsonPrimitive(0))
                put("num_ctx", JsonPrimitive(config.ollamaContextLength))
                put("num_predict", JsonPrimitive(config.ollamaMaxOutputTokens))
            })
        }
        val started = System.nanoTime()
        val response = postJson("/api/chat", body)
        val envelope = try {
            AppJson.tolerant.decodeFromString<OllamaChatEnvelope>(AppJson.strict.encodeToString(response))
        } catch (_: Exception) {
            throw OllamaProtocolException("Ollama /api/chat response has an unexpected shape.")
        }
        if (!envelope.done) throw OllamaProtocolException("Ollama /api/chat did not finish successfully.")
        val content = envelope.message?.content?.trim().orEmpty()
        if (content.isBlank()) throw OllamaProtocolException("Ollama returned an empty message.")
        return OllamaReply(
            model = envelope.model ?: config.ollamaModel,
            content = content,
            promptTokens = envelope.promptEvalCount,
            completionTokens = envelope.evalCount,
            elapsedNanos = System.nanoTime() - started,
        )
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(content))
    }

    private fun getJson(path: String, timeout: Duration): JsonObject {
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
            .timeout(config.ollamaTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.strict.encodeToString(body)))
            .build()
        return parse(send(request, path).body(), path)
    }

    private fun send(request: HttpRequest, endpoint: String): HttpResponse<String> = try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw OllamaHttpException("Ollama $endpoint returned HTTP ${response.statusCode()}.")
        }
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
        AppJson.tolerant.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        throw OllamaProtocolException("Ollama $endpoint response is not valid JSON.")
    }
}

class OllamaUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
class OllamaProtocolException(message: String) : IllegalStateException(message)
class OllamaHttpException(message: String) : IllegalStateException(message)
