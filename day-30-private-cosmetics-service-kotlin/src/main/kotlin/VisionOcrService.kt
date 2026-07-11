import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Base64

class HybridOcrEngine(
    private val primary: OcrEngine?,
    private val fallback: OcrEngine,
) : OcrEngine {
    @Volatile
    private var primaryProbed = false

    @Volatile
    private var primaryReady = false

    constructor(config: AppConfig) : this(
        primary = if (config.elizaVisionEnabled) ElizaVisionOcrEngine(config) else null,
        fallback = TesseractOcrEngine(config),
    )

    override suspend fun diagnose(): Boolean {
        primaryReady = runCatching { primary?.diagnose() == true }.getOrDefault(false)
        primaryProbed = true
        return primaryReady || fallback.diagnose()
    }

    override suspend fun recognize(photo: UploadedPhoto): OcrResult {
        val primaryEngine = primary
        if (primaryEngine != null && !primaryProbed) {
            primaryReady = runCatching { primaryEngine.diagnose() }.getOrDefault(false)
            primaryProbed = true
        }
        var externalAttempted = false
        if (primaryEngine != null && primaryReady) {
            try {
                externalAttempted = true
                return primaryEngine.recognize(photo).also { primaryReady = true }
            } catch (_: OcrUnavailableException) {
                primaryReady = false
            }
        }
        val local = fallback.recognize(photo)
        return if (primaryEngine == null) {
            local
        } else if (!externalAttempted) {
            local.copy(
                provider = "local_tesseract_fallback",
                notice = "Eliza Vision недоступна по readiness-проверке: фото не отправлялось, использован локальный OCR.",
            )
        } else {
            local.copy(
                provider = "local_tesseract_fallback",
                externalProcessing = true,
                notice = "Попытка Eliza Vision завершилась ошибкой: фото могло быть передано внешнему сервису, затем использован локальный OCR.",
            )
        }
    }

    override fun currentProvider(): String = when {
        primaryReady -> "eliza_vision"
        primary != null -> "local_tesseract_fallback"
        else -> "local_tesseract"
    }

    override fun externalProcessingAvailable(): Boolean = primaryReady
}

