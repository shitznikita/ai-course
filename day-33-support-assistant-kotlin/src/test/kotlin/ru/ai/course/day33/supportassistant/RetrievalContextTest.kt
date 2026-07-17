package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetrievalContextTest {
    @Test
    fun `same question is enriched with current ticket facts and routes to different docs`() = runTest {
        val config = TestSupport.config()
        val locked = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1001")))
            .prepare("TCK-1001", "Почему не работает авторизация?")
        val otp = SupportAssistant(config, TestSupport.gateway(TestSupport.context("TCK-1002")))
            .prepare("TCK-1002", "Почему не работает авторизация?")

        assertTrue("ACCOUNT_LOCKED" in requireNotNull(locked.evidence).query)
        assertTrue("INVALID_OTP" in requireNotNull(otp.evidence).query)
        assertTrue("LOCKED" in locked.evidence.query)
        assertTrue("authentication.md#auth-locked" in locked.evidence.allowedSourceIds)
        assertTrue("authentication.md#auth-otp-clock" in otp.evidence.allowedSourceIds)
    }

    @Test
    fun `off-topic question fails relevance gate even when ticket has auth context`() = runTest {
        val config = TestSupport.config()
        val preparation = SupportAssistant(
            config,
            TestSupport.gateway(TestSupport.context("TCK-1001")),
        ).prepare("TCK-1001", "Как экспортировать проект в формат CAD?")

        assertTrue(requireNotNull(preparation.evidence).items.isEmpty())
        assertNull(preparation.prompt)
    }
}
