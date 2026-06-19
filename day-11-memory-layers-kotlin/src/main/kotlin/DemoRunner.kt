class DemoRunner(
    private val memoryManager: MemoryManager,
    private val promptBuilder: PromptBuilder,
    private val agent: MemoryAgent,
    private val costEstimator: CostEstimator,
) {
    fun run() {
        println("=== DAY 11 DEMO: ASSISTANT MEMORY LAYERS ===")
        println("Domain: mobile app specification assistant.")
        println()

        memoryManager.clearAllForDemo()
        println("1) Start with empty memory")
        println(memoryManager.show(null))
        println()

        println("2) Explicitly save long-term memory")
        DemoScenario.longTermSaves.forEach { (_, key, value) ->
            memoryManager.saveLong(key, value)
            println("/memory save long $key $value")
        }
        println()

        println("3) Explicitly save working memory")
        DemoScenario.workingSaves.forEach { (_, key, value) ->
            memoryManager.saveWorking(key, value)
            println("/memory save working $key $value")
        }
        println()

        println("4) Save short-term current-chat notes")
        DemoScenario.shortTermNotes.forEach { note ->
            memoryManager.saveShortText(note)
            println("/memory save short $note")
        }
        println()

        println("5) /memory show")
        println(memoryManager.show(null))
        println()

        println("6) /memory status")
        println(promptBuilder.status(memoryManager.snapshot()))
        println()

        println("7) Ask final question")
        println("User: ${DemoScenario.finalQuestion}")
        val answer = agent.ask(DemoScenario.finalQuestion)
        printPromptAudit(answer.prompt)
        println("API usage: ${formatApiUsage(answer.usage)}")
        println("Cost: ${costEstimator.label(answer.usage)}")
        answer.warningOrError?.let { println("Warning: $it") }
        println()
        println("Agent:")
        println(answer.answer)
        println()

        println("Conclusion:")
        println("- Short-term memory contributed current chat details.")
        println("- Working memory contributed task state, constraints, decisions, and open questions.")
        println("- Long-term memory contributed user profile and global rules.")
    }
}

fun printPromptAudit(prompt: PromptBuildResult) {
    println()
    println("=== PROMPT AUDIT ===")
    prompt.audits.forEach { audit ->
        println("${audit.layer}: included=${audit.included}, tokens=${audit.tokens}, ${audit.details}")
    }
    println("Recent short-term messages sent: ${prompt.recentMessagesSent}")
    println("Total prompt token estimate: ${prompt.promptTokens}")
    println("=== END PROMPT AUDIT ===")
    println()
}

fun formatApiUsage(usage: ApiUsage?): String {
    if (usage == null) return "not provided"
    val prompt = usage.promptTokens?.toString() ?: "?"
    val completion = usage.completionTokens?.toString() ?: "?"
    val total = usage.totalTokens?.toString() ?: "?"
    return "prompt=$prompt, completion=$completion, total=$total"
}
