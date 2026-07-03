import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ScenarioRepository(private val config: AppConfig) {
    fun load(): List<ChatScenario> =
        AppJson.compact.decodeFromString(config.scenariosFile.readText())
}

class ScenarioRunner(private val config: AppConfig) {
    fun dryRun(): ScenarioEvaluationReport = runScenarios("dry-run-scenarios", live = false)

    fun live(): ScenarioEvaluationReport = runScenarios("live-scenarios", live = true)

    private fun runScenarios(mode: String, live: Boolean): ScenarioEvaluationReport {
        val scenarios = ScenarioRepository(config).load()
        val records = scenarios.map { scenario ->
            val agent = ChatAgent(config)
            agent.clear()
            val stepRecords = scenario.steps.mapIndexed { index, step ->
                val run = if (live) agent.answerLive(step.user) else agent.answerDryRun(step.user)
                run.toStepRecord(index + 1, step)
            }
            val finalState = agent.session().taskState
            ScenarioEvaluationRecord(
                id = scenario.id,
                title = scenario.title,
                turnCount = scenario.steps.size,
                answersWithSources = stepRecords.count { it.sourcesPresent },
                quotesMatchChunks = stepRecords.count { it.quotesMatchChunks },
                ragCalls = stepRecords.count { it.ragCalled },
                goalRetained = scenario.expectedGoalTerms.all { term ->
                    finalState.goal.orEmpty().contains(term, ignoreCase = true)
                },
                constraintsRetained = scenario.expectedConstraintTerms.all { term ->
                    finalState.constraints.joinToString(" ").contains(term, ignoreCase = true)
                },
                termsRetained = scenario.expectedTerms.all { term ->
                    finalState.terms.joinToString(" ").contains(term, ignoreCase = true) ||
                        finalState.clarifications.joinToString(" ").contains(term, ignoreCase = true)
                },
                finalTaskState = finalState,
                records = stepRecords,
            )
        }
        return ScenarioEvaluationReport(
            generatedAtIso = Instant.now().toString(),
            mode = mode,
            scenarioCount = records.size,
            totalTurns = records.sumOf { it.turnCount },
            answersWithSources = records.sumOf { it.answersWithSources },
            quotesMatchChunks = records.sumOf { it.quotesMatchChunks },
            ragCalls = records.sumOf { it.ragCalls },
            goalsRetained = records.count { it.goalRetained },
            constraintsRetained = records.count { it.constraintsRetained },
            termsRetained = records.count { it.termsRetained },
            records = records,
        )
    }

    private fun ChatTurnRun.toStepRecord(turn: Int, step: ChatScenarioStep): ScenarioStepRecord {
        val answer = groundedRun.answer
        val maxRelevance = groundedRun.retrieval.selected.maxOfOrNull { it.rerankScore } ?: 0.0
        val sourcesPresent = !step.expectSources || answer.sources.isNotEmpty()
        return ScenarioStepRecord(
            turn = turn,
            user = userMessage,
            status = answer.status,
            sourcesPresent = sourcesPresent,
            quotesMatchChunks = groundedRun.validation.quotesMatchChunks,
            ragCalled = groundedRun.retrieval.before.isNotEmpty(),
            maxRelevance = maxRelevance,
            taskGoal = taskStateAfter.goal,
            constraintCount = taskStateAfter.constraints.size,
            termCount = taskStateAfter.terms.size,
            validationErrors = groundedRun.validation.errors,
        )
    }
}

class ReportStorage(private val config: AppConfig) {
    fun saveDemo(runs: List<ChatTurnRun>) {
        config.reportsDir.createDirectories()
        config.reportsDir.resolve("latest-chat-demo.md").writeText(
            buildString {
                appendLine("# Day 25 RAG Chat Demo")
                appendLine()
                runs.forEachIndexed { index, run ->
                    appendLine("## Turn ${index + 1}")
                    appendLine()
                    appendLine("User: ${run.userMessage}")
                    appendLine()
                    appendLine("Task goal: ${run.taskStateAfter.goal ?: "not set"}")
                    appendLine("Constraints: ${run.taskStateAfter.constraints.joinToString("; ").ifBlank { "none" }}")
                    appendLine("Terms: ${run.taskStateAfter.terms.joinToString(", ").ifBlank { "none" }}")
                    appendLine()
                    appendRun(run.groundedRun)
                    appendLine()
                }
            },
        )
    }

    fun saveScenarioDryRun(report: ScenarioEvaluationReport) {
        config.reportsDir.createDirectories()
        config.reportsDir.resolve("scenario-dry-run.json").writeText(AppJson.pretty.encodeToString(report))
    }

    fun saveScenarioLive(report: ScenarioEvaluationReport) {
        config.reportsDir.createDirectories()
        val safeTimestamp = report.generatedAtIso.replace(':', '-').replace('.', '-')
        config.reportsDir.resolve("scenario-live-$safeTimestamp.json").writeText(AppJson.pretty.encodeToString(report))
    }

    private fun StringBuilder.appendRun(run: GroundedRun) {
        appendLine("Status: `${run.answer.status}`")
        appendLine()
        appendLine("Answer: ${run.answer.answer}")
        appendLine()
        if (run.answer.sources.isNotEmpty()) {
            appendLine("Sources:")
            run.answer.sources.forEach {
                appendLine("- `${it.source}` / ${it.section} / ${it.chunkId}")
            }
            appendLine()
        }
        if (run.answer.quotes.isNotEmpty()) {
            appendLine("Quotes:")
            run.answer.quotes.forEach {
                appendLine("- `${it.quoteId ?: "quote"}` ${it.text}")
            }
            appendLine()
        }
        appendLine("Validation errors: ${run.validation.errors.ifEmpty { listOf("none") }.joinToString()}")
    }
}
