import kotlinx.serialization.encodeToString

class PromptBuilder {
    fun stage(agentName: String, task: String, invariants: List<Invariant>, context: String): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            """
            Ты $agentName внутри controlled task lifecycle.
            Переходы между состояниями делает только Kotlin-код.
            Соблюдай инварианты. Не предлагай Java, backend для MVP, хардкод секретов.
            Верни краткий структурированный результат для demo video.
            """.trimIndent(),
        ),
        ChatMessage("system", "ACTIVE INVARIANTS\n${appJson.encodeToString(invariants)}"),
        ChatMessage("user", "TASK:\n$task\n\nCONTEXT:\n$context"),
    )
}
