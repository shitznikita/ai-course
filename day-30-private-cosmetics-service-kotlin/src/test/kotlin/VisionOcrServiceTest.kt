import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VisionOcrServiceTest {
    @Test
    fun `extracts only normalized ingredients section`() {
        val raw = """
            BRIGHTENING TONER PAD
            Ingredients: Water，  Glycerin；
              Niacinamide,  Panthenol
            Directions: wipe over clean skin
            Cautions: avoid contact with eyes
        """.trimIndent().replace("  Glycerin", "\u00A0 Glycerin")

        assertEquals(
            "Water, Glycerin;\nNiacinamide, Panthenol",
            IngredientsSectionExtractor.extract(raw),
        )
        assertEquals(
            "Water, Glycerin",
            IngredientsSectionExtractor.extract("  Water,   Glycerin  "),
        )
        assertEquals(
            "Water, Glycerin, Niacinamide",
            IngredientsSectionExtractor.extract("OCR noise f dients Water, Glycerin, Niacinamide"),
        )
    }

    @Test
    fun `sends vision payload and parses direct Eliza response`() = runBlocking {
        val captured = AtomicReference<CapturedVisionRequest>()
        val ingredients = "Ingredients: Water, Glycerin, Niacinamide, Panthenol, Allantoin, Caffeine, " +
            "Tocopherol, Citric Acid\nDirections: wipe over clean skin"
        val response = completionResponse(transcription(ingredients), wrapped = false)

        withVisionServer(response, captured) { endpoint ->
            val engine = ElizaVisionOcrEngine(visionConfig(), endpoint)
            val result = engine.recognize(testPhoto())
            val request = captured.get()
            val payload = AppJson.strict.parseToJsonElement(request.body).jsonObject

            assertEquals("OAuth $VISION_TOKEN", request.authorization)
            assertEquals("vision-test-model", payload["model"]?.jsonPrimitive?.content)
            assertEquals("768", payload["max_completion_tokens"]?.jsonPrimitive?.content)
            assertEquals(
                "json_object",
                payload["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
            )
            val content = payload["messages"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("content")
                ?.jsonArray
                ?: error("Missing multimodal message content")
            val image = content.single { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }.jsonObject
            assertEquals(
                "data:image/png;base64,AAECAw==",
                image["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
            )
            assertEquals("high", image["image_url"]?.jsonObject?.get("detail")?.jsonPrimitive?.content)

            assertEquals(
                "Water, Glycerin, Niacinamide, Panthenol, Allantoin, Caffeine, Tocopherol, Citric Acid",
                result.text,
            )
            assertEquals("high", result.quality)
            assertEquals("eliza_vision", result.provider)
            assertTrue(result.externalProcessing)
            assertTrue(result.uncertainFragments.isEmpty())
            assertContains(result.notice.orEmpty(), "Eliza Vision")
        }
    }

    @Test
    fun `parses wrapped Eliza response and reports uncertainty quality`() = runBlocking {
        val captured = AtomicReference<CapturedVisionRequest>()
        val content = transcription(
            ingredients = "INCI - Water, Glycerin, Panthenol, Niacinamide\nCautions: avoid eyes",
            uncertain = listOf("  unclear   botanical  ", "", "unclear botanical"),
        )
        val response = completionResponse("```json\n$content\n```", wrapped = true)

        withVisionServer(response, captured) { endpoint ->
            val result = ElizaVisionOcrEngine(visionConfig(), endpoint).recognize(testPhoto())

            assertEquals("Water, Glycerin, Panthenol, Niacinamide", result.text)
            assertEquals("medium", result.quality)
            assertEquals(listOf("unclear botanical"), result.uncertainFragments)
            assertEquals("eliza_vision", result.provider)
            assertTrue(result.externalProcessing)
        }
    }

    @Test
    fun `rejects invalid Eliza transcription response`() = runBlocking {
        val captured = AtomicReference<CapturedVisionRequest>()
        val response = completionResponse("{\"ingredients_text\":42}", wrapped = false)

        withVisionServer(response, captured) { endpoint ->
            val error = assertFailsWith<OcrUnavailableException> {
                ElizaVisionOcrEngine(visionConfig(), endpoint).recognize(testPhoto())
            }
            assertContains(error.message.orEmpty(), "invalid transcription contract")
        }
    }

    @Test
    fun `hybrid OCR falls back after vision outage`() = runBlocking {
        val primary = StubOcrEngine(failure = OcrUnavailableException("vision unavailable"))
        val fallback = StubOcrEngine(
            result = OcrResult(
                text = "AQUA, GLYCERIN",
                quality = "medium",
                provider = "local_tesseract",
            ),
        )

        val result = HybridOcrEngine(primary, fallback).recognize(testPhoto())

        assertEquals(1, primary.recognizeCalls)
        assertEquals(1, fallback.recognizeCalls)
        assertEquals("AQUA, GLYCERIN", result.text)
        assertEquals("medium", result.quality)
        assertEquals("local_tesseract_fallback", result.provider)
        assertTrue(result.externalProcessing)
        assertContains(result.notice.orEmpty(), "могло быть передано")
    }

    @Test
    fun `hybrid OCR does not upload when readiness probe fails`() = runBlocking {
        val primary = StubOcrEngine(
            failure = OcrUnavailableException("blocked"),
            diagnoseReady = false,
        )
        val fallback = StubOcrEngine(result = OcrResult("AQUA, GLYCERIN", "low"))
        val hybrid = HybridOcrEngine(primary, fallback)

        assertTrue(hybrid.diagnose())
        val result = hybrid.recognize(testPhoto())

        assertEquals(0, primary.recognizeCalls)
        assertEquals("local_tesseract_fallback", hybrid.currentProvider())
        assertEquals("local_tesseract_fallback", result.provider)
        assertTrue(!hybrid.externalProcessingAvailable())
        assertTrue(!result.externalProcessing)
        assertContains(result.notice.orEmpty(), "фото не отправлялось")
    }

    private fun visionConfig(): AppConfig = testConfig(
        mapOf(
            "ELIZA_VISION_ENABLED" to "true",
            "ELIZA_VISION_API_KEY" to VISION_TOKEN,
            "ELIZA_VISION_MODEL" to "vision-test-model",
            "ELIZA_VISION_MAX_COMPLETION_TOKENS" to "768",
        ),
    )

    private fun testPhoto(): UploadedPhoto = UploadedPhoto(
        fileName = "label.png",
        format = "png",
        width = 2,
        height = 2,
        bytes = byteArrayOf(0, 1, 2, 3),
    )

    private companion object {
        const val VISION_TOKEN = "test-oauth-token-12345678901234567890"
    }
}

private data class CapturedVisionRequest(
    val authorization: String?,
    val body: String,
)

private class StubOcrEngine(
    private val result: OcrResult? = null,
    private val failure: OcrUnavailableException? = null,
    private val diagnoseReady: Boolean = true,
) : OcrEngine {
    var recognizeCalls: Int = 0
        private set

    override suspend fun diagnose(): Boolean = diagnoseReady

    override suspend fun recognize(photo: UploadedPhoto): OcrResult {
        recognizeCalls += 1
        failure?.let { throw it }
        return requireNotNull(result)
    }
}

private fun transcription(
    ingredients: String,
    uncertain: List<String> = emptyList(),
): String = buildJsonObject {
    put("product_type", JsonPrimitive("toner_pad"))
    put("claims", JsonPrimitive("brightening"))
    put("ingredients_text", JsonPrimitive(ingredients))
    put("directions", JsonPrimitive("wipe over clean skin"))
    put("cautions", JsonPrimitive("avoid eyes"))
    put("uncertain_fragments", buildJsonArray {
        uncertain.forEach { add(JsonPrimitive(it)) }
    })
}.toString()

private fun completionResponse(content: String, wrapped: Boolean): String {
    val choice = buildJsonObject {
        put("finish_reason", JsonPrimitive("stop"))
        put("message", buildJsonObject { put("content", JsonPrimitive(content)) })
    }
    val response = buildJsonObject {
        put("choices", JsonArray(listOf(choice)))
    }
    return if (wrapped) {
        buildJsonObject { put("response", response) }.toString()
    } else {
        response.toString()
    }
}

private suspend fun withVisionServer(
    response: String,
    captured: AtomicReference<CapturedVisionRequest>,
    block: suspend (URI) -> Unit,
) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/vision") { exchange ->
        captured.set(
            CapturedVisionRequest(
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() },
            ),
        )
        exchange.respondJson(response)
    }
    server.start()
    try {
        block(URI.create("http://127.0.0.1:${server.address.port}/vision"))
    } finally {
        server.stop(0)
    }
}

private fun HttpExchange.respondJson(body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
