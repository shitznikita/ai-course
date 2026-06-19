fun main(args: Array<String>) {
    val config = AppConfig.load()
    val storage = TaskStateStorage(config.stateFile)
    val stateMachine = TaskStateMachine()
    val llmClient = LlmClient(config)
    val promptBuilder = PromptBuilder()
    val orchestrator = AgentOrchestrator(
        storage = storage,
        stateMachine = stateMachine,
        intakeAgent = IntakeAgent(llmClient, promptBuilder),
        planningAgent = PlanningAgent(llmClient, promptBuilder),
        executionAgent = ExecutionAgent(llmClient, promptBuilder),
        validationAgent = ValidationAgent(llmClient, promptBuilder),
    )
    val demoRunner = DemoRunner(storage, orchestrator)

    when (args.firstOrNull() ?: "demo") {
        "demo" -> demoRunner.runFullDemo()
        "pause-demo" -> demoRunner.runPauseDemo()
        "interactive" -> InteractiveCli(orchestrator).run()
        else -> println("Usage: demo | pause-demo | interactive")
    }
}
