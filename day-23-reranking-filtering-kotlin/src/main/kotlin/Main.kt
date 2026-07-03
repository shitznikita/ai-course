private const val DEFAULT_DEMO_QUESTION = "Какие runtime файлы Day 21 нельзя коммитить?"

fun main(args: Array<String>) {
    val config = AppConfig.load()
    when (args.firstOrNull() ?: "fixture-demo") {
        "fixture-demo" -> runFixtureDemo(config)
        "compare-dry-run" -> runCompareDryRun(config)
        "compare-demo" -> runCompare(config, DEFAULT_DEMO_QUESTION)
        "ask" -> runAsk(config, args.drop(1))
        "eval-live" -> runEvalLive(config)
        else -> printUsage()
    }
}

private fun runFixtureDemo(config: AppConfig) {
    val questions = ControlQuestionRepository(config).load()
    val question = questions.getOrNull(5)?.question ?: DEFAULT_DEMO_QUESTION
    val agent = RagAgent(config)
    val baseline = agent.retrieveBaseline(question)
    val reranked = agent.retrieveReranked(question)

    println("Day 23: Reranking, filtering, query rewrite")
    println("mode: fixture-demo")
    println("embedding backend: ${config.embeddingBackend}")
    println("query rewrite mode: ${config.queryRewriteMode}")
    println("rerank mode: ${config.rerankMode}")
    println("topK before filter: ${config.retrievalTopKBefore}")
    println("topK after filter: ${config.rerankTopKAfter}")
    println("threshold: ${config.rerankMinScore.formatDigits()}")
    println()
    println("BASELINE RAG RETRIEVAL")
    printRetrieved(baseline)
    println()
    println("RERANKED RETRIEVAL")
    printReranked(reranked)
    println()
    println("RERANKED RAG PROMPT PREVIEW")
    println(agent.promptPreview(question).shortPreview(2800))
    println()
    println("CHECK: question -> rewrite -> retrieve top-K before -> rerank/filter -> top-K after -> prompt preview")
}

private fun runCompareDryRun(config: AppConfig) {
    val report = EvaluationRunner(config).dryRun()
    ReportStorage(config).saveDryRun(report)
    printRerankEvaluation(report)
    println()
    println("REPORT: ${config.reportsDir.resolve("eval-rerank-dry-run.json").toAbsolutePath()}")
    println("CHECK: 10 control questions compared baseline vs reranked retrieval without LLM calls")
}

private fun runCompare(config: AppConfig, question: String) {
    val agent = RagAgent(config)
    val baseline = agent.askBaselineRag(question)
    val reranked = agent.askRerankedRag(question)
    ReportStorage(config).saveComparison(question, baseline, reranked)

    println("Day 23: baseline RAG vs reranked RAG")
    println("question: $question")
    println()
    printAnswer(baseline)
    println()
    printAnswer(reranked)
    println()
    println("REPORT: ${config.reportsDir.resolve("latest-rerank-comparison.md").toAbsolutePath()}")
}

private fun runAsk(config: AppConfig, args: List<String>) {
    if (args.size < 2) {
        printUsage()
        return
    }
    val mode = args.first().lowercase()
    val question = args.drop(1).joinToString(" ").trim()
    check(mode == "baseline" || mode == "reranked") { "ask mode must be baseline or reranked" }
    check(question.isNotBlank()) { "question must not be blank" }

    val agent = RagAgent(config)
    val answer = if (mode == "baseline") agent.askBaselineRag(question) else agent.askRerankedRag(question)
    printAnswer(answer)
}

private fun runEvalLive(config: AppConfig) {
    val report = EvaluationRunner(config).live()
    ReportStorage(config).saveEvaluation(report)
    printRerankEvaluation(report)
    println()
    println("REPORTS DIR: ${config.reportsDir.toAbsolutePath()}")
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo                    Offline rewrite + rerank/filter + RAG prompt preview")
    println("  compare-dry-run                 Offline baseline vs reranked retrieval for 10 questions")
    println("  compare-demo                    Live baseline RAG vs reranked RAG for a default question")
    println("  ask baseline <question>         Retrieve baseline top-K context and ask LLM")
    println("  ask reranked <question>         Rewrite, rerank/filter context and ask LLM")
    println("  eval-live                       Live baseline vs reranked RAG over all control questions")
}
