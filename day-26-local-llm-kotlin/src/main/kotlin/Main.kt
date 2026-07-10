import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = try {
        AppConfig.load()
    } catch (error: ConfigurationException) {
        System.err.println("CONFIGURATION ERROR: ${error.message}")
        exitProcess(64)
    }
    val runner = DemoRunner(config)
    val command = args.firstOrNull()?.lowercase() ?: "demo"

    try {
        when (command) {
            "diagnose" -> {
                if (!runner.diagnose()) exitProcess(2)
            }
            "demo" -> runner.runDemo()
            "ask" -> {
                val prompt = args.drop(1).joinToString(" ").trim()
                if (prompt.isBlank()) {
                    printUsage()
                    exitProcess(64)
                }
                runner.ask(prompt)
            }
            "help", "--help", "-h" -> printUsage()
            else -> {
                printUsage()
                exitProcess(64)
            }
        }
    } catch (error: LocalLlmException) {
        printFailure(error, config)
        exitProcess(if (error is ModelNotInstalledException) 2 else 1)
    }
}

private fun printFailure(error: LocalLlmException, config: AppConfig) {
    System.err.println("ERROR: ${error.message}")
    when (error) {
        is OllamaUnavailableException -> {
            System.err.println("Start the local server in another terminal: ollama serve")
            System.err.println("Then retry: day-26-local-llm-kotlin/scripts/run-local-llm.sh --args=\"diagnose\"")
        }
        is ModelNotInstalledException -> {
            System.err.println("Download the required local model: ollama pull ${config.model}")
        }
        is OllamaHttpException -> {
            if (error.statusCode == 404) {
                System.err.println("Check that the local model is installed: ollama pull ${config.model}")
            }
        }
        else -> Unit
    }
}

private fun printUsage() {
    println("Usage:")
    println("  demo                 Run three fixed local HTTP requests (default)")
    println("  diagnose             Check local Ollama daemon and installed models")
    println("  ask <prompt>         Send one custom prompt to local Ollama")
    println()
    println("Configuration comes from day-26-local-llm-kotlin/.env and environment variables:")
    println("  OLLAMA_BASE_URL=http://127.0.0.1:11434")
    println("  OLLAMA_MODEL=qwen3:14b")
}
