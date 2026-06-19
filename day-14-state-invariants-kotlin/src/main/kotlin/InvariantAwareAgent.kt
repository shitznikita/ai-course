class InvariantAwareAgent(
    private val config: AppConfig,
    private val store: InvariantStore,
    private val checker: InvariantChecker,
    private val judge: LLMInvariantJudge,
    private val promptBuilder: PromptBuilder,
    private val llmClient: LlmClient,
) {
    fun ask(userRequest: String): AgentResult {
        store.ensureSeed()
        val invariants = store.active()
        val deterministicRequest = checker.check(userRequest, "user_request", invariants)
        if (!deterministicRequest.valid) {
            return AgentResult(
                response = refusal(deterministicRequest),
                requestValidation = deterministicRequest,
                responseValidation = null,
                activeInvariants = invariants,
                llmCalled = false,
            )
        }

        val requestValidation = if (config.checkerMode in setOf("llm", "combined")) {
            val llmCheck = judge.judge(userRequest, "user_request", invariants)
            if (!llmCheck.valid) llmCheck else deterministicRequest
        } else {
            deterministicRequest
        }
        if (!requestValidation.valid) {
            return AgentResult(refusal(requestValidation), requestValidation, null, invariants, llmCalled = true)
        }

        val raw = llmClient.chat(promptBuilder.build(userRequest, invariants))
        val firstAnswer = raw.warningOrError ?: raw.content
        val responseCheck = checker.check(firstAnswer, "assistant_response", invariants)
        if (responseCheck.valid) {
            return AgentResult(firstAnswer, requestValidation, responseCheck, invariants, llmCalled = true)
        }

        val retry = llmClient.chat(promptBuilder.retry(firstAnswer, responseCheck, invariants))
        val retryAnswer = retry.warningOrError ?: retry.content
        val retryCheck = checker.check(retryAnswer, "assistant_response_retry", invariants)
        return if (retryCheck.valid) {
            AgentResult(retryAnswer, requestValidation, retryCheck, invariants, llmCalled = true)
        } else {
            AgentResult(
                response = "Не могу показать ответ: черновик нарушил инварианты даже после retry.\n${retryCheck.render()}",
                requestValidation = requestValidation,
                responseValidation = retryCheck,
                activeInvariants = invariants,
                llmCalled = true,
            )
        }
    }

    private fun refusal(validation: ValidationResult): String {
        val first = validation.violations.firstOrNull()
        val alternative = when (first?.invariantId) {
            "stack_kotlin_only" -> "Могу показать эквивалентное решение на Kotlin."
            "no_backend_mvp" -> "Могу предложить локальное хранение через Room и экспорт CSV."
            "no_hardcoded_api_keys" -> "Используй .env или переменные окружения."
            "no_profanity" -> "Могу предложить нейтральную альтернативу без токсичности."
            else -> "Могу предложить вариант, который не нарушает активные инварианты."
        }
        return "Не могу выполнить запрос: он конфликтует с инвариантом ${first?.invariantId ?: "unknown"}. ${first?.message ?: ""}\n$alternative"
    }
}
