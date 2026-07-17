package ru.ai.course.day32.codereview

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElizaLlmClientTest {
    @Test
    fun `client unwraps Eliza response envelope without exposing authorization`() {
        val root = Files.createTempDirectory("day32-eliza-")
        val config = testConfig(root)
        var captured: HttpCall? = null
        val transport = HttpTransport { call ->
            captured = call
            HttpResult(
                200,
                mapOf("content-type" to listOf("application/json")),
                """
                    {
                      "response": {
                        "model": "fixture-model",
                        "choices": [{"message": {"content": "{\"findings\":[]}"}}],
                        "usage": {"prompt_tokens": 12, "completion_tokens": 3}
                      }
                    }
                """.trimIndent(),
            )
        }
        val prompt = PromptPack("system", "user", "preview", 20, 100, false)

        val reply = ElizaLlmClient(config, transport).generate(prompt)

        assertEquals("fixture-model", reply.model)
        assertEquals("""{"findings":[]}""", reply.content)
        assertEquals(12, reply.promptTokens)
        assertEquals(3, reply.completionTokens)
        assertTrue(captured?.headers?.get("Authorization")?.startsWith("OAuth ") == true)
    }
}
