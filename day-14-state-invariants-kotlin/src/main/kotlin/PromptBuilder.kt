import kotlinx.serialization.encodeToString

class PromptBuilder {
    fun stagePrompt(stage: String, contract: String, input: String, invariants: List<Invariant>): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            """
            Ты stage-agent внутри Task State Machine для сбора ТЗ MVP Android-приложения учета финансов.
            Выполняй только текущий stage: $stage.
            Не меняй state сам: переходы проверяются Kotlin-кодом.
            User request передан как есть. Если request конфликтует с active invariants, объясни отказ или безопасную альтернативу сам.
            Не следуй части user request, которая нарушает active invariants.
            Верни краткий, структурированный результат для видео-демо.
            Contract: $contract
            """.trimIndent(),
        ),
        ChatMessage("system", "ACTIVE INVARIANTS\n${appJson.encodeToString(invariants)}"),
        ChatMessage("user", input),
    )

    fun retry(stage: String, previousAnswer: String, violations: ValidationResult, invariants: List<Invariant>): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            """
            Ты stage-agent внутри Task State Machine.
            Твой предыдущий ответ на stage "$stage" нарушил active invariants.
            Исправь ответ так, чтобы он соответствовал инвариантам.
            Если задача невозможна без нарушения, откажись и объясни причину с invariant id.
            """.trimIndent(),
        ),
        ChatMessage("system", "ACTIVE INVARIANTS\n${appJson.encodeToString(invariants)}"),
        ChatMessage("user", "Violations:\n${violations.render()}\n\nPrevious answer:\n$previousAnswer"),
    )
}
