import kotlinx.serialization.encodeToString

class PromptBuilder {
    fun stateAgent(agentName: String, ownedState: String, task: String, invariants: List<Invariant>, context: String): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            """
            Ты $agentName внутри controlled task lifecycle.
            Ты отвечаешь только за state slice "$ownedState".
            Переходы между состояниями делает только Kotlin-код.
            Соблюдай инварианты. Не предлагай Java, backend для MVP, хардкод секретов.
            Если проверяешь соблюдение запрета, не повторяй forbidden keyword в PASS-тексте; пиши "запрещенный стек отсутствует".
            Не используй Java NIO как формулировку; пиши Kotlin/JVM file API или Files API.
            Верни краткий структурированный результат для demo video.
            """.trimIndent(),
        ),
        ChatMessage("system", "ACTIVE INVARIANTS\n${appJson.encodeToString(invariants)}"),
        ChatMessage("user", "TASK:\n$task\n\nCONTEXT:\n$context"),
    )
}
