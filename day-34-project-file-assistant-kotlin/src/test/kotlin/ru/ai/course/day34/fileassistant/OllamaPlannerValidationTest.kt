package ru.ai.course.day34.fileassistant

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OllamaPlannerValidationTest {
    @Test
    fun `unexpected finish arguments enter invalid-plan retry and then recover`() = withStub(
        invalidFinish(),
        validFinish("Recovered after validation feedback."),
    ) { url, requests ->
        val planner = OllamaPlanner(TestSupport.config().copy(ollamaUrl = url))

        val result = runBlocking {
            ProjectFileAssistant(NoCallGateway(), planner).run("Finish only after strict action validation")
        }

        assertEquals("Recovered after validation feedback.", result.finishSummary)
        assertEquals(2, requests.get())
        assertEquals(2, planner.llmCalls)
    }

    @Test
    fun `unexpected tool arguments fail after two invalid plans without tool execution`() = withStub(
        invalidListCall(),
        invalidListCall(),
    ) { url, requests ->
        val gateway = NoCallGateway()
        val planner = OllamaPlanner(TestSupport.config().copy(ollamaUrl = url))

        val error = assertFailsWith<IllegalStateException> {
            runBlocking {
                ProjectFileAssistant(gateway, planner).run("Discover project files with validated arguments")
            }
        }

        assertTrue("two invalid plans" in error.message.orEmpty())
        assertEquals(2, requests.get())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `unknown tool fails after two invalid plans without MCP call`() = withStub(
        unknownToolCall(),
        unknownToolCall(),
    ) { url, requests ->
        val gateway = NoCallGateway()
        val planner = OllamaPlanner(TestSupport.config().copy(ollamaUrl = url))

        val error = assertFailsWith<IllegalStateException> {
            runBlocking {
                ProjectFileAssistant(gateway, planner).run("Use only tools discovered from the MCP registry")
            }
        }

        assertTrue("Tool was not discovered" in error.message.orEmpty())
        assertEquals(2, requests.get())
        assertEquals(0, gateway.calls)
    }

    private fun invalidFinish(): JsonObject = action(
        type = "finish",
        tool = "none",
        arguments = buildJsonObject { put("unexpected", JsonPrimitive(true)) },
        summary = "Should be rejected.",
    )

    private fun validFinish(summary: String): JsonObject = action(
        type = "finish",
        tool = "none",
        arguments = JsonObject(emptyMap()),
        summary = summary,
    )

    private fun invalidListCall(): JsonObject = action(
        type = "tool_call",
        tool = FileTool.LIST_FILES,
        arguments = buildJsonObject {
            put("limit", JsonPrimitive(20))
            put("unexpected", JsonPrimitive("must survive decoding"))
        },
        summary = "",
    )

    private fun unknownToolCall(): JsonObject = action(
        type = "tool_call",
        tool = "project_delete_file",
        arguments = JsonObject(emptyMap()),
        summary = "",
    )

    private fun action(
        type: String,
        tool: String,
        arguments: JsonObject,
        summary: String,
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("tool", JsonPrimitive(tool))
        put("arguments", arguments)
        put("summary", JsonPrimitive(summary))
    }

    private fun withStub(
        vararg actions: JsonObject,
        block: (url: String, requests: AtomicInteger) -> Unit,
    ) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            val index = requests.getAndIncrement()
            val action = actions[minOf(index, actions.lastIndex)]
            val response = buildJsonObject {
                put("message", buildJsonObject {
                    put("content", JsonPrimitive(action.toString()))
                })
            }.toString().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/api/chat", requests)
        } finally {
            server.stop(0)
        }
    }

    private class NoCallGateway : FileToolGateway {
        var calls: Int = 0

        override suspend fun tools(): Set<String> = FileTool.expected

        override suspend fun call(tool: String, arguments: JsonObject): JsonObject {
            calls++
            error("Invalid plans must not reach MCP.")
        }

        override fun snapshot(): SessionSummary = SessionSummary(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", sha256(""),
        )
    }
}
