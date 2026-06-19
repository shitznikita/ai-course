fun main(args: Array<String>) {
    val config = AppConfig.load()
    val store = InvariantStore(config.invariantsFile)
    val llmClient = LlmClient(config)
    val checker = InvariantChecker()
    val judge = LLMInvariantJudge(llmClient)
    val promptBuilder = PromptBuilder()
    val agent = InvariantAwareAgent(config, store, checker, judge, promptBuilder, llmClient)
    val demoRunner = DemoRunner(store, checker, judge, agent)

    when (args.firstOrNull() ?: "demo") {
        "demo" -> demoRunner.runDemo()
        "checker-demo" -> demoRunner.runCheckerDemo()
        "interactive" -> InteractiveCli(store, checker, agent).run()
        else -> println("Usage: demo | checker-demo | interactive")
    }
}
