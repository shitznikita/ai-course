fun main(args: Array<String>) {
    val config = AppConfig.load()
    val storage = TaskStateStorage(config.stateFile)
    val store = InvariantStore(config.invariantsFile)
    val llmClient = LlmClient(config)
    val checker = InvariantChecker()
    val judge = LLMInvariantJudge(llmClient)
    val promptBuilder = PromptBuilder()
    val stateMachine = TaskStateMachine()
    val intakeAgent = IntakeAgent(llmClient, promptBuilder, checker)
    val planningAgent = PlanningAgent(llmClient, promptBuilder, checker)
    val executionAgent = ExecutionAgent(llmClient, promptBuilder, checker)
    val validationAgent = ValidationAgent(llmClient, promptBuilder, checker)
    val orchestrator = AgentOrchestrator(
        config = config,
        storage = storage,
        stateMachine = stateMachine,
        invariantStore = store,
        checker = checker,
        judge = judge,
        intakeAgent = intakeAgent,
        planningAgent = planningAgent,
        executionAgent = executionAgent,
        validationAgent = validationAgent,
    )
    val demoRunner = DemoRunner(storage, store, checker, judge, orchestrator)

    when (args.firstOrNull() ?: "demo") {
        "demo" -> demoRunner.runDemo()
        "checker-demo" -> demoRunner.runCheckerDemo()
        "interactive" -> InteractiveCli(store, checker, orchestrator).run()
        else -> println("Usage: demo | checker-demo | interactive")
    }
}
