package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SupportAssistantEndToEndTest {
    @Test
    fun `real MCP plus hash RAG gives deterministic isolated answers with zero LLM calls`() = runBlocking {
        val config = TestSupport.config()
        LazyEmbeddedSupportMcpGateway(config).use { gateway ->
            val assistant = SupportAssistant(config, gateway)
            val question = "Почему не работает авторизация?"
            val locked = assistant.askFixture("TCK-1001", question)
            val otp = assistant.askFixture("TCK-1002", question)

            assertEquals(AssistantOutcome.ANSWERED, locked.outcome)
            assertEquals(AssistantOutcome.ANSWERED, otp.outcome)
            assertEquals(0, locked.llmCalls + otp.llmCalls)
            assertNotEquals(locked.response.answer, otp.response.answer)
            assertTrue("authentication.md#auth-locked" in locked.response.knowledgeSourceIds)
            assertTrue("authentication.md#auth-otp-clock" in otp.response.knowledgeSourceIds)
            assertTrue("ticket.deviceClockSkewSeconds" !in locked.response.contextFactIds)
            assertTrue("ticket.failedAuthAttempts" !in otp.response.contextFactIds)
        }
    }

    @Test
    fun `missing ticket and weak retrieval never construct live generator`() = runTest {
        val config = TestSupport.config()
        var generatorConstructed = false
        val missingGateway = SupportContextGateway {
            McpContextFetch(
                SupportTool.REQUIRED.sorted(),
                listOf(SupportTool.GET_TICKET),
                null,
                "Ticket TCK-4040 was not found.",
            )
        }
        val missing = SupportAssistant(config, missingGateway).askLive(
            "TCK-4040",
            "Почему не работает авторизация?",
            generator = SupportResponseGenerator {
                generatorConstructed = true
                error("must not run")
            },
        )
        assertEquals(AssistantOutcome.NOT_FOUND, missing.outcome)
        assertEquals(0, missing.llmCalls)
        assertFalse(generatorConstructed)

        val context = TestSupport.context("TCK-1001")
        val weak = SupportAssistant(config, TestSupport.gateway(context)).askLive(
            "TCK-1001",
            "Как экспортировать проект в формат CAD?",
            generator = SupportResponseGenerator {
                generatorConstructed = true
                error("must not run")
            },
        )
        assertEquals(AssistantOutcome.UNKNOWN, weak.outcome)
        assertEquals(0, weak.llmCalls)
        assertNull(weak.evidence?.items?.firstOrNull())
        assertFalse(generatorConstructed)
    }

    @Test
    fun `ticket switch clears history and last evidence`() = runTest {
        val config = TestSupport.config()
        val assistant = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1001")))
        val result = assistant.askFixture("TCK-1001", "Почему не работает авторизация?")
        val state = ChatSessionState(4)
        state.record(result)

        assertTrue(state.history().isNotEmpty())
        assertTrue(state.lastEvidence != null)
        assertTrue(state.switchTicket("TCK-1002"))
        assertTrue(state.history().isEmpty())
        assertNull(state.lastEvidence)
    }

    @Test
    fun `assistant rejects externally supplied history from another ticket`() = runTest {
        val config = TestSupport.config()
        val assistant = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1002")))

        assertFailsWith<IllegalArgumentException> {
            assistant.prepare(
                "TCK-1002",
                "Почему не работает авторизация?",
                recentHistory = listOf(
                    ChatTurn("TCK-1001", "Старый вопрос", "Старый ответ"),
                ),
            )
        }
    }

    @Test
    fun `question cannot reference another ticket in current-ticket mode`() = runTest {
        val config = TestSupport.config()
        val assistant = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1001")))

        assertFailsWith<IllegalArgumentException> {
            assistant.prepare(
                "TCK-1001",
                "Сравни этот тикет с TCK‑1002.",
            )
        }
    }

    @Test
    fun `cloud preflight failure reports zero network LLM calls`() = runTest {
        val config = TestSupport.config()
        val assistant = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1001")))
        var transportCalls = 0
        val generator = ElizaLlmClient(config, HttpTransport {
            transportCalls++
            error("HTTP must not run")
        })

        val result = assistant.askLive(
            "TCK-1001",
            "Мой пароль qwerty123, почему вход не работает?",
            generator = generator,
        )

        assertEquals(0, transportCalls)
        assertEquals(0, result.llmCalls)
        assertEquals(AssistantOutcome.UNKNOWN, result.outcome)
    }
}
