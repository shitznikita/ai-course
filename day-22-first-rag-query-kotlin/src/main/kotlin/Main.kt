private const val DEFAULT_DEMO_QUESTION = "Как запустить Day 21 offline indexing demo?"

fun main(args: Array<String>) {
    val config = AppConfig.load()
    when (args.firstOrNull() ?: "fixture-demo") {
        "fixture-demo" -> runFixtureDemo(config)
        "compare-demo" -> runCompare(config, DEFAULT_DEMO_QUESTION)
        "ask" -> runAsk(config, args.drop(1))
        "eval-dry-run" -> runEvalDryRun(config)
        "eval-live" -> runEvalLive(config)
        else -> printUsage()
    }
}

private fun runFixtureDemo(config: AppConfig) {
    val questions = ControlQuestionRepository(config).load()
    val question = questions.firstOrNull()?.question ?: DEFAULT_DEMO_QUESTION
    val agent = RagAgent(config)
    val results = agent.retrieve(question)

    println("Day 22: First RAG request")
    println("mode: fixture-demo")
    println("embedding backend: ${config.embeddingBackend}")
    println("question: $question")
    println()
    println("RETRIEVED CHUNKS")
    printRetrieved(results)
    println()
    println("RAG PROMPT PREVIEW")
    println(agent.promptPreview(question).shortPreview(2600))
    println()
    println("CHECK: question -> search relevant chunks -> build RAG prompt preview")
}

private fun runCompare(config: AppConfig, question: String) {
    val agent = RagAgent(config)
    val noRag = agent.askNoRag(question)
    val rag = agent.askRag(question)
    ReportStorage(config).saveComparison(question, noRag, rag)

    println("Day 22: no-RAG vs RAG")
    println("question: $question")
    println()
    printAnswer(noRag)
    println()
    printAnswer(rag)
    println()
    println("REPORT: ${config.reportsDir.resolve("latest-rag-comparison.md").toAbsolutePath()}")
}

private fun runAsk(config: AppConfig, args: List<String>) {
    if (args.size < 2) {
        printUsage()
        return
    }
    val mode = args.first().lowercase()
    val question = args.drop(1).joinToString(" ").trim()
    check(mode == "no-rag" || mode == "rag") { "ask mode must be no-rag or rag" }
    check(question.isNotBlank()) { "question must not be blank" }

    val agent = RagAgent(config)
    val answer = if (mode == "no-rag") agent.askNoRag(question) else agent.askRag(question)
    printAnswer(answer)
}

private fun runEvalDryRun(config: AppConfig) {
    val report = EvaluationRunner(config).dryRun()
    printEvaluation(report)
    println()
    println("CHECK: 10 control questions retrieved chunks and expected source hits without LLM calls")
}

private fun runEvalLive(config: AppConfig) {
    val report = EvaluationRunner(config).live()
    ReportStorage(config).saveEvaluation(report)
    printEvaluation(report)
    println()
    println("REPORTS DIR: ${config.reportsDir.toAbsolutePath()}")
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo                 Offline retrieval + RAG prompt preview")
    println("  compare-demo                 Live no-RAG vs RAG for a default question")
    println("  ask no-rag <question>        Ask LLM without retrieval")
    println("  ask rag <question>           Retrieve chunks, build RAG prompt, ask LLM")
    println("  eval-dry-run                 Retrieval/source-hit check for 10 control questions")
    println("  eval-live                    Live no-RAG vs RAG over all control questions")
}
