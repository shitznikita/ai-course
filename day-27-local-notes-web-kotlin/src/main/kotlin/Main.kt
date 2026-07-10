import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = try {
        AppConfig.load()
    } catch (error: ConfigurationException) {
        System.err.println("CONFIGURATION ERROR: ${error.message}")
        exitProcess(64)
    }
    val analyzer = OllamaNotesAnalyzer(config)

    when (args.firstOrNull()?.lowercase() ?: "serve") {
        "serve" -> startServer(config, analyzer)
        "diagnose" -> runDiagnose(analyzer)
        "help", "--help", "-h" -> printUsage()
        else -> {
            printUsage()
            exitProcess(64)
        }
    }
}

private fun startServer(config: AppConfig, analyzer: NotesAnalyzer) {
    println("Day 27: Local notes web analyzer")
    println("WEB APP: ${config.serverUrl}")
    println("OLLAMA: ${config.ollamaBaseUrl}")
    println("MODEL: ${config.model}")
    println("UPLOADS: .txt, .md; max ${config.maxUploadBytes} bytes / ${config.maxNoteChars} chars")
    println("PRIVACY: uploaded notes and reports are kept only in memory.")
    println("Open ${config.serverUrl} in a browser. Press Ctrl+C to stop.")

    embeddedServer(CIO, host = config.serverHost, port = config.serverPort) {
        notesWebModule(config, analyzer)
    }.start(wait = true)
}

private fun runDiagnose(analyzer: NotesAnalyzer) {
    try {
        val health = runBlocking { analyzer.diagnose() }
        println("OLLAMA: ${health.ollamaVersion}")
        println("MODEL: ${health.model} (installed)")
        println("CHECK: local model is ready for the web app")
    } catch (error: ApiProblem) {
        System.err.println("ERROR: ${error.message}")
        error.hint?.let { System.err.println(it) }
        exitProcess(2)
    }
}

private fun printUsage() {
    println("Usage:")
    println("  serve       Start the local web app (default)")
    println("  diagnose    Check local Ollama and the configured model")
    println()
    println("Configuration comes from day-27-local-notes-web-kotlin/.env and environment variables:")
    println("  APP_HOST=127.0.0.1")
    println("  APP_PORT=8787")
    println("  OLLAMA_BASE_URL=http://127.0.0.1:11434")
    println("  OLLAMA_MODEL=qwen3:14b")
}
