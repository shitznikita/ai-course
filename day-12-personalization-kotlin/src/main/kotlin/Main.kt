fun main(args: Array<String>) {
    val config = AppConfig.load()
    val tokenCounter = ApproximateTokenCounter()
    val memoryManager = MemoryManager(config)
    val profileManager = UserProfileManager(config)
    val promptBuilder = PromptBuilder(tokenCounter, config.shortTermMessagesLimit)
    val llmClient = LlmClient(config)
    val agent = PersonalizedAgent(memoryManager, profileManager, promptBuilder, llmClient)

    when (args.firstOrNull() ?: "demo") {
        "demo" -> DemoRunner(memoryManager, profileManager, promptBuilder, agent).run()
        "interactive" -> InteractiveCli(memoryManager, profileManager, promptBuilder, agent).run()
        else -> {
            println("Usage: demo | interactive")
        }
    }
}
