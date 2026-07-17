package ru.ai.course.day33.supportassistant

import kotlinx.serialization.encodeToString
import kotlin.math.abs

class FixtureResponder {
    fun generate(prompt: PromptPack): LlmReply {
        val input = prompt.transmittedInput
        val facts = input.contextFacts.associate { it.id to it.value }
        val errorCode = facts["ticket.errorCode"]
        val response = when {
            errorCode == "ACCOUNT_LOCKED" && input.question.isAuthenticationQuestion() ->
                answered(
                    prompt = prompt,
                    preferredSource = "authentication.md#auth-locked",
                    answer = "Для текущего тикета причина входа — защитная блокировка ACCOUNT_LOCKED после неуспешных попыток.",
                    actions = listOf(
                        "Не повторяйте вход 15 минут.",
                        "Затем используйте «Сбросить пароль» и войдите с новым паролем.",
                        "Если блокировка сохранится, передайте ID текущего тикета в линию Identity.",
                    ),
                    factIds = listOf(
                        "ticket.errorCode",
                        "ticket.failedAuthAttempts",
                        "user.accountState",
                    ),
                )

            errorCode == "INVALID_OTP" &&
                abs(facts["ticket.deviceClockSkewSeconds"]?.toIntOrNull() ?: 0) > 120 &&
                input.question.isAuthenticationQuestion() ->
                answered(
                    prompt = prompt,
                    preferredSource = "authentication.md#auth-otp-clock",
                    answer = "Для текущего тикета одноразовый код отклоняется из-за CLOCK_SKEW: часы устройства расходятся больше чем на 120 секунд.",
                    actions = listOf(
                        "Включите автоматические дату, время и часовой пояс.",
                        "Синхронизируйте часы и запросите новый одноразовый код.",
                        "Используйте только самый свежий код; не отправляйте его поддержке.",
                    ),
                    factIds = listOf(
                        "ticket.errorCode",
                        "ticket.deviceClockSkewSeconds",
                        "user.accountState",
                    ),
                )

            errorCode == "PAYMENT_RETRY_REQUIRED" && input.question.isBillingQuestion() ->
                answered(
                    prompt = prompt,
                    preferredSource = "billing.md#billing-retry",
                    answer = "Для текущего тикета подписка ограничена после неуспешного автоматического продления.",
                    actions = listOf(
                        "Откройте «Настройки → Подписка» и проверьте доступный способ оплаты.",
                        "Безопасно повторите платёж, не отправляя полный номер карты или CVC.",
                        "Если повтор не пройдёт, передайте ID текущего тикета в Billing.",
                    ),
                    factIds = listOf("ticket.errorCode", "user.accountState", "user.plan"),
                )

            else -> GroundingValidator.canonicalUnknown()
        }
        return LlmReply("fixture-responder", SupportJson.compact.encodeToString(response))
    }

    private fun answered(
        prompt: PromptPack,
        preferredSource: String,
        answer: String,
        actions: List<String>,
        factIds: List<String>,
    ): SupportModelResponse {
        if (preferredSource !in prompt.allowedSourceIds || factIds.any { it !in prompt.allowedFactIds }) {
            return GroundingValidator.canonicalUnknown()
        }
        return SupportModelResponse(
            status = ModelAnswerStatus.ANSWERED,
            answer = answer,
            actionSteps = actions,
            knowledgeSourceIds = listOf(preferredSource),
            contextFactIds = factIds,
            clarifyingQuestion = null,
        )
    }

    private fun String.isAuthenticationQuestion(): Boolean {
        val value = TextTools.normalize(this)
        return listOf("авторизац", "вход", "login", "otp", "одноразов", "код").any { it in value }
    }

    private fun String.isBillingQuestion(): Boolean {
        val value = TextTools.normalize(this)
        return listOf("подпис", "оплат", "payment", "billing", "продлен").any { it in value }
    }
}
