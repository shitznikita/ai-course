fun main(args: Array<String>) {
    val env = loadEnvFile(resolveEnvPath()) + System.getenv()
    val mode = args.firstOrNull()?.lowercase() ?: env["DAY10_MODE"]?.lowercase() ?: "demo"
    val config = AppConfig.fromEnv(env, mode)
    val tokenCounter = ApproximateTokenCounter()
    val llmClient = LlmClient(config, debug = config.debug)
    val factMemoryUpdater = FactMemoryUpdater(llmClient, tokenCounter, config.factsUpdateMode)
    val costEstimator = CostEstimator(config.inputPricePerMillionTokens, config.outputPricePerMillionTokens)

    println("Day 10: Context management strategies")
    println("Mode: $mode")
    println("Model: ${config.model}")
    println("Recent messages limit: ${config.recentMessagesLimit}")
    println("Facts update mode: ${config.factsUpdateMode}")
    println()

    when (mode) {
        "demo" -> DemoRunner(
            config = config,
            llmClient = llmClient,
            tokenCounter = tokenCounter,
            factMemoryUpdater = factMemoryUpdater,
            costEstimator = costEstimator,
        ).run()

        "interactive" -> {
            val store = StateStore(config.stateFile)
            val agent = ContextAgent(
                llmClient = llmClient,
                tokenCounter = tokenCounter,
                factMemoryUpdater = factMemoryUpdater,
                stateStore = store,
                recentMessagesLimit = config.recentMessagesLimit,
                initialState = store.load(),
            )
            InteractiveCli(agent).run()
        }

        else -> {
            println("Unknown mode: $mode")
            println("Available modes: demo, interactive")
        }
    }
}
