import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Instant

interface OptimizationGenerator {
    fun generate(profile: OptimizationProfile, messages: List<PromptMessage>): ChatReply
}

class LocalOllamaClient(private val config: AppConfig) : EmbeddingGateway, OptimizationGenerator, ResourceGateway {
    private val httpClient = HttpClient.newBuilder().connectTimeout(config.ollamaRequestTimeout).followRedirects(HttpClient.Redirect.NEVER).build()

    fun diagnose(): OllamaStatus {
        val version = getJson("/api/version").stringField("version") ?: throw OllamaProtocolException("Ollama /api/version response does not contain version.")
        val models = (getJson("/api/tags")["models"] as? JsonArray)?.mapNotNull { entry ->
            val model = entry as? JsonObject ?: return@mapNotNull null
            model.stringField("name")?.let { OllamaModel(it, model.stringField("model")) }
        } ?: throw OllamaProtocolException("Ollama /api/tags response does not contain models.")
        return OllamaStatus(version, models)
    }

    fun show(model: String): OllamaModelDetails {
        val body = postJson("/api/show", buildJsonObject { put("model", JsonPrimitive(model)) })
        val details = body["details"] as? JsonObject
        val info = body["model_info"] as? JsonObject
        return OllamaModelDetails(
            model = body.stringField("model") ?: model,
            parameterSize = details?.stringField("parameter_size"),
            quantizationLevel = details?.stringField("quantization_level"),
            contextLength = info?.entries?.firstOrNull { (key, _) -> key.contains("context_length", true) }?.value?.longValue(),
        )
    }

    override fun snapshot(): ResourceSnapshot {
        val response = getJson("/api/ps")
        val models = (response["models"] as? JsonArray)?.mapNotNull { value ->
            val model = value as? JsonObject ?: return@mapNotNull null
            val details = model["details"] as? JsonObject
            model.stringField("name")?.let { name ->
                RunningModel(
                    name = name,
                    model = model.stringField("model"),
                    size = model.longField("size"),
                    sizeVram = model.longField("size_vram"),
                    contextLength = model.longField("context_length"),
                    processor = model.stringField("processor"),
                    quantization = details?.stringField("quantization_level"),
                )
            }
        } ?: throw OllamaProtocolException("Ollama /api/ps response does not contain models.")
        return ResourceSnapshot(Instant.now().toString(), models, MacMemoryPressure.sample())
    }

    override fun embed(texts: List<String>): EmbeddingReply {
        require(texts.isNotEmpty()) { "At least one text is required for embedding." }
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.embeddingModel))
            put("input", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
            put("truncate", JsonPrimitive(false)); put("keep_alive", JsonPrimitive(config.keepAlive))
        }
        val started = System.nanoTime()
        val response = postJson("/api/embed", requestBody)
        val embeddings = (response["embeddings"] as? JsonArray)?.map { entry ->
            (entry as? JsonArray)?.map { it.doubleValue() ?: throw OllamaProtocolException("Embedding contains a non-number.") }
                ?: throw OllamaProtocolException("Ollama /api/embed returned a non-array embedding.")
        } ?: throw OllamaProtocolException("Ollama /api/embed response does not contain embeddings.")
        if (embeddings.size != texts.size) throw OllamaProtocolException("Ollama /api/embed returned ${embeddings.size} vectors for ${texts.size} inputs.")
        return EmbeddingReply(response.stringField("model") ?: config.embeddingModel, embeddings, response.longField("prompt_eval_count"), System.nanoTime() - started)
    }

    override fun generate(profile: OptimizationProfile, messages: List<PromptMessage>): ChatReply {
        val started = System.nanoTime()
        val response = postJson("/api/chat", OllamaPayloads.chat(profile, messages, config.keepAlive))
        if ((response["done"] as? JsonPrimitive)?.booleanOrNull != true) throw OllamaProtocolException("Ollama /api/chat did not complete successfully.")
        val content = ((response["message"] as? JsonObject)?.stringField("content"))?.trim()
            ?: throw OllamaProtocolException("Ollama /api/chat response does not contain message.content.")
        if (content.isBlank()) throw OllamaProtocolException("Ollama returned an empty answer.")
        return ChatReply(
            response.stringField("model") ?: profile.model, content, response.longField("prompt_eval_count"),
            response.longField("eval_count"), System.nanoTime() - started, response.longField("eval_duration"),
        )
    }

    private fun getJson(path: String): JsonObject {
        val request = HttpRequest.newBuilder().uri(config.ollamaEndpoint(path)).timeout(config.ollamaRequestTimeout).GET().build()
        return parseJsonObject(send(request, path).body(), path)
    }

    private fun postJson(path: String, body: JsonObject): JsonObject {
        val request = HttpRequest.newBuilder().uri(config.ollamaEndpoint(path)).timeout(config.ollamaRequestTimeout)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(AppJson.compact.encodeToString(body))).build()
        return parseJsonObject(send(request, path).body(), path)
    }

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
        Thread.currentThread().interrupt(); throw OllamaUnavailableException("Ollama request was interrupted.", error)
    }

    private fun parseJsonObject(body: String, endpoint: String): JsonObject = try {
        AppJson.compact.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        throw OllamaProtocolException("Ollama $endpoint response is not valid JSON.")
    }
}

object OllamaPayloads {
    fun chat(profile: OptimizationProfile, messages: List<PromptMessage>, keepAlive: String): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(profile.model)); put("messages", messages.toJsonMessages()); put("format", groundedAnswerSchema())
        put("stream", JsonPrimitive(false)); put("think", JsonPrimitive(false)); put("keep_alive", JsonPrimitive(keepAlive))
        put("options", buildJsonObject {
            put("temperature", JsonPrimitive(profile.temperature)); put("num_predict", JsonPrimitive(profile.numPredict)); put("num_ctx", JsonPrimitive(profile.numCtx))
        })
    }
}

private object MacMemoryPressure {
    fun sample(): String? = try {
        val process = ProcessBuilder("/usr/bin/memory_pressure", "-Q").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim().replace(Regex("\\s+"), " ") }
        process.waitFor()
        output.takeIf { it.isNotBlank() }?.take(500)
    } catch (_: Exception) { null }
}

private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.longField(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun kotlinx.serialization.json.JsonElement.longValue(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun kotlinx.serialization.json.JsonElement.doubleValue(): Double? = (this as? JsonPrimitive)?.doubleOrNull
