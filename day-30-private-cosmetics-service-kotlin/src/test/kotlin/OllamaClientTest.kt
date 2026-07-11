import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OllamaClientTest {
    @Test
    fun `sends bounded direct REST payload to loopback Ollama`() {
        val captured = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            captured.set(exchange.requestBody.bufferedReader().use { it.readText() })
            exchange.json(
                """{"model":"qwen3:4b","message":{"role":"assistant","content":"{\"status\":\"answered\"}"},"done":true,"prompt_eval_count":10,"eval_count":4,"eval_duration":1000000}""",
            )
        }
        server.start()
        try {
            val config = testConfig(
                mapOf(
                    "OLLAMA_BASE_URL" to "http://127.0.0.1:${server.address.port}",
                    "OLLAMA_CONTEXT_LENGTH" to "4096",
                    "OLLAMA_MAX_OUTPUT_TOKENS" to "400",
                ),
            )
            val reply = OllamaClient(config).chat("system", "user", PromptBuilder.chatSchema())
            val payload = AppJson.strict.parseToJsonElement(captured.get()).jsonObject

            assertEquals("qwen3:4b", payload["model"]?.jsonPrimitive?.content)
            assertEquals(false, payload["think"]?.jsonPrimitive?.content?.toBooleanStrict())
            val options = payload["options"]?.jsonObject
            assertEquals("4096", options?.get("num_ctx")?.jsonPrimitive?.content)
            assertEquals("400", options?.get("num_predict")?.jsonPrimitive?.content)
            assertTrue(payload.containsKey("format"))
            assertFalse(captured.get().contains("Authorization", ignoreCase = true))
            assertEquals("qwen3:4b", reply.model)
        } finally {
            server.stop(0)
        }
    }
}

private fun HttpExchange.json(body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
