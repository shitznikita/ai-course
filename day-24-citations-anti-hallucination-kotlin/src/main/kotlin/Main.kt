private const val DEFAULT_SUPPORTED_QUESTION = "Как запустить Day 21 offline indexing demo?"
private const val DEFAULT_UNKNOWN_QUESTION = "Сформулируйте вопрос вне локальной базы знаний."

fun main(args: Array<String>) {
    val config = AppConfig.load()
    when (args.firstOrNull() ?: "fixture-demo") {
        "fixture-demo" -> runFixtureDemo(config)
        "ask-dry-run" -> runAskDryRun(config, args.drop(1))
        "ask" -> runAsk(config, args.drop(1))
        "eval-dry-run" -> runEvalDryRun(config)
        "eval-live" -> runEvalLive(config)
        else -> printUsage()
    }
}

private fun runFixtureDemo(config: AppConfig) {
    val agent = RagAgent(config)
    val supported = ControlQuestionRepository(config).load().getOrNull(1)
    val unknown = UnknownQuestionRepository(config).load().firstOrNull()
    val supportedRun = agent.askDryRun(
        question = supported?.question ?: DEFAULT_SUPPORTED_QUESTION,
        expectedAnswerPoints = supported?.expectedAnswerPoints ?: emptyList(),
    )
    val unknownRun = agent.askDryRun(unknown?.question ?: DEFAULT_UNKNOWN_QUESTION)
    ReportStorage(config).saveDemo(supportedRun, unknownRun)

    println("Day 24: Citations, sources, anti-hallucination")
    println("mode: fixture-demo")
    println("embedding backend: ${config.embeddingBackend}")
    println("answer min relevance: ${config.answerMinRelevance.formatDigits()}")
    println("min/max quotes: ${config.minQuotes}/${config.maxQuotes}")
    println()
    println("SUPPORTED QUESTION")
    printGroundedRun(supportedRun, includePrompt = true)
    println()
    println("LOW CONFIDENCE QUESTION")
    printGroundedRun(unknownRun, includePrompt = false)
    println()
    println("REPORT: ${config.reportsDir.resolve("latest-citation-demo.md").toAbsolutePath()}")
    println("CHECK: answer + sources + quotes + unknown gate are demonstrated without LLM calls")
}

private fun runAskDryRun(config: AppConfig, args: List<String>) {
    val question = args.joinToString(" ").trim().ifBlank { DEFAULT_SUPPORTED_QUESTION }
    val run = RagAgent(config).askDryRun(question)
    printGroundedRun(run, includePrompt = true)
}

private fun runAsk(config: AppConfig, args: List<String>) {
    val question = args.joinToString(" ").trim().ifBlank { DEFAULT_SUPPORTED_QUESTION }
    val run = RagAgent(config).askLive(question)
    printGroundedRun(run, includePrompt = false)
}

private fun runEvalDryRun(config: AppConfig) {
    val report = EvaluationRunner(config).dryRun()
    ReportStorage(config).saveDryRun(report)
    printCitationEvaluation(report)
    println()
    println("REPORT: ${config.reportsDir.resolve("eval-dry-run.json").toAbsolutePath()}")
    println("CHECK: 10 supported questions have sources/quotes; unknown questions return 'не знаю'")
}

private fun runEvalLive(config: AppConfig) {
    val report = EvaluationRunner(config).live()
    ReportStorage(config).saveEvaluation(report)
    printCitationEvaluation(report)
    println()
    println("REPORTS DIR: ${config.reportsDir.toAbsolutePath()}")
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo                         Offline supported + unknown answer preview")
    println("  ask-dry-run <question>               Retrieval, quote extraction, prompt preview without LLM")
    println("  ask <question>                       Live grounded RAG answer with citations")
    println("  eval-dry-run                         Offline validation over 10 supported + unknown questions")
    println("  eval-live                            Live validation over 10 supported + unknown questions")
}
