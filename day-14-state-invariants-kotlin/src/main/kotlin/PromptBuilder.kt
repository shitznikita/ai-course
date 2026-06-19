import kotlinx.serialization.encodeToString

class PromptBuilder {
    fun build(userRequest: String, invariants: List<Invariant>): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            """
            Ты InvariantAwareAgent, помощник по проектированию MVP Android-приложения учета финансов.
            Ты обязан соблюдать инварианты проекта.
            Если пользователь просит нарушить инвариант, откажись и назови конфликтующий invariant id.
            Не предлагай обходные пути, которые нарушают инвариант.
            Не хардкодь секреты, не предлагай backend для первого релиза, не предлагай Java.
            """.trimIndent(),
        ),
        ChatMessage("system", "ACTIVE INVARIANTS\n${appJson.encodeToString(invariants)}"),
        ChatMessage("user", userRequest),
    )

    fun retry(previousAnswer: String, violations: ValidationResult, invariants: List<Invariant>): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            """
            Твой предыдущий ответ нарушил инварианты. Исправь ответ так, чтобы он соответствовал им.
            Если задача невозможна без нарушения, откажись и объясни причину.
            """.trimIndent(),
        ),
        ChatMessage("system", "ACTIVE INVARIANTS\n${appJson.encodeToString(invariants)}"),
        ChatMessage("user", "Violations:\n${violations.render()}\n\nPrevious answer:\n$previousAnswer"),
    )
}
