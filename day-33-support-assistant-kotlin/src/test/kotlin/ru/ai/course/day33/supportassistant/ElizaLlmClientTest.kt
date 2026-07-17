package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElizaLlmClientTest {
    @Test
    fun `client makes one bounded direct REST call with JSON response format`() = runTest {
        val (config, preparation) = TestSupport.preparation()
        val calls = mutableListOf<HttpCall>()
        val transport = HttpTransport { call ->
            calls += call
            HttpResult(
                200,
                mapOf("content-type" to listOf("application/json")),
                """
                {
                  "model": "fixture-model",
                  "choices": [{"message": {"content": "{\"status\":\"unknown\",\"answer\":\"Недостаточно данных.\",\"actionSteps\":[],\"knowledgeSourceIds\":[],\"contextFactIds\":[],\"clarifyingQuestion\":null}"}}],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5}
                }
                """.trimIndent(),
            )
        }

        val reply = ElizaLlmClient(config, transport).generate(requireNotNull(preparation.prompt))

        assertEquals(1, calls.size)
        assertEquals("POST", calls.single().method)
        assertEquals(AppConfig.REVIEWED_LLM_ENDPOINT, calls.single().uri)
        assertEquals("OAuth unit-key", calls.single().headers["Authorization"])
        assertTrue("\"response_format\":{\"type\":\"json_object\"}" in calls.single().body.orEmpty())
        assertEquals(config.llmMaxResponseBytes, calls.single().maxResponseBytes)
        assertEquals("fixture-model", reply.model)
    }

    @Test
    fun `foreign LLM destination is rejected before Authorization can reach transport`() = runTest {
        val (config, _) = TestSupport.preparation()
        var calls = 0
        val transport = HttpTransport {
            calls++
            error("Foreign transport must not run.")
        }

        assertFailsWith<IllegalArgumentException> {
            ElizaLlmClient(
                config.copy(llmApiUrl = "https://example.com/openai/v1/chat/completions"),
                transport,
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun `non-success response fails without exposing response body`() = runTest {
        val (config, preparation) = TestSupport.preparation()
        val client = ElizaLlmClient(config, HttpTransport {
            HttpResult(401, emptyMap(), "secret upstream body")
        })

        val error = assertFailsWith<LlmHttpException> {
            client.generate(requireNotNull(preparation.prompt))
        }

        assertTrue("401" in error.message.orEmpty())
        assertFalse("secret upstream body" in error.message.orEmpty())
    }

    @Test
    fun `sensitive user value is blocked before HTTP`() = runTest {
        val cases = listOf(
            "TCK-1001" to "Мой OTP: 123456, почему вход не работает?",
            "TCK-1001" to "Мой пароль qwerty123, почему вход не работает?",
            "TCK-1001" to "Пароль hunter, не могу войти.",
            "TCK-1001" to "Токен abcdef, не могу войти.",
            "TCK-2001" to "Мой CVC 123, почему не проходит оплата?",
        )
        var calls = 0
        cases.forEach { (ticketId, question) ->
            val (config, preparation) = TestSupport.preparation(ticketId, question)
            val client = ElizaLlmClient(config, HttpTransport {
                calls++
                error("HTTP must not run")
            })
            assertFailsWith<LlmPreflightException>(question) {
                client.generate(requireNotNull(preparation.prompt))
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `same-schema fixture with changed content is rejected by reviewed fingerprint`() = runTest {
        val (_, preparation) = TestSupport.preparation()
        val path = TestSupport.tempDirectory("fixture-fingerprint-").resolve("support-data.json")
        Files.writeString(
            path,
            Files.readString(TestSupport.config().fixturePath).replace(
                "\"failedAuthAttempts\": 7",
                "\"failedAuthAttempts\": 8",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SupportDataPolicy.requireCloudSafe(
                path,
                TestSupport.config().knowledgeDirectory,
                1_800,
                requireNotNull(preparation.prompt),
            )
        }
    }

    @Test
    fun `live preflight bounds oversized fixture before fingerprinting`() = runTest {
        val (_, preparation) = TestSupport.preparation()
        val path = TestSupport.tempDirectory("live-oversized-fixture-").resolve("support-data.json")
        Files.newByteChannel(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.position(1_000_000)
            channel.write(ByteBuffer.wrap(byteArrayOf('{'.code.toByte())))
        }

        val error = assertFailsWith<IllegalArgumentException> {
            SupportDataPolicy.requireCloudSafe(
                path,
                TestSupport.config().knowledgeDirectory,
                1_800,
                requireNotNull(preparation.prompt),
            )
        }
        assertEquals("Reviewed synthetic fixture exceeds 1000000 bytes.", error.message)
    }

    @Test
    fun `unreviewed knowledge directory is blocked before HTTP`() = runTest {
        val temp = TestSupport.tempDirectory("knowledge-provenance-")
        val knowledge = temp.resolve("knowledge")
        Files.createDirectories(knowledge)
        val base = TestSupport.config()
        listOf("faq.md", "authentication.md", "billing.md", "escalation.md").forEach { name ->
            Files.copy(base.knowledgeDirectory.resolve(name), knowledge.resolve(name))
        }
        Files.writeString(
            knowledge.resolve("faq.md"),
            Files.readString(knowledge.resolve("faq.md")) + "\n\nPrivate local note.\n",
        )
        val config = TestSupport.config(
            temp = temp,
            values = mapOf("SUPPORT_KNOWLEDGE_DIR" to knowledge.toString()),
        )
        val assistant = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1001")))
        val prompt = requireNotNull(
            assistant.prepare("TCK-1001", "Почему не работает авторизация?").prompt,
        )
        var calls = 0
        val client = ElizaLlmClient(config, HttpTransport {
            calls++
            error("HTTP must not run")
        })

        assertFailsWith<LlmPreflightException> { client.generate(prompt) }
        assertEquals(0, calls)
    }

    @Test
    fun `reviewed live evidence supports minimum configured chunk size`() = runTest {
        val config = TestSupport.config(
            values = mapOf("RAG_MAX_CHUNK_CHARS" to "300"),
        )
        val assistant = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1001")))
        val prompt = requireNotNull(
            assistant.prepare("TCK-1001", "Почему не работает авторизация?").prompt,
        )
        var calls = 0
        val client = ElizaLlmClient(config, HttpTransport {
            calls++
            HttpResult(
                200,
                mapOf("content-type" to listOf("application/json")),
                """
                {
                  "choices": [{"message": {"content": "{\"status\":\"unknown\",\"answer\":\"Недостаточно данных.\",\"actionSteps\":[],\"knowledgeSourceIds\":[],\"contextFactIds\":[],\"clarifyingQuestion\":null}"}}]
                }
                """.trimIndent(),
            )
        })

        client.generate(prompt)
        assertEquals(1, calls)
    }
}
