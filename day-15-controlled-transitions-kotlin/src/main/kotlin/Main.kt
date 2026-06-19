fun main(args: Array<String>) {
    val config = AppConfig.load()
    val storage = TaskStateStorage(config.stateFile)
    val invariantStore = InvariantStore(config.invariantsFile)
    val llmClient = LlmClient(config)
    val promptBuilder = PromptBuilder()
    val orchestrator = AgentOrchestrator(
        storage = storage,
        invariantStore = invariantStore,
        invariantChecker = InvariantChecker(),
        stateMachine = TaskStateMachine(TransitionValidator()),
        planningSwarm = PlanningAgentsSwarm(llmClient, promptBuilder),
        executionAgent = ExecutionAgent(llmClient, promptBuilder),
        validationAgent = ValidationAgent(llmClient, promptBuilder),
    )
    val demoRunner = DemoRunner(storage, invariantStore, orchestrator)

    when (args.firstOrNull() ?: "demo") {
        "demo" -> demoRunner.runFullDemo()
        "transition-tests" -> demoRunner.runTransitionTests()
        "pause-resume-demo" -> demoRunner.runPauseResumeDemo()
        "interactive" -> InteractiveCli(orchestrator).run()
        else -> println("Usage: demo | transition-tests | pause-resume-demo | interactive")
    }
}
