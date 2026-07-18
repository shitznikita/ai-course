package ru.ai.course.day34.fileassistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OllamaPlannerTest {
    @Test
    fun `request uses local chat JSON schema deterministic options and no cloud secret`() {
        val planner = OllamaPlanner(TestSupport.config())
        val request = planner.buildRequest(
            PlannerContext(
                goal = "Inspect the public API and synchronize documentation",
                step = 1,
                availableTools = FileTool.expected,
                observations = emptyList(),
            ),
        )

        assertEquals("qwen3:4b", request.getValue("model").jsonPrimitive.content)
        assertFalse(request.getValue("stream").jsonPrimitive.boolean)
        assertFalse(request.getValue("think").jsonPrimitive.boolean)
        assertEquals(0, request.getValue("options").jsonObject.getValue("temperature").jsonPrimitive.content.toInt())
        val messages = request.getValue("messages") as JsonArray
        assertEquals(2, messages.size)
        val serialized = request.toString()
        assertTrue("project_write_file" in serialized)
        assertTrue("expectedSha256" in serialized)
        assertFalse("Authorization" in serialized)
        assertFalse("api_key" in serialized.lowercase())
        val schema = request.getValue("format") as JsonObject
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        val required = schema.getValue("required") as JsonArray
        assertEquals(4, required.size)
        assertTrue("\"none\"" in schema.toString())
    }

    @Test
    fun `config rejects non-loopback model and MCP endpoints`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AppConfig(ollamaUrl = "https://example.com/api/chat")
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AppConfig(mcpHost = "example.com")
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AppConfig(ollamaUrl = "http://localhost:11434/api/chat")
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AppConfig(mcpHost = "localhost")
        }
    }

    @Test
    fun `high-token-density whole item fits context equation at boundary`() {
        val planner = OllamaPlanner(TestSupport.config())
        val unit = "漢🙂{}[]!?"
        var low = 0
        var high = 2_000
        while (low < high) {
            val middle = (low + high + 1) / 2
            val context = contextWithRead(entry(unit.repeat(middle)))
            if (runCatching { planner.buildRequest(context) }.isSuccess) low = middle else high = middle - 1
        }
        val accepted = entry(unit.repeat(low))
        val context = contextWithRead(accepted)
        val admission = planner.contextAdmission(context)
        val request = planner.buildRequest(context)
        val prompt = (request.getValue("messages") as JsonArray)[1]
            .jsonObject.getValue("content").jsonPrimitive.content

        assertTrue(accepted.content in prompt)
        assertTrue("PATH: ${accepted.path}" in prompt)
        assertTrue("BYTES: ${accepted.bytes}" in prompt)
        assertTrue("SHA-256: ${accepted.sha256}" in prompt)
        assertTrue(admission.fits)
        assertTrue(
            admission.chatContentUtf8Bytes +
                OllamaPlanner.CHAT_TEMPLATE_FRAMING_RESERVE_TOKENS +
                OllamaPlanner.NUM_PREDICT_TOKENS <= OllamaPlanner.NUM_CTX_TOKENS,
        )
        assertTrue(admission.chatContentUtf8Bytes <= OllamaPlanner.MAX_CHAT_CONTENT_UTF8_BYTES)
    }

    @Test
    fun `just over conservative byte budget fails before Ollama call`() {
        val planner = OllamaPlanner(TestSupport.config())
        val unit = "漢🙂{}[]!?"
        var repetitions = 1
        while (runCatching { planner.buildRequest(contextWithRead(entry(unit.repeat(repetitions)))) }.isSuccess) {
            repetitions *= 2
        }
        var low = repetitions / 2
        var high = repetitions
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (runCatching { planner.buildRequest(contextWithRead(entry(unit.repeat(middle)))) }.isSuccess) {
                low = middle
            } else {
                high = middle
            }
        }
        val rejectedContext = contextWithRead(entry(unit.repeat(high)))
        val admission = planner.contextAdmission(rejectedContext)

        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking { planner.next(rejectedContext) }
        }

        assertFalse(admission.fits)
        assertTrue(admission.worstCaseTotalTokens > OllamaPlanner.NUM_CTX_TOKENS)
        assertTrue("conservative context budget" in error.message.orEmpty())
        assertEquals(0, planner.llmCalls)
    }

    private fun entry(content: String): ReadFileEntry = ReadFileEntry(
        path = "docs/high-density.md",
        content = content,
        sha256 = sha256(content),
        bytes = content.toByteArray().size,
    )

    private fun contextWithRead(entry: ReadFileEntry): PlannerContext {
        val result = AppJson.strict.encodeToJsonElement(
            ReadFilesResult.serializer(),
            ReadFilesResult(listOf(entry)),
        ) as JsonObject
        return PlannerContext(
            goal = "Update documentation from the complete file",
            step = 2,
            availableTools = FileTool.expected,
            observations = listOf(
                ToolObservation(
                    step = 1,
                    tool = FileTool.READ_FILES,
                    arguments = buildJsonObject {
                        put("paths", JsonArray(listOf(JsonPrimitive(entry.path))))
                    },
                    result = result,
                    argumentsSummary = "paths=[${entry.path}]",
                    observationSummary = "${entry.path} (${entry.bytes} B)",
                ),
            ),
        )
    }
}
