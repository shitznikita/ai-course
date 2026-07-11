import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val mode = args.firstOrNull()?.lowercase() ?: "serve"
    val config = try {
        AppConfig.load(requireAuth = mode == "serve")
    } catch (error: ConfigurationException) {
        System.err.println("CONFIGURATION ERROR: ${error.message}")
        exitProcess(64)
    }
    val knowledge = try {
        IngredientKnowledgeBase.load(config)
    } catch (error: KnowledgeException) {
        System.err.println("KNOWLEDGE ERROR: ${error.message}")
        exitProcess(66)
    }
    val service = LocalCosmeticsService(config, knowledge)

    when (mode) {
        "serve" -> serve(config, service)
        "diagnose" -> diagnose(service)
        "fixture-demo" -> fixtureDemo(config, knowledge)
        "eval-dry-run" -> OfflineEvaluation.run(knowledge, java.nio.file.Path.of("eval/eval-cases.json"), config.maxKnowledgeCards)
        "help", "--help", "-h" -> usage()
        else -> {
            usage()
            exitProcess(64)
        }
    }
}

private fun serve(config: AppConfig, service: CosmeticsUseCases) {
    println("Day 30: Private cosmetics LLM service")
    println("WEB APP: ${config.serverUrl}")
    println("OLLAMA: ${config.ollamaBaseUrl} (loopback only)")
    println("MODEL: ${config.model}")
    println("LIMITS: context=${config.contextLength}, concurrent=${config.maxConcurrentInference}, queue=${config.inferenceQueueCapacity}")
    println("PRIVACY: photos, INCI and chat stay in RAM; raw Ollama is not exposed.")
    println("Press Ctrl+C to stop.")
    embeddedServer(CIO, host = config.serverHost, port = config.serverPort) {
        cosmeticsWebModule(config, service)
    }.start(wait = true)
}

private fun diagnose(service: CosmeticsUseCases) {
    val health = runBlocking { service.health() }
    println("STATUS: ${health.status}")
    println("OLLAMA: ${health.ollamaVersion ?: "unavailable"}")
    println("MODEL: ${health.model} installed=${health.modelInstalled}")
    println("OCR: ready=${health.ocrReady}")
    println("LOCAL DATA: ingredients=${health.ingredientCards}, products=${health.catalogProducts}")
    if (health.status != "ready") exitProcess(2)
}

private fun fixtureDemo(config: AppConfig, knowledge: IngredientKnowledgeBase) {
    val inci = "AQUA, GLYCERIN, NIACINAMIDE, PANTHENOL, SODIUM HYALURONATE, PHENOXYETHANOL"
    val pack = knowledge.retrieve(inci, config.maxKnowledgeCards)
    val product = knowledge.findProductExact("DemoLab Hydro Balance Serum")
    println("DAY 30 OFFLINE FIXTURE DEMO")
    println("Parsed ingredients: ${pack.parsed.ingredients.size}")
    println("Recognized IDs: ${pack.recognized.joinToString { it.card.id }}")
    println("Grounded sources: ${pack.sources.joinToString { it.id }}")
    println("Catalog exact match: ${product?.id ?: "not found"}")
    println("Unknown product fallback: ask for photo or INCI; never guess")
    println("CHECK: local knowledge, exact retrieval and anti-hallucination fallback are ready")
}

private fun usage() {
    println("Usage:")
    println("  serve         Start the private web/API service (default)")
    println("  diagnose      Check Ollama, model, OCR and local data")
    println("  fixture-demo  Run deterministic retrieval without Ollama")
    println("  eval-dry-run  Validate retrieval, reformulations, unknowns and prompt injection")
}
