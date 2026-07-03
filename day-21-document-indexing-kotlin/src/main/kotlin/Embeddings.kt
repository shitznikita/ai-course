import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.absoluteValue

interface EmbeddingClient {
    val backend: String
    val model: String
    fun embed(texts: List<String>): List<List<Double>>
}

object EmbeddingFactory {
    fun create(backend: String, config: AppConfig): EmbeddingClient =
        when (backend.lowercase()) {
            "hash" -> HashEmbeddingClient()
            "ollama" -> OllamaEmbeddingClient(config.ollamaBaseUrl, config.ollamaEmbedModel)
            else -> error("Unsupported EMBEDDING_BACKEND='$backend'. Use 'hash' or 'ollama'.")
        }
}

class HashEmbeddingClient(private val dimensions: Int = 512) : EmbeddingClient {
    override val backend: String = "hash"
    override val model: String = "deterministic-hash-$dimensions"

    override fun embed(texts: List<String>): List<List<Double>> = texts.map { text ->
        val vector = DoubleArray(dimensions)
        Tokenizer.tokens(text).forEach { token ->
            addFeature(vector, token, 1.0)
            if (token.length >= 4) {
                token.windowed(3).forEach { addFeature(vector, "tri:$it", 0.35) }
            }
        }
        normalizeL2(vector.toList())
    }

    private fun addFeature(vector: DoubleArray, feature: String, weight: Double) {
        val hash = feature.hashCode()
        val index = (hash.toLong().absoluteValue % vector.size).toInt()
        val sign = if (hash and 1 == 0) 1.0 else -1.0
        vector[index] += sign * weight
    }
}

class OllamaEmbeddingClient(
    private val baseUrl: String,
    override val model: String,
) : EmbeddingClient {
    override val backend: String = "ollama"
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    override fun embed(texts: List<String>): List<List<Double>> = texts.mapIndexed { index, text ->
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "prompt" to JsonPrimitive(text),
            ),
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/embeddings"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(IndexJson.compact.encodeToString(body)))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Ollama embedding request ${index + 1}/${texts.size} failed: HTTP ${response.statusCode()} ${response.body().shortPreview(300)}")
        }
        parseEmbedding(response.body())
    }

    private fun parseEmbedding(body: String): List<Double> {
        val root = IndexJson.compact.parseToJsonElement(body).jsonObject
        val embedding = root["embedding"]?.jsonArray
            ?: root["embeddings"]?.jsonArray?.firstOrNull()?.jsonArray
            ?: error("Ollama response does not contain 'embedding'.")
        return normalizeL2(embedding.map { it.jsonPrimitive.double })
    }
}
