class DemoRunner(
    private val config: AppConfig,
    private val llmClient: LlmClient,
    private val tokenCounter: ApproximateTokenCounter,
    private val factMemoryUpdater: FactMemoryUpdater,
    private val costEstimator: CostEstimator,
) {
    fun run() {
        println("=== DAY 10 DEMO: CONTEXT STRATEGIES WITHOUT SUMMARY ===")
        println("Scenario: collect a mobile finance app specification through 10-15 messages.")
        println("Recent messages limit: ${config.recentMessagesLimit}")
        println()

        val sliding = runLinearStrategy(StrategyName.SLIDING)
        val facts = runLinearStrategy(StrategyName.FACTS)
        val branching = runBranchingStrategy()

        printComparison(sliding, facts, branching)
    }

    private fun runLinearStrategy(strategyName: StrategyName): DemoResult {
        val agent = newDemoAgent()
        agent.switchStrategy(strategyName)

        println("=== STRATEGY: ${strategyName.cliName.uppercase()} ===")
        DemoScenario.baseMessages.forEachIndexed { index, message ->
            val factStats = agent.addUserMessage(message)
            println("${index + 1}. $message")
            factStats?.let { stats ->
                println("   facts update: ${stats.mode}, prompt tokens=${stats.promptTokens}${stats.warning?.let { ", warning=$it" } ?: ""}")
            }
        }

        val answer = agent.ask(DemoScenario.finalQuestion)
        val evaluationText = answer.answer + "\n" + (answer.context.factsBlock ?: "")
        val evaluation = evaluateAnswer(evaluationText)

        printAnswer(answer)
        return DemoResult(
            strategy = strategyName.cliName,
            answer = answer.answer,
            promptTokens = answer.context.promptTokens + answer.factsUpdatePromptTokens,
            apiTotalTokens = answer.usage?.totalTokens,
            cost = costEstimator.label(answer.usage),
            keptFacts = evaluation.kept,
            lostFacts = evaluation.lost,
            quality = "${evaluation.kept.size}/${expectedFacts.size}",
            stability = if (evaluation.lost.isEmpty()) "high" else "medium",
            userConvenience = if (strategyName == StrategyName.SLIDING) "easy" else "medium",
            notes = "dropped=${answer.context.droppedMessages}, recent=${answer.context.recentMessagesSent}",
        )
    }

    private fun runBranchingStrategy(): BranchingDemoResult {
        val agent = newDemoAgent()
        agent.switchStrategy(StrategyName.BRANCHING)

        println("=== STRATEGY: BRANCHING ===")
        DemoScenario.branchBaseMessages.forEachIndexed { index, message ->
            agent.addUserMessage(message)
            println("${index + 1}. checkpoint base: $message")
        }

        agent.createCheckpoint()
        agent.createBranch("branch_a")
        agent.createBranch("branch_b")
        println("Checkpoint created after ${DemoScenario.branchBaseMessages.size} messages.")
        println("Branches: ${agent.branchNames().joinToString()}")
        println()

        agent.switchBranch("branch_a")
        DemoScenario.branchAContinuation.dropLast(1).forEach { agent.addUserMessage(it) }
        val branchA = agent.ask(DemoScenario.branchAContinuation.last())

        agent.switchBranch("branch_b")
        DemoScenario.branchBContinuation.dropLast(1).forEach { agent.addUserMessage(it) }
        val branchB = agent.ask(DemoScenario.branchBContinuation.last())

        val branchAIsMinimal = branchA.answer.contains("ручн", ignoreCase = true) ||
            branchA.answer.contains("прост", ignoreCase = true) ||
            branchA.answer.contains("миним", ignoreCase = true)
        val branchBIsAdvanced = branchB.answer.contains("аналит", ignoreCase = true) ||
            branchB.answer.contains("график", ignoreCase = true) ||
            branchB.answer.contains("инсайт", ignoreCase = true)
        val branchAHasAdvancedFeatures = branchA.answer.contains("графики расходов", ignoreCase = true) ||
            branchA.answer.contains("инсайты", ignoreCase = true) ||
            branchA.answer.contains("сравнение расходов по месяцам", ignoreCase = true)
        val isolated = branchAIsMinimal && branchBIsAdvanced && !branchAHasAdvancedFeatures

        println("Branch A answer:")
        printAnswer(branchA)
        println("Branch B answer:")
        printAnswer(branchB)

        val promptTokens = branchA.context.promptTokens + branchB.context.promptTokens
        return BranchingDemoResult(
            strategy = "branching",
            branchAAnswer = branchA.answer,
            branchBAnswer = branchB.answer,
            promptTokens = promptTokens,
            apiTotalTokens = listOfNotNull(branchA.usage?.totalTokens, branchB.usage?.totalTokens).sum().takeIf { it > 0 },
            cost = "A=${costEstimator.label(branchA.usage)}, B=${costEstimator.label(branchB.usage)}",
            branchesIsolated = isolated,
            notes = "checkpoint=${DemoScenario.branchBaseMessages.size}, branches=2",
        )
    }

    private fun printAnswer(answer: AgentAnswer) {
        println()
        println("Context sent:")
        println("Strategy: ${answer.strategy.cliName}")
        println("Recent/messages sent: ${answer.context.recentMessagesSent}")
        println("Dropped messages: ${answer.context.droppedMessages}")
        println("Prompt tokens: ${answer.context.promptTokens}")
        if (answer.factsUpdatePromptTokens > 0) {
            println("Facts update prompt tokens: ${answer.factsUpdatePromptTokens}")
        }
        answer.context.factsBlock?.let {
            println("Facts used:")
            println(it)
        }
        answer.context.branchInfo?.let { println("Branch info: $it") }
        println("API usage: ${formatApiUsage(answer.usage)}")
        println("Cost: ${costEstimator.label(answer.usage)}")
        answer.warningOrError?.let { println("Warning: $it") }
        println()
        println("Answer:")
        println(answer.answer)
        println()
    }

    private fun printComparison(
        sliding: DemoResult,
        facts: DemoResult,
        branching: BranchingDemoResult,
    ) {
        println("=== COMPARISON ===")
        println("| Strategy | Prompt tokens | Important facts kept | Important facts lost | Answer quality | Stability | User convenience |")
        println("|---|---:|---|---|---|---|---|")
        listOf(sliding, facts).forEach { row ->
            println(
                "| ${row.strategy} | ${row.promptTokens} | ${row.keptFacts.joinToString("; ").ifBlank { "none" }} | " +
                    "${row.lostFacts.joinToString("; ").ifBlank { "none" }} | ${row.quality} | ${row.stability} | ${row.userConvenience} |",
            )
        }
        println(
            "| ${branching.strategy} | ${branching.promptTokens} | branches isolated=${branching.branchesIsolated} | " +
                "n/a | ${if (branching.branchesIsolated) "good" else "needs review"} | " +
                "${if (branching.branchesIsolated) "high" else "medium"} | harder, but good for alternatives |",
        )
        println()
        println("Short conclusion:")
        println("- Sliding Window is simplest, but old details can disappear from the REST request.")
        println("- Facts keeps stable key-value data without summary, but fact extraction can be imperfect.")
        println("- Branching is best for alternative product directions, but it needs explicit user commands.")
    }

    private fun newDemoAgent(): ContextAgent {
        return ContextAgent(
            llmClient = llmClient,
            tokenCounter = tokenCounter,
            factMemoryUpdater = factMemoryUpdater,
            stateStore = null,
            recentMessagesLimit = config.recentMessagesLimit,
        )
    }

    private fun evaluateAnswer(answer: String): Evaluation {
        val kept = expectedFacts.filter { it.regex.containsMatchIn(answer) }.map { it.label }
        val lost = expectedFacts.filterNot { it.regex.containsMatchIn(answer) }.map { it.label }
        return Evaluation(kept, lost)
    }
}

