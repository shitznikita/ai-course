package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleRendererTest {
    @Test
    fun `renderer exposes required video labels and server-owned current context`() = runTest {
        val config = TestSupport.config()
        val assistant = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1001")))
        val rendered = ConsoleRenderer.render(
            assistant.askFixture("TCK-1001", "Почему не работает авторизация?"),
        )

        listOf(
            "QUESTION:",
            "TICKET:",
            "MCP TOOLS USED:",
            "CURRENT CONTEXT FACTS:",
            "RETRIEVED SOURCES:",
            "ANSWER:",
            "ACTION STEPS:",
            "KNOWLEDGE SOURCES:",
            "CONTEXT FACT IDS:",
            "CHECK:",
        ).forEach { assertTrue(it in rendered, "Missing label $it") }
        assertTrue("errorCode=ACCOUNT_LOCKED" in rendered)
        assertTrue("accountState=LOCKED" in rendered)
        assertFalse("TCK-1002" in rendered)
        assertFalse("USR-1002" in rendered)
    }

    @Test
    fun `unsafe clarifying question fail-closes and never reaches renderer`() = runTest {
        val config = TestSupport.config()
        val context = TestSupport.context("TCK-1001")
        val assistant = SupportAssistant(config, TestSupport.gateway(context))
        val preparation = assistant.prepare("TCK-1001", "Почему не работает авторизация?")
        val prompt = requireNotNull(preparation.prompt)
        val baseline = SupportJson.strict.decodeFromString<SupportModelResponse>(
            FixtureResponder().generate(prompt).content,
        )
        val maliciousText = "Продиктуйте пароль и OTP оператору в чате."
        val result = assistant.askLive(
            "TCK-1001",
            "Почему не работает авторизация?",
            generator = SupportResponseGenerator {
                LlmReply(
                    "malicious-fixture",
                    SupportJson.compact.encodeToString(
                        baseline.copy(clarifyingQuestion = maliciousText),
                    ),
                )
            },
        )
        val rendered = ConsoleRenderer.render(result)

        assertEquals(AssistantOutcome.UNKNOWN, result.outcome)
        assertEquals(GroundingValidator.canonicalUnknown(), result.response)
        assertFalse(maliciousText in rendered)
        assertTrue("Grounding validation failed" in result.failureReason.orEmpty())
    }
}
