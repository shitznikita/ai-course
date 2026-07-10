import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = try {
        AppConfig.load()
    } catch (error: ConfigurationException) {
        System.err.println("CONFIGURATION ERROR: ${error.message}")
        exitProcess(64)
    }
    val runner = Day28Runner(config)
    val command = args.firstOrNull()?.lowercase() ?: "benchmark"

    try {
        val successful = when (command) {
            "diagnose" -> runner.diagnose()
            "local" -> runner.local(args.drop(1).joinToString(" "))
            "compare" -> runner.compare(args.drop(1).joinToString(" "))
            "benchmark" -> runner.benchmark()
            "help", "--help", "-h" -> {
                printUsage()
                true
            }
            else -> {
                printUsage()
                false
            }
        }
        if (!successful) exitProcess(if (command == "help" || command == "--help" || command == "-h") 0 else 2)
    } catch (error: LocalRagException) {
        printFailure(error, config)
        exitProcess(if (error is ConfigurationException) 64 else 1)
    } catch (error: IllegalArgumentException) {
        System.err.println("ERROR: ${error.message}")
        exitProcess(64)
    }
}

private fun printFailure(error: LocalRagException, config: AppConfig) {
    System.err.println("ERROR: ${error.message}")
    when (error) {
        is OllamaUnavailableException -> System.err.println("Start local Ollama in another terminal: ollama serve")
        is IndexValidationException -> System.err.println(rebuildIndexHint(config.embeddingModel))
        is CloudConfigurationException -> System.err.println("Compare/benchmark need the local Day 1 OAuth config; local <question> stays fully offline.")
        else -> Unit
    }
}

private fun printUsage() {
    println("Usage:")
    println("  benchmark                       Compare local and cloud on 3 questions × 3 runs (default)")
    println("  diagnose                        Check local models, Day 21 index, query dimensions, and cloud config")
    println("  local <question>                Run one fully local RAG answer with citations")
    println("  compare <question>              Run one local + cloud answer on the same local retrieval context")
    println()
    println("Configuration comes from day-28-local-rag-kotlin/.env, Day 1 .env via the script, and shell environment.")
    println("Cloud commands send retrieved course chunks to the configured HTTPS endpoint; local never does.")
}
