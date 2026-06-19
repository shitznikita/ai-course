fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()
    val mode = args.firstOrNull()?.lowercase() ?: env["DAY11_MODE"]?.lowercase() ?: "demo"
    val config = AppConfig.fromEnv(env)
    val tokenCounter = ApproximateTokenCounter()
    val llmClient = LlmClient(config, debug = config.debug)
    val memoryManager = MemoryManager(config)
    val promptBuilder = PromptBuilder(tokenCounter, config.shortTermMessagesLimit)
    val agent = MemoryAgent(llmClient, memoryManager, promptBuilder)
    val costEstimator = CostEstimator(config.inputPricePerMillionTokens, config.outputPricePerMillionTokens)

    println("Day 11: Assistant memory layers")
    println("Mode: $mode")
    println("Model: ${config.model}")
    println("Memory root: ${config.memoryRoot}")
    println("Short-term limit: ${config.shortTermMessagesLimit}")
    println()

    when (mode) {
        "demo" -> DemoRunner(
            memoryManager = memoryManager,
            promptBuilder = promptBuilder,
            agent = agent,
            costEstimator = costEstimator,
        ).run()

        "interactive" -> InteractiveCli(
            memoryManager = memoryManager,
            promptBuilder = promptBuilder,
            agent = agent,
        ).run()

        else -> {
            println("Unknown mode: $mode")
            println("Available modes: demo, interactive")
        }
    }
}
