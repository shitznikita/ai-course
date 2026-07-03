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

    fun dryRun(): EvaluationReport {
        val records = questions.map { question ->
            val results = agent.retrieve(question.question)
            question.toEvaluationRecord(results, noRag = null, rag = null)
        }
        return EvaluationReport(
            generatedAtIso = Instant.now().toString(),
            mode = "dry-run-retrieval-only",
            questionCount = records.size,
            expectedSourceHitCount = records.sumOf { it.expectedSourceHits.size },
            records = records,
        )
    }

    fun live(): EvaluationReport {
        val records = questions.map { question ->
            val results = agent.retrieve(question.question)
            val noRag = agent.askNoRag(question.question)
            val rag = agent.askRag(question.question)
            question.toEvaluationRecord(results, noRag = noRag, rag = rag)
        }
        return EvaluationReport(
            generatedAtIso = Instant.now().toString(),
            mode = "live-no-rag-vs-rag",
            questionCount = records.size,
            expectedSourceHitCount = records.sumOf { it.expectedSourceHits.size },
            records = records,
        )
    }

    private fun ControlQuestion.toEvaluationRecord(
        results: List<SearchResult>,
        noRag: AnswerRun?,
        rag: AnswerRun?,
    ): EvaluationRecord {
        val retrievedSources = results.map { it.chunk.metadata.source }.distinct()
        val hits = expectedSources.filter { expected ->
            retrievedSources.any { source -> source == expected || source.endsWith(expected) || source.contains(expected) }
        }
        return EvaluationRecord(
            id = id,
            question = question,
            expectedAnswerPoints = expectedAnswerPoints,
            expectedSources = expectedSources,
            retrievedSources = retrievedSources,
            expectedSourceHits = hits,
            noRag = noRag,
            rag = rag,
        )
    }
}

class ReportStorage(private val config: AppConfig) {
    fun saveComparison(question: String, noRag: AnswerRun, rag: AnswerRun) {
        config.reportsDir.createDirectories()
        config.reportsDir.resolve("latest-rag-comparison.md").writeText(
            buildString {
                appendLine("# Day 22 RAG Comparison")
                appendLine()
                appendLine("Question: $question")
                appendLine()
                appendLine("## WITHOUT RAG")
                appendLine()
                appendLine(noRag.answer.ifBlank { noRag.warningOrError ?: "empty answer" })
                appendLine()
                appendLine("## WITH RAG")
                appendLine()
                appendLine(rag.answer.ifBlank { rag.warningOrError ?: "empty answer" })
                appendLine()
                appendLine("## Retrieved Sources")
                rag.retrievedChunks.forEach {
                    appendLine("- score=${it.score.formatDigits()} `${it.source}` / ${it.section} / ${it.chunkId}")
                }
            },
        )
    }

    fun saveEvaluation(report: EvaluationReport) {
        config.reportsDir.createDirectories()
        val safeTimestamp = report.generatedAtIso.replace(':', '-').replace('.', '-')
        config.reportsDir.resolve("eval-live-$safeTimestamp.json").writeText(AppJson.pretty.encodeToString(report))
    }
}
