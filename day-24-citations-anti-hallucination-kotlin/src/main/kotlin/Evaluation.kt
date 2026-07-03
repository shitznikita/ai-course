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

class UnknownQuestionRepository(private val config: AppConfig) {
    fun load(): List<UnknownQuestion> =
        AppJson.compact.decodeFromString(config.unknownQuestionsFile.readText())
}

class EvaluationRunner(private val config: AppConfig) {
    private val agent = RagAgent(config)
    private val supportedQuestions = ControlQuestionRepository(config).load()
    private val unknownQuestions = UnknownQuestionRepository(config).load()

    fun dryRun(): CitationEvaluationReport {
        val supported = supportedQuestions.map { question ->
            val run = agent.askDryRun(question.question, question.expectedAnswerPoints)
            run.toRecord(question.id, question.expectedAnswerPoints, question.expectedSources)
        }
        val unknown = unknownQuestions.map { question ->
            val run = agent.askDryRun(question.question)
            run.toRecord(question.id)
        }
        return (supported + unknown).toReport("dry-run-citations-and-unknown-gate", supported.size, unknown.size)
    }

    fun live(): CitationEvaluationReport {
        val supported = supportedQuestions.map { question ->
            val run = agent.askLive(question.question, question.expectedAnswerPoints)
            run.toRecord(question.id, question.expectedAnswerPoints, question.expectedSources)
        }
        val unknown = unknownQuestions.map { question ->
            val run = agent.askLive(question.question)
            run.toRecord(question.id)
        }
        return (supported + unknown).toReport("live-citations-and-unknown-gate", supported.size, unknown.size)
    }

    private fun GroundedRun.toRecord(
        id: String,
        expectedAnswerPoints: List<String> = emptyList(),
        expectedSources: List<String> = emptyList(),
    ): CitationEvaluationRecord =
        CitationEvaluationRecord(
            id = id,
            question = question,
            expectedAnswerPoints = expectedAnswerPoints,
            expectedSources = expectedSources,
            status = answer.status,
            maxRelevance = retrieval.selected.maxOfOrNull { it.rerankScore } ?: 0.0,
            sources = answer.sources,
            quotes = answer.quotes,
            validation = validation,
        )

    private fun List<CitationEvaluationRecord>.toReport(
        mode: String,
        supportedCount: Int,
        unknownCount: Int,
    ): CitationEvaluationReport =
        CitationEvaluationReport(
            generatedAtIso = Instant.now().toString(),
            mode = mode,
            supportedQuestionCount = supportedCount,
            unknownQuestionCount = unknownCount,
            sourcesPresentCount = count { it.expectedAnswerPoints.isNotEmpty() && it.validation.sourcesPresent },
            quotesPresentCount = count { it.expectedAnswerPoints.isNotEmpty() && it.validation.quotesPresent },
            quotesMatchChunksCount = count { it.expectedAnswerPoints.isNotEmpty() && it.validation.quotesMatchChunks },
            answerMatchesQuotesCount = count { it.expectedAnswerPoints.isNotEmpty() && it.validation.answerMatchesQuotes },
            unknownCount = count { it.expectedAnswerPoints.isEmpty() && it.validation.statusUnknown && it.validation.unknownSaysDontKnow },
            unknownClarifyingQuestionCount = count { it.expectedAnswerPoints.isEmpty() && it.validation.clarifyingQuestionPresent },
            records = this,
        )
}

class ReportStorage(private val config: AppConfig) {
    fun saveDemo(supported: GroundedRun, unknown: GroundedRun) {
        config.reportsDir.createDirectories()
        config.reportsDir.resolve("latest-citation-demo.md").writeText(
            buildString {
                appendLine("# Day 24 Citation Demo")
                appendLine()
                appendRun("Supported Question", supported)
                appendLine()
                appendRun("Low Confidence Question", unknown)
            },
        )
    }

    fun saveDryRun(report: CitationEvaluationReport) {
        config.reportsDir.createDirectories()
        config.reportsDir.resolve("eval-dry-run.json").writeText(AppJson.pretty.encodeToString(report))
    }

    fun saveEvaluation(report: CitationEvaluationReport) {
        config.reportsDir.createDirectories()
        val safeTimestamp = report.generatedAtIso.replace(':', '-').replace('.', '-')
        config.reportsDir.resolve("eval-live-$safeTimestamp.json").writeText(AppJson.pretty.encodeToString(report))
    }

    private fun StringBuilder.appendRun(title: String, run: GroundedRun) {
        appendLine("## $title")
        appendLine()
        appendLine("Question: ${run.question}")
        appendLine()
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
        if (run.answer.clarifyingQuestion != null) {
            appendLine("Clarifying question: ${run.answer.clarifyingQuestion}")
            appendLine()
        }
        appendLine("Validation errors: ${run.validation.errors.ifEmpty { listOf("none") }.joinToString()}")
    }
}
