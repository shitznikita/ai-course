import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotesWebAppTest {
    @Test
    fun `serves the local web interface`() = testApplication {
        application { notesWebModule(testConfig(), FakeNotesAnalyzer()) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Локальный анализатор заметок"))
    }

    @Test
    fun `analyzes a markdown upload without real Ollama`() = testApplication {
        val analyzer = FakeNotesAnalyzer()
        application { notesWebModule(testConfig(), analyzer) }

        val response = client.submitFormWithBinaryData(
            url = "/api/analyze",
            formData = multipartNote("demo.md", "# План\n- Показать локальный анализ".encodeToByteArray()),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.instance.decodeFromString<AnalyzeResponse>(response.bodyAsText())
        assertEquals("demo.md", body.source.fileName)
        assertEquals("md", body.source.format)
        assertEquals("Локальный отчёт без облака.", body.report.summary)
        assertEquals("# План\n- Показать локальный анализ", assertNotNull(analyzer.lastNote).text)
    }

    @Test
    fun `rejects an unsupported note extension`() = testApplication {
        application { notesWebModule(testConfig(), FakeNotesAnalyzer()) }

        val response = client.submitFormWithBinaryData(
            url = "/api/analyze",
            formData = multipartNote("note.pdf", "not a PDF".encodeToByteArray()),
        )

        assertApiError(response.status, response.bodyAsText(), HttpStatusCode.UnsupportedMediaType, "unsupported_format")
    }

    @Test
    fun `rejects empty notes and notes over the character limit`() = testApplication {
        application { notesWebModule(testConfig(mapOf("NOTES_MAX_CHARS" to "5")), FakeNotesAnalyzer()) }

        val empty = client.submitFormWithBinaryData(
            url = "/api/analyze",
            formData = multipartNote("empty.txt", "  \n".encodeToByteArray()),
        )
        assertApiError(empty.status, empty.bodyAsText(), HttpStatusCode.UnprocessableEntity, "empty_note")

        val tooLong = client.submitFormWithBinaryData(
            url = "/api/analyze",
            formData = multipartNote("long.txt", "123456".encodeToByteArray()),
        )
        assertApiError(tooLong.status, tooLong.bodyAsText(), HttpStatusCode.UnprocessableEntity, "note_too_long")
    }

    @Test
    fun `rejects uploads over the byte limit`() = testApplication {
        application { notesWebModule(testConfig(mapOf("NOTES_MAX_UPLOAD_BYTES" to "1024")), FakeNotesAnalyzer()) }

        val response = client.submitFormWithBinaryData(
            url = "/api/analyze",
            formData = multipartNote("large.txt", ByteArray(1_025) { 'a'.code.toByte() }),
        )

        assertApiError(response.status, response.bodyAsText(), HttpStatusCode.PayloadTooLarge, "file_too_large")
    }

    @Test
    fun `rejects missing and duplicate multipart note fields`() = testApplication {
        application { notesWebModule(testConfig(), FakeNotesAnalyzer()) }

        val missing = client.submitFormWithBinaryData(
            url = "/api/analyze",
            formData = formData {
                append("different-field", "data".encodeToByteArray())
            },
        )
        assertApiError(missing.status, missing.bodyAsText(), HttpStatusCode.BadRequest, "single_note_required")

        val duplicate = client.submitFormWithBinaryData(
            url = "/api/analyze",
            formData = formData {
                append("note", "one".encodeToByteArray(), fileHeaders("one.txt"))
                append("note", "two".encodeToByteArray(), fileHeaders("two.txt"))
            },
        )
        assertApiError(duplicate.status, duplicate.bodyAsText(), HttpStatusCode.BadRequest, "single_note_required")
    }

    @Test
    fun `returns actionable errors when local model is unavailable or missing`() = testApplication {
        val unavailable = ApiProblem(
            status = HttpStatusCode.ServiceUnavailable,
            code = "ollama_unavailable",
            message = "Локальный Ollama недоступен.",
            hint = "Запустите: ollama serve",
        )
        application { notesWebModule(testConfig(), FakeNotesAnalyzer(healthFailure = unavailable)) }

        val response = client.get("/api/health")

        assertApiError(response.status, response.bodyAsText(), HttpStatusCode.ServiceUnavailable, "ollama_unavailable")
    }

    @Test
    fun `returns an actionable missing model error`() = testApplication {
        val missingModel = ApiProblem(
            status = HttpStatusCode.ServiceUnavailable,
            code = "model_missing",
            message = "Локальная модель 'qwen3:14b' не установлена.",
            hint = "Скачайте её локально: ollama pull qwen3:14b",
        )
        application { notesWebModule(testConfig(), FakeNotesAnalyzer(healthFailure = missingModel)) }

        val response = client.get("/api/health")

        assertApiError(response.status, response.bodyAsText(), HttpStatusCode.ServiceUnavailable, "model_missing")
    }

    @Test
    fun `rejects external addresses in configuration`() {
        assertFailsWith<ConfigurationException> {
            testConfig(mapOf("OLLAMA_BASE_URL" to "https://example.com"))
        }
        assertFailsWith<ConfigurationException> {
            testConfig(mapOf("APP_HOST" to "0.0.0.0"))
        }
    }

    private fun assertApiError(status: HttpStatusCode, body: String, expectedStatus: HttpStatusCode, expectedCode: String) {
        assertEquals(expectedStatus, status)
        val error = AppJson.instance.decodeFromString<ApiError>(body)
        assertEquals(expectedCode, error.code)
    }

    private fun multipartNote(fileName: String, bytes: ByteArray) = formData {
        append(
            "note",
            bytes,
            fileHeaders(fileName),
        )
    }

    private fun fileHeaders(fileName: String): Headers = Headers.build {
        append(HttpHeaders.ContentDisposition, "form-data; name=\"note\"; filename=\"$fileName\"")
        append(HttpHeaders.ContentType, "text/plain")
    }

    private fun testConfig(overrides: Map<String, String> = emptyMap()): AppConfig = AppConfig.fromValues(
        mapOf(
            "APP_HOST" to "127.0.0.1",
            "APP_PORT" to "8787",
            "OLLAMA_BASE_URL" to "http://127.0.0.1:11434",
            "OLLAMA_MODEL" to "qwen3:14b",
        ) + overrides,
    )
}

private class FakeNotesAnalyzer(
    private val healthFailure: ApiProblem? = null,
) : NotesAnalyzer {
    var lastNote: UploadedNote? = null

    override suspend fun diagnose(): HealthResponse {
        healthFailure?.let { throw it }
        return HealthResponse(
            ready = true,
            model = "qwen3:14b",
            ollamaVersion = "test",
        )
    }

    override suspend fun analyze(note: UploadedNote): AnalyzeResponse {
        lastNote = note
        return AnalyzeResponse(
            source = SourceMetadata(note.fileName, note.format, note.charCount),
            report = AnalysisReport(
                summary = "Локальный отчёт без облака.",
                decisions = listOf("Использовать локальную модель."),
                actionItems = listOf(ActionItem(task = "Показать демо", owner = "Никита")),
                risks = emptyList(),
                openQuestions = emptyList(),
            ),
            model = ModelMetrics(name = "qwen3:14b", latencyMs = 42),
        )
    }
}
