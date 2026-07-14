package ru.ai.course.day31.developerassistant

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

interface EmbeddingClient {
    val backend: String
    val model: String

    fun embed(texts: List<String>): List<List<Double>>
}

object EmbeddingFactory {
    fun create(config: AppConfig): EmbeddingClient =
        when (config.embeddingBackend) {
            "hash" -> HashEmbeddingClient()
            "ollama" -> OllamaEmbeddingClient(config)
            else -> error("Unsupported embedding backend: ${config.embeddingBackend}")
        }
}

class HashEmbeddingClient(private val dimensions: Int = 512) : EmbeddingClient {
    override val backend: String = "hash"
    override val model: String = "deterministic-hash-v1-$dimensions"

    override fun embed(texts: List<String>): List<List<Double>> = texts.map(::embedOne)

    private fun embedOne(text: String): List<Double> {
        val vector = DoubleArray(dimensions)
        TextTools.tokens(text).forEach { token ->
            addFeature(vector, "token:$token", 1.0)
            if (token.length >= 4) {
                token.windowed(3).forEach { trigram -> addFeature(vector, "trigram:$trigram", 0.30) }
            }
        }
        return normalizeL2(vector.toList())
    }

    private fun addFeature(vector: DoubleArray, feature: String, weight: Double) {
        val digest = MessageDigest.getInstance("SHA-256").digest(feature.toByteArray(Charsets.UTF_8))
        val unsignedHash = (
            ((digest[0].toInt() and 0xff) shl 24) or
                ((digest[1].toInt() and 0xff) shl 16) or
                ((digest[2].toInt() and 0xff) shl 8) or
                (digest[3].toInt() and 0xff)
            ).toLong() and 0xffff_ffffL
        val index = (unsignedHash % vector.size).toInt()
        val sign = if ((digest[4].toInt() and 1) == 0) 1.0 else -1.0
        vector[index] += sign * weight
    }
}

class OllamaEmbeddingClient(private val config: AppConfig) : EmbeddingClient {
    override val backend: String = "ollama"
    override val model: String = config.ollamaEmbeddingModel
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(config.ollamaTimeout)
        .build()

    override fun embed(texts: List<String>): List<List<Double>> {
        if (texts.isEmpty()) return emptyList()
        val requestBody = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "input" to JsonArray(texts.map { JsonPrimitive(it) }),
            ),
        )
        val request = HttpRequest.newBuilder()
            .uri(config.ollamaEndpoint("/api/embed"))
            .timeout(config.ollamaTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.strict.encodeToString(requestBody)))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            "Ollama embedding request failed with HTTP ${response.statusCode()}: ${response.body().singleLine()}"
        }

        val root = AppJson.tolerant.parseToJsonElement(response.body()).jsonObject
        val embeddings = root["embeddings"]?.jsonArray
            ?.map { vector -> vector.jsonArray.map { it.jsonPrimitive.content.toDouble() } }
            ?: error("Ollama /api/embed response has no embeddings array.")
        require(embeddings.size == texts.size) {
            "Ollama returned ${embeddings.size} embeddings for ${texts.size} texts."
        }
        require(embeddings.all { it.isNotEmpty() } && embeddings.map { it.size }.distinct().size == 1) {
            "Ollama returned empty or inconsistent embedding dimensions."
        }
        return embeddings.map(::normalizeL2)
    }
}