class ElizaVisionOcrEngine(
    private val config: AppConfig,
    private val endpoint: URI = config.elizaVisionApiUrl,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.elizaVisionRequestTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : OcrEngine {
    @Volatile
    private var cachedReadiness: CachedVisionReadiness? = null

    override suspend fun diagnose(): Boolean = withContext(Dispatchers.IO) {
        if (!config.elizaVisionEnabled || config.elizaVisionApiKey == null) return@withContext false
        val now = System.nanoTime()
        cachedReadiness?.takeIf { now < it.expiresAtNanos }?.let { return@withContext it.ready }
        val ready = probeModels()
        cachedReadiness = CachedVisionReadiness(ready, now + READINESS_CACHE_NANOS)
        ready
    }

    override fun currentProvider(): String = "eliza_vision"

    override fun externalProcessingAvailable(): Boolean = true

    override suspend fun recognize(photo: UploadedPhoto): OcrResult = withContext(Dispatchers.IO) {
        val apiKey = config.elizaVisionApiKey?.reveal()
            ?: throw OcrUnavailableException("Eliza Vision OAuth token is not configured.")
        if (!config.elizaVisionEnabled) throw OcrUnavailableException("Eliza Vision is disabled.")

        val dataUrl = "data:image/${photo.format};base64,${Base64.getEncoder().encodeToString(photo.bytes)}"
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.elizaVisionModel))
            put("max_completion_tokens", JsonPrimitive(config.elizaVisionMaxCompletionTokens))
            put("response_format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(VISION_PROMPT))
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put("image_url", buildJsonObject {
                                put("url", JsonPrimitive(dataUrl))
                                put("detail", JsonPrimitive("high"))
                            })
                        })
                    })
                })
            })
        }
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(config.elizaVisionRequestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "${config.elizaVisionAuthScheme} $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(AppJson.strict.encodeToString(body)))
            .build()
        val response = send(request)
        val transcription = parse(response.body())
        val extracted = IngredientsSectionExtractor.extract(transcription.ingredientsText)
        if (extracted.isBlank()) throw OcrUnavailableException("Eliza Vision returned no ingredient text.")
        val uncertain = transcription.uncertainFragments
            .map { it.replace(Regex("\\s+"), " ").trim().take(200) }
            .filter(String::isNotBlank)
            .distinct()
            .take(20)
        OcrResult(
            text = extracted,
            quality = estimateVisionQuality(extracted, uncertain),
            provider = "eliza_vision",
            externalProcessing = true,
            uncertainFragments = uncertain,
            notice = "Фото обработано Eliza Vision и не сохраняется приложением. Проверьте INCI перед анализом.",
        )
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw OcrUnavailableException("Eliza Vision returned HTTP ${response.statusCode()}.")
        }
        if (response.body().length > MAX_RESPONSE_CHARS) {
            throw OcrUnavailableException("Eliza Vision response is unexpectedly large.")
        }
        response
    } catch (error: HttpTimeoutException) {
        throw OcrUnavailableException("Eliza Vision did not answer before the configured timeout.", error)
    } catch (error: IOException) {
        throw OcrUnavailableException("Eliza Vision request could not be completed.", error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw OcrUnavailableException("Eliza Vision request was interrupted.", error)
    }

    private fun probeModels(): Boolean {
        val apiKey = config.elizaVisionApiKey?.reveal() ?: return false
        val modelsEndpoint = URI(
            endpoint.scheme,
            endpoint.userInfo,
            endpoint.host,
            endpoint.port,
            "/openrouter/v1/models",
            null,
            null,
        )
        val request = HttpRequest.newBuilder()
            .uri(modelsEndpoint)
            .timeout(READINESS_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "${config.elizaVisionAuthScheme} $apiKey")
            .GET()
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299 || response.body().length > MAX_RESPONSE_CHARS) return false
            val root = AppJson.strict.parseToJsonElement(response.body()).jsonObject
            (root["data"] as? JsonArray)?.any { element ->
                (element as? JsonObject)?.visionString("id") == config.elizaVisionModel
            } == true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun parse(body: String): VisionLabelTranscription {
        val root = try {
            AppJson.strict.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw OcrUnavailableException("Eliza Vision response is not valid JSON.")
        }
        val responseRoot = root["response"] as? JsonObject ?: root
        val choice = (responseRoot["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw OcrUnavailableException("Eliza Vision response has no chat choice.")
        val finishReason = choice.visionString("finish_reason")
        if (finishReason != null && finishReason != "stop") {
            throw OcrUnavailableException("Eliza Vision did not finish the transcription.")
        }
        val content = ((choice["message"] as? JsonObject)?.visionString("content"))
            ?.trim()
            ?.removeJsonFence()
            ?: throw OcrUnavailableException("Eliza Vision response has no message content.")
        return try {
            AppJson.strict.decodeFromString(content)
        } catch (_: Exception) {
            throw OcrUnavailableException("Eliza Vision returned an invalid transcription contract.")
        }
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 1_000_000
        private val READINESS_TIMEOUT = java.time.Duration.ofSeconds(3)
        private val READINESS_CACHE_NANOS = java.time.Duration.ofMinutes(1).toNanos()
        private const val VISION_PROMPT = """
            You are a strict cosmetic-label transcription engine. Inspect the photograph carefully,
            including small text. Return JSON with exactly these keys: product_type, claims,
            ingredients_text, directions, cautions, uncertain_fragments. Transcribe the complete
            Ingredients/INCI section exactly in label order. Do not replace a hard-to-read botanical
            name with a more common ingredient. Never invent or silently autocorrect: put any genuinely
            unreadable fragment in uncertain_fragments. Re-read every ingredient once before answering.
        """
    }

    private data class CachedVisionReadiness(val ready: Boolean, val expiresAtNanos: Long)
}

object IngredientsSectionExtractor {
    private val startMarker = Regex(
        """^[ \t]*(?:ingredients?|ingred[il1]ents?|inci|состав(?:[ \t]*\(inci\))?)[ \t]*(?::|-)?[ \t]*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val stopMarker = Regex(
        """^[ \t]*(?:directions?|how[ \t]+to[ \t]+use|cautions?|warnings?|storage|manufactured(?:[ \t]+(?:for|by))?|made[ \t]+in|lot(?:[ \t]+no\.?)?|expiration(?:[ \t]+date)?|способ[ \t]+применения|предупреждени[ея]|меры[ \t]+предосторожности|изготовитель)\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val waterListStart = Regex(
        """^[^\n]{0,40}?(\b(?:water|aqua)[ \t]*,)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    fun extract(raw: String): String {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return normalized
        val markerStart = startMarker.find(normalized)
        val afterStart = if (markerStart != null) {
            normalized.substring(markerStart.range.last + 1)
        } else {
            val waterStart = waterListStart.find(normalized)?.groups?.get(1)?.range?.first ?: return normalized
            normalized.substring(waterStart)
        }.trimStart('\n', ' ', '\t', ':', '-', ';')
        val stop = stopMarker.find(afterStart)
        val section = (stop?.let { afterStart.substring(0, it.range.first) } ?: afterStart)
            .trim(' ', '\t', '\n', ',', ';', ':', '-')
        return section.takeIf { it.length >= 3 } ?: normalized
    }

    fun containsMarker(raw: String): Boolean = startMarker.containsMatchIn(normalize(raw))

    private fun normalize(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .replace('，', ',')
        .replace('；', ';')
        .lineSequence()
        .map { it.replace(Regex("[ \\t]+"), " ").trim() }
        .filter(String::isNotBlank)
        .joinToString("\n")
        .trim()
}

@Serializable
private data class VisionLabelTranscription(
    @SerialName("product_type") val productType: String? = null,
    val claims: String? = null,
    @SerialName("ingredients_text") val ingredientsText: String,
    val directions: String? = null,
    val cautions: String? = null,
    @SerialName("uncertain_fragments") val uncertainFragments: List<String> = emptyList(),
)

private fun JsonObject.visionString(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun String.removeJsonFence(): String {
    val value = trim()
    if (!value.startsWith("```")) return value
    return value.lines().drop(1).dropLast(1).joinToString("\n").trim()
}

private fun estimateVisionQuality(text: String, uncertain: List<String>): String {
    val ingredientLikeParts = text.split(Regex("[,;\\n]+")).count { part ->
        part.trim().length in 3..100 && part.any(Char::isLetter)
    }
    return when {
        uncertain.isEmpty() && ingredientLikeParts >= 8 -> "high"
        uncertain.size <= 2 && ingredientLikeParts >= 4 -> "medium"
        else -> "low"
    }
}
