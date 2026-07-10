import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = try { AppConfig.load() } catch (error: ConfigurationException) {
        System.err.println("CONFIGURATION ERROR: ${error.message}"); exitProcess(64)
    }
    val command = args.firstOrNull()?.lowercase() ?: "benchmark"
    val runner = Day29Runner(config)
    try {
        val success = when (command) {
            "diagnose" -> runner.diagnose()
            "benchmark" -> runner.benchmark()
            "profile" -> runner.profile(args.getOrNull(1) ?: "", args.drop(2).joinToString(" "))
            "help", "--help", "-h" -> { printUsage(); true }
            else -> { printUsage(); false }
        }
        if (!success) exitProcess(if (command in setOf("help", "--help", "-h")) 0 else 2)
    } catch (error: OptimizationException) {
        System.err.println("ERROR: ${error.message}")
        when (error) {
            is OllamaUnavailableException -> System.err.println("Start local Ollama in another terminal: ollama serve")
            is IndexValidationException -> System.err.println(rebuildIndexHint(config.embeddingModel))
            else -> Unit
        }
        exitProcess(if (error is ConfigurationException) 64 else 1)
    } catch (error: IllegalArgumentException) {
        System.err.println("ERROR: ${error.message}"); exitProcess(64)
    }
}

private fun printUsage() {
    println("Usage:")
    println("  benchmark                                      Run 3 questions × 3 profiles × 3 repeats (default)")
    println("  diagnose                                       Check Q4/Q8, embeddings, index and local resources")
    println("  profile <baseline-q4|optimized-q4|optimized-q8> <question>")
    println()
    println("All embeddings, generation, resources and reports are local. The CLI never sends RAG chunks to cloud services.")
}
