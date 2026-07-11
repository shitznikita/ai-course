import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CosmeticsWebAppTest {
    private val token = "test-token-with-at-least-24-characters"

    @Test
    fun `serves UI and cheap liveness without authentication`() = testApplication {
        val config = securedConfig()
        application { cosmeticsWebModule(config, FakeUseCases()) }

        val page = client.get("/")
        val script = client.get("/app.js")
        val live = client.get("/api/health/live")
        val health = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, page.status)
        val pageText = page.bodyAsText()
        val scriptText = script.bodyAsText()
        assertTrue(pageText.contains("Private Cosmetics AI"))
        assertTrue(pageText.contains("id=\"login-view\""))
        assertTrue(pageText.contains("id=\"app-view\" class=\"shell\" hidden"))
        assertFalse(pageText.contains("id=\"token\""))
        assertTrue(scriptText.contains("localStorage.setItem(TOKEN_STORAGE_KEY, token)"))
        assertTrue(scriptText.contains("headers.set(\"Authorization\", `Bearer ${'$'}{token}`)"))
        assertFalse(scriptText.contains("window.location.search"))
        assertFalse(scriptText.contains("window.location.hash"))
        assertEquals(HttpStatusCode.OK, live.status)
        assertEquals("alive", AppJson.strict.decodeFromString<LivenessResponse>(live.bodyAsText()).status)
        assertEquals(HttpStatusCode.Unauthorized, health.status)
        assertTrue(health.headers["Content-Security-Policy"]?.contains("frame-ancestors 'none'") == true)
        assertTrue(health.headers["Content-Security-Policy"]?.contains("form-action 'self'") == true)
    }

    @Test
    fun `returns dependency readiness with authentication`() = testApplication {
        val config = securedConfig()
        application { cosmeticsWebModule(config, FakeUseCases()) }

        val response = client.get("/api/health") { authenticated() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ready", AppJson.strict.decodeFromString<HealthResponse>(response.bodyAsText()).status)
    }

    @Test
    fun `requires bearer token on private routes`() = testApplication {
        val config = securedConfig()
        application { cosmeticsWebModule(config, FakeUseCases()) }

        val response = client.post("/api/analyze/text") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun `accepts valid text request and returns structured report`() = testApplication {
        val config = securedConfig()
        val fake = FakeUseCases()
        application { cosmeticsWebModule(config, fake) }

        val request = AnalyzeTextRequest("AQUA, GLYCERIN", "Demo serum")
        val response = client.post("/api/analyze/text") {
            authenticated()
            contentType(ContentType.Application.Json)
            setBody(AppJson.strict.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.strict.decodeFromString<AnalyzeResponse>(response.bodyAsText())
        assertEquals("session-test", body.sessionId)
        assertEquals("AQUA, GLYCERIN", fake.lastText?.inciText)
    }

    @Test
    fun `rate limit runs before JSON validation and returns Retry-After`() = testApplication {
        val config = securedConfig(mapOf("RATE_LIMIT_REQUESTS" to "1", "RATE_LIMIT_WINDOW_SECONDS" to "60"))
        application { cosmeticsWebModule(config, FakeUseCases()) }

        val first = client.post("/api/analyze/text") {
            authenticated()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val second = client.post("/api/analyze/text") {
            authenticated()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, first.status)
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertTrue(second.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it > 0 } == true)
    }

    @Test
    fun `rejects image extension spoofing by magic bytes`() = testApplication {
        val config = securedConfig()
        application { cosmeticsWebModule(config, FakeUseCases()) }

        val response = client.submitFormWithBinaryData(
            url = "/api/ocr",
            formData = formData {
                append("photo", "not a png".encodeToByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"photo\"; filename=\"label.png\"")
                    append(HttpHeaders.ContentType, "image/png")
                })
            },
        ) { authenticated() }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        val error = AppJson.strict.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("unsupported_image", error.code)
    }

    @Test
    fun `deletes in-memory session through protected endpoint`() = testApplication {
        val config = securedConfig()
        application { cosmeticsWebModule(config, FakeUseCases()) }

        val response = client.delete("/api/sessions/session-test") { authenticated() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(AppJson.strict.decodeFromString<DeleteSessionResponse>(response.bodyAsText()).deleted)
    }

    private fun securedConfig(overrides: Map<String, String> = emptyMap()): AppConfig = testConfig(
        mapOf(
            "APP_ALLOW_INSECURE_NO_AUTH" to "false",
            "APP_API_TOKEN" to token,
        ) + overrides,
    )

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

private class FakeUseCases : CosmeticsUseCases {
    var lastText: AnalyzeTextRequest? = null

    override suspend fun health(): HealthResponse = HealthResponse(
        status = "ready",
        model = "qwen3:4b",
        ollamaVersion = "test",
        modelInstalled = true,
        ocrReady = true,
        ingredientCards = 24,
        catalogProducts = 4,
    )

    override suspend fun analyzeText(request: AnalyzeTextRequest): AnalyzeResponse {
        lastText = request
        return response(request.inciText, request.productName)
    }

    override suspend fun analyzeName(request: AnalyzeNameRequest): AnalyzeResponse = response("AQUA, GLYCERIN", request.name)

    override suspend fun recognizePhoto(photo: UploadedPhoto): OcrResponse = OcrResponse(
        photo.fileName,
        photo.format,
        photo.width,
        photo.height,
        "AQUA, GLYCERIN",
        "high",
    )

    override suspend fun chat(request: ChatRequest): ChatResponse = ChatResponse(
        sessionId = request.sessionId,
        reply = ChatAnswer("answered", "После очищения.", listOf("eu-cosing"), emptyList()),
        sources = emptyList(),
        model = ModelMetrics("qwen3:4b", 10),
    )

    override fun deleteSession(id: String): Boolean = id == "session-test"

    private fun response(inci: String, name: String?): AnalyzeResponse = AnalyzeResponse(
        sessionId = "session-test",
        input = AnalysisInputSummary("text", name, inciText = inci, parsedIngredientCount = 2, recognizedIngredientCount = 2, unknownIngredients = emptyList()),
        report = CosmeticsReport(
            "answered",
            "face_serum",
            "Grounded demo report",
            listOf("dry"),
            RoutineAdvice(listOf("evening"), "serum", "Follow label", "no", listOf("eu-cosing")),
            listOf(IngredientInsight("glycerin", "humectant", listOf("eu-cosing"))),
            emptyList(),
            listOf("Concentration unknown"),
            "medium",
            listOf("eu-cosing"),
            "Not medical advice",
        ),
        sources = listOf(EvidenceSource("eu-cosing", "CosIng", "European Commission", "https://example.test")),
        model = ModelMetrics("qwen3:4b", 10),
    )
}
