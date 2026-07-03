private val FIXTURE_MESSAGES = listOf(
    "Цель: подготовить видео по RAG days 21-24. Ограничение: отвечай только с источниками и цитатами. Как запустить Day 21 offline demo?",
    "Уточнение: live chunks нельзя отправлять во внешний endpoint без разрешения. Какие источники и цитаты должен показывать Day 24?",
    "Зафиксируй термин production-like RAG chat. Как показать Day 25 офлайн, чтобы было видно историю, task state и sources?",
)

fun main(args: Array<String>) {
    val config = AppConfig.load()
    when (args.firstOrNull() ?: "fixture-demo") {
        "fixture-demo" -> runFixtureDemo(config)
        "chat" -> runInteractiveChat(config)
        "ask-dry-run" -> runAskDryRun(config, args.drop(1))
        "ask" -> runAsk(config, args.drop(1))
        "scenario-dry-run" -> runScenarioDryRun(config)
        "scenario-live" -> runScenarioLive(config)
        else -> printUsage()
    }
}

private fun runFixtureDemo(config: AppConfig) {
    val agent = ChatAgent(config)
    agent.clear()
    val runs = FIXTURE_MESSAGES.map { agent.answerDryRun(it) }
    ReportStorage(config).saveDemo(runs)

    println("Day 25: RAG mini-chat with task memory")
    println("mode: fixture-demo")
    println("embedding backend: ${config.embeddingBackend}")
    println("session file: ${config.sessionFile.toAbsolutePath()}")
    println()
    runs.forEachIndexed { index, run ->
        println("======== TURN ${index + 1} ========")
        printChatTurn(run, includePrompt = index == 0)
        println()
    }
    println("REPORT: ${config.reportsDir.resolve("latest-chat-demo.md").toAbsolutePath()}")
    println("CHECK: history + task state + RAG + sources/quotes are demonstrated without LLM calls")
}

private fun runAskDryRun(config: AppConfig, args: List<String>) {
    val question = args.joinToString(" ").trim().ifBlank { FIXTURE_MESSAGES.first() }
    val run = ChatAgent(config).answerDryRun(question)
    printChatTurn(run, includePrompt = true)
}

private fun runAsk(config: AppConfig, args: List<String>) {
    val question = args.joinToString(" ").trim().ifBlank { FIXTURE_MESSAGES.first() }
    val run = ChatAgent(config).answerLive(question)
    printChatTurn(run, includePrompt = false)
}

private fun runScenarioDryRun(config: AppConfig) {
    val report = ScenarioRunner(config).dryRun()
    ReportStorage(config).saveScenarioDryRun(report)
    printScenarioEvaluation(report)
    println()
    println("REPORT: ${config.reportsDir.resolve("scenario-dry-run.json").toAbsolutePath()}")
    println("CHECK: 2 long scenarios retain goal/constraints and print sources on every supported answer")
}

private fun runScenarioLive(config: AppConfig) {
    val report = ScenarioRunner(config).live()
    ReportStorage(config).saveScenarioLive(report)
    printScenarioEvaluation(report)
    println()
    println("REPORTS DIR: ${config.reportsDir.toAbsolutePath()}")
}

private fun runInteractiveChat(config: AppConfig) {
    val agent = ChatAgent(config)
    println("Day 25 RAG chat. Commands: /state, /history, /clear, /exit")
    println("Session file: ${config.sessionFile.toAbsolutePath()}")
    while (true) {
        print("User> ")
        val input = readlnOrNull()?.trim() ?: break
        when {
            input.equals("/exit", ignoreCase = true) || input.equals("exit", ignoreCase = true) -> return
            input.equals("/state", ignoreCase = true) -> printSession(agent.session())
            input.equals("/history", ignoreCase = true) -> printSession(agent.session())
            input.equals("/clear", ignoreCase = true) -> {
                agent.clear()
                println("Session cleared.")
            }
            input.isBlank() -> Unit
            else -> {
                val run = agent.answerLive(input)
                printChatTurn(run, includePrompt = false)
            }
        }
    }
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo                         Offline chat demo with task memory and RAG citations")
    println("  chat                                 Interactive live CLI chat")
    println("  ask-dry-run <question>               One offline turn with saved history and prompt preview")
    println("  ask <question>                       One live turn with saved history")
    println("  scenario-dry-run                     Offline validation over 2 long scenarios")
    println("  scenario-live                        Optional live validation over 2 long scenarios")
}
