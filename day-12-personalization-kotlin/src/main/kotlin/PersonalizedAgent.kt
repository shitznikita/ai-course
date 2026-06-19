class PersonalizedAgent(
    private val memoryManager: MemoryManager,
    private val profileManager: UserProfileManager,
    private val promptBuilder: PromptBuilder,
    private val llmClient: LlmClient,
) {
    fun ask(userMessage: String): AgentAnswer {
        val profile = profileManager.activeProfile()
        val snapshot = memoryManager.snapshot()
        val prompt = promptBuilder.build(snapshot, profile, userMessage)
        memoryManager.appendShort(ChatMessage("user", userMessage))
        val response = llmClient.chat(prompt.messagesForApi)
        if (response.content.isNotBlank()) {
            memoryManager.appendShort(ChatMessage("assistant", response.content))
        }
        return AgentAnswer(response.content, prompt, response.usage, response.warningOrError)
    }

    fun askAs(profile: UserProfile, userMessage: String): AgentAnswer {
        val prompt = promptBuilder.build(memoryManager.snapshot(), profile, userMessage)
        val response = llmClient.chat(prompt.messagesForApi)
        return AgentAnswer(response.content, prompt, response.usage, response.warningOrError)
    }
}
