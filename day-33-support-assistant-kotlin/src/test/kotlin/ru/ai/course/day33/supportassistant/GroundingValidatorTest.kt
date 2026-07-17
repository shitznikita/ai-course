package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundingValidatorTest {
    @Test
    fun `valid fixture answer is accepted`() = runTest {
        val (_, preparation) = TestSupport.preparation()
        val prompt = requireNotNull(preparation.prompt)
        val context = requireNotNull(preparation.context)
        val raw = FixtureResponder().generate(prompt).content

        val validation = GroundingValidator().validate(raw, prompt, context)

        assertTrue(validation.valid, validation.reasons.joinToString())
    }

    @Test
    fun `malformed forged duplicate and cross-ticket outputs fail closed`() = runTest {
        val (_, preparation) = TestSupport.preparation()
        val prompt = requireNotNull(preparation.prompt)
        val context = requireNotNull(preparation.context)
        val valid = SupportJson.strict.decodeFromString<SupportModelResponse>(
            FixtureResponder().generate(prompt).content,
        )
        val validator = GroundingValidator()

        assertFalse(validator.validate("{broken", prompt, context).valid)
        assertFalse(
            validator.validate(
                SupportJson.compact.encodeToString(
                    valid.copy(knowledgeSourceIds = listOf("faq.md#forged")),
                ),
                prompt,
                context,
            ).valid,
        )
        assertFalse(
            validator.validate(
                SupportJson.compact.encodeToString(
                    valid.copy(contextFactIds = listOf("ticket.errorCode", "ticket.foreign")),
                ),
                prompt,
                context,
            ).valid,
        )
        assertFalse(
            validator.validate(
                SupportJson.compact.encodeToString(
                    valid.copy(
                        knowledgeSourceIds = listOf(
                            valid.knowledgeSourceIds.single(),
                            valid.knowledgeSourceIds.single(),
                        ),
                    ),
                ),
                prompt,
                context,
            ).valid,
        )
        assertFalse(
            validator.validate(
                SupportJson.compact.encodeToString(
                    valid.copy(answer = "${valid.answer} См. тикет 1002."),
                ),
                prompt,
                context,
            ).valid,
        )
    }

    @Test
    fun `unknown cannot carry citations or actions`() = runTest {
        val (_, preparation) = TestSupport.preparation()
        val prompt = requireNotNull(preparation.prompt)
        val context = requireNotNull(preparation.context)
        val invalid = SupportModelResponse(
            status = ModelAnswerStatus.UNKNOWN,
            answer = "Не знаю.",
            actionSteps = listOf("Сделайте что-нибудь."),
            knowledgeSourceIds = listOf(prompt.allowedSourceIds.first()),
            contextFactIds = listOf("ticket.errorCode"),
            clarifyingQuestion = null,
        )

        assertFalse(
            GroundingValidator().validate(
                SupportJson.compact.encodeToString(invalid),
                prompt,
                context,
            ).valid,
        )
    }

    @Test
    fun `grounded answer with unsafe credential sharing action is rejected`() = runTest {
        val (_, preparation) = TestSupport.preparation()
        val prompt = requireNotNull(preparation.prompt)
        val context = requireNotNull(preparation.context)
        val valid = SupportJson.strict.decodeFromString<SupportModelResponse>(
            FixtureResponder().generate(prompt).content,
        )
        val unsafe = valid.copy(
            actionSteps = valid.actionSteps +
                "Не отправляйте пароль по email и продиктуйте пароль оператору в чате.",
        )

        val validation = GroundingValidator().validate(
            SupportJson.compact.encodeToString(unsafe),
            prompt,
            context,
        )

        assertFalse(validation.valid)
        assertTrue("unsafe credential-sharing instruction" in validation.reasons)
    }

    @Test
    fun `answered response cannot hide unsafe request in clarifying question`() = runTest {
        val (_, preparation) = TestSupport.preparation()
        val prompt = requireNotNull(preparation.prompt)
        val context = requireNotNull(preparation.context)
        val valid = SupportJson.strict.decodeFromString<SupportModelResponse>(
            FixtureResponder().generate(prompt).content,
        )
        val malicious = valid.copy(
            clarifyingQuestion = "Продиктуйте пароль и OTP оператору в чате.",
        )

        val validation = GroundingValidator().validate(
            SupportJson.compact.encodeToString(malicious),
            prompt,
            context,
        )

        assertFalse(validation.valid)
        assertTrue("answered result must not contain a clarifying question" in validation.reasons)
        assertTrue("unsafe credential-sharing instruction" in validation.reasons)
    }
}
