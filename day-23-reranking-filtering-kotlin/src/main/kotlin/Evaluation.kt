import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ControlQuestionRepository(private val config: AppConfig) {
    fun load(): List<ControlQuestion> =
        AppJson.compact.decodeFromString(config.controlQuestionsFile.readText())
}

class EvaluationRunner(private val config: AppConfig) {
    private val agent = RagAgent(config)
    private val questions = ControlQuestionRepository(config).load()

    fun dryRun(): RerankEvaluationReport {
        val records = questions.map { question ->
            val baseline = agent.retrieveBaseline(question.question)
            val reranked = agent.retrieveReranked(question.question)
            question.toEvaluationRecord(baseline, reranked, baselineAnswer = null, rerankedAnswer = null)
        }
        return records.toReport("dry-run-baseline-vs-reranked")
    }

    fun live(): RerankEvaluationReport {
        val records = questions.map { question ->
            val baseline = agent.retrieveBaseline(question.question)
            val reranked = agent.retrieveReranked(question.question)
            val baselineAnswer = agent.askBaselineRag(question.question)
            val rerankedAnswer = agent.askRerankedRag(question.question)
            question.toEvaluationRecord(baseline, reranked, baselineAnswer, rerankedAnswer)
        }
        return records.toReport("live-baseline-rag-vs-reranked-rag")
    }

    private fun List<RerankEvaluationRecord>.toReport(mode: String): RerankEvaluationReport =
        RerankEvaluationReport(
            generatedAtIso = Instant.now().toString(),
            mode = mode,
            questionCount = size,
            expectedSourceCount = sumOf { it.expectedSources.size },
            baselineExpectedSourceHitCount = sumOf { it.baselineExpectedSourceHits.size },
            rerankedExpectedSourceHitCount = sumOf { it.rerankedExpectedSourceHits.size },
            baselineFalsePositiveCount = sumOf { it.baselineFalsePositiveSources.size },
            rerankedFalsePositiveCount = sumOf { it.rerankedFalsePositiveSources.size },
            records = this,
        )

    private fun ControlQuestion.toEvaluationRecord(
        baseline: List<SearchResult>,
        reranked: RerankedRetrieval,
        baselineAnswer: AnswerRun?,
        rerankedAnswer: AnswerRun?,
    ): RerankEvaluationRecord {
        val baselineSources = baseline.map { it.chunk.metadata.source }.distinct()
        val rerankedSources = reranked.selected.map { it.result.chunk.metadata.source }.distinct()
        val baselineHits = expectedHits(baselineSources, expectedSources)
        val rerankedHits = expectedHits(rerankedSources, expectedSources)
        return RerankEvaluationRecord(
            id = id,
            question = question,
            rewrittenQuery = reranked.rewrite.rewritten,
            addedRewriteHints = reranked.rewrite.addedHints,
            expectedAnswerPoints = expectedAnswerPoints,
            expectedSources = expectedSources,
            baselineSources = baselineSources,
            rerankedSources = rerankedSources,
            baselineExpectedSourceHits = baselineHits,
            rerankedExpectedSourceHits = rerankedHits,
            baselineFalsePositiveSources = falsePositiveSources(baselineSources, expectedSources),
            rerankedFalsePositiveSources = falsePositiveSources(rerankedSources, expectedSources),
            baselineFirstHitRank = firstHitRank(baselineSources, expectedSources),
            rerankedFirstHitRank = firstHitRank(rerankedSources, expectedSources),
            candidatesBeforeFilter = reranked.before.size,
            chunksAfterFilter = reranked.selected.size,
            filteredOutCount = reranked.filteredOutCount,
            lowConfidence = reranked.lowConfidence,
            baseline = baselineAnswer,
            reranked = rerankedAnswer,
        )
    }

    private fun expectedHits(actualSources: List<String>, expectedSources: List<String>): List<String> =
        expectedSources.filter { expected ->
            actualSources.any { actual -> sourceMatches(actual, expected) }
        }

    private fun falsePositiveSources(actualSources: List<String>, expectedSources: List<String>): List<String> =
        actualSources.filterNot { actual ->
            expectedSources.any { expected -> sourceMatches(actual, expected) }
        }

    private fun firstHitRank(actualSources: List<String>, expectedSources: List<String>): Int? =
        actualSources.indexOfFirst { actual ->
            expectedSources.any { expected -> sourceMatches(actual, expected) }
        }.takeIf { it >= 0 }?.plus(1)

    private fun sourceMatches(actual: String, expected: String): Boolean =
        actual == expected || actual.endsWith(expected) || actual.contains(expected)
}

class ReportStorage(private val config: AppConfig) {
    fun saveComparison(question: String, baseline: AnswerRun, reranked: AnswerRun) {
        config.reportsDir.createDirectories()
        config.reportsDir.resolve("latest-rerank-comparison.md").writeText(
            buildString {
                appendLine("# Day 23 Rerank Comparison")
                appendLine()
                appendLine("Question: $question")
                appendLine()
                appendLine("## BASELINE RAG")
                appendLine()
                appendLine(baseline.answer.ifBlank { baseline.warningOrError ?: "empty answer" })
                appendLine()
                appendLine("## RERANKED RAG")
                appendLine()
                appendLine("Rewritten query: ${reranked.rewrittenQuery ?: question}")
                if (reranked.lowConfidence) {
                    appendLine()
                    appendLine("Low confidence: no chunk passed rerank threshold; fallback context was used.")
                }
                appendLine()
                appendLine(reranked.answer.ifBlank { reranked.warningOrError ?: "empty answer" })
                appendLine()
                appendLine("## Baseline Sources")
                baseline.retrievedChunks.forEach {
                    appendLine("- score=${it.score.formatDigits()} `${it.source}` / ${it.section} / ${it.chunkId}")
                }
                appendLine()
                appendLine("## Reranked Sources")
                reranked.retrievedChunks.forEach {
                    appendLine("- score=${it.score.formatDigits()} `${it.source}` / ${it.section} / ${it.chunkId}")
                }
            },
        )
    }

    fun saveDryRun(report: RerankEvaluationReport) {
        config.reportsDir.createDirectories()
        config.reportsDir.resolve("eval-rerank-dry-run.json").writeText(AppJson.pretty.encodeToString(report))
    }

    fun saveEvaluation(report: RerankEvaluationReport) {
        config.reportsDir.createDirectories()
        val safeTimestamp = report.generatedAtIso.replace(':', '-').replace('.', '-')
        config.reportsDir.resolve("eval-live-$safeTimestamp.json").writeText(AppJson.pretty.encodeToString(report))
    }
}
