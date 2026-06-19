class PromptBuilder {
    fun stagePrompt(stage: String, contract: String, input: String): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            """
            Ты stage-agent внутри Task State Machine.
            Выполняй только текущий stage: $stage.
            Не меняй state сам: переходы проверяются Kotlin-кодом.
            Верни краткий, структурированный результат для видео-демо.
            Contract: $contract
            """.trimIndent(),
        ),
        ChatMessage("user", input),
    )
}