data class DemoResult(
    val strategy: String,
    val answer: String,
    val promptTokens: Int,
    val apiTotalTokens: Int?,
    val cost: String,
    val keptFacts: List<String>,
    val lostFacts: List<String>,
    val quality: String,
    val stability: String,
    val userConvenience: String,
    val notes: String,
)

data class BranchingDemoResult(
    val strategy: String,
    val branchAAnswer: String,
    val branchBAnswer: String,
    val promptTokens: Int,
    val apiTotalTokens: Int?,
    val cost: String,
    val branchesIsolated: Boolean,
    val notes: String,
)

private data class Evaluation(
    val kept: List<String>,
    val lost: List<String>,
)

private data class ExpectedFact(
    val label: String,
    val regex: Regex,
)

private val expectedFacts = listOf(
    ExpectedFact("name", Regex("Никит", RegexOption.IGNORE_CASE)),
    ExpectedFact("finance app", Regex("финанс|расход", RegexOption.IGNORE_CASE)),
    ExpectedFact("audience", Regex("аудитор|контрол", RegexOption.IGNORE_CASE)),
    ExpectedFact("no backend", Regex("без backend|backend не|бэкенд|бэкэнд|бэкэнда|бэкенда", RegexOption.IGNORE_CASE)),
    ExpectedFact("categories", Regex("категор", RegexOption.IGNORE_CASE)),
    ExpectedFact("limits", Regex("лимит", RegexOption.IGNORE_CASE)),
    ExpectedFact("simple UI", Regex("прост.*интерф|интерф.*прост|UX", RegexOption.IGNORE_CASE)),
    ExpectedFact("CSV", Regex("CSV", RegexOption.IGNORE_CASE)),
    ExpectedFact("no pushes", Regex("пуш|push", RegexOption.IGNORE_CASE)),
    ExpectedFact("subscription later", Regex("подписк|монетизац", RegexOption.IGNORE_CASE)),
    ExpectedFact("3 weeks", Regex("3 нед|три нед", RegexOption.IGNORE_CASE)),
    ExpectedFact("UX risk", Regex("риск|сложн.*UX|UX", RegexOption.IGNORE_CASE)),
)

fun formatApiUsage(usage: ApiUsage?): String {
    if (usage == null) return "not provided"
    val prompt = usage.promptTokens?.toString() ?: "?"
    val completion = usage.completionTokens?.toString() ?: "?"
    val total = usage.totalTokens?.toString() ?: "?"
    return "prompt=$prompt, completion=$completion, total=$total"
}
