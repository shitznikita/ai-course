class MemoryAgent(
    private val llmClient: LlmClient,
    private val memoryManager: MemoryManager,
    private val promptBuilder: PromptBuilder,
) {
    fun ask(userMessage: String): AgentAnswer {
        val snapshotBefore = memoryManager.snapshot()
        val prompt = promptBuilder.build(snapshotBefore, userMessage)

        memoryManager.appendShortMessage(ChatMessage("user", userMessage))
        val response = llmClient.complete(prompt.messagesForApi)
        memoryManager.appendShortMessage(ChatMessage("assistant", response.content))

        return AgentAnswer(
            answer = response.content,
            prompt = prompt,
            usage = response.usage,
            warningOrError = response.warningOrError,
        )
    }
}
