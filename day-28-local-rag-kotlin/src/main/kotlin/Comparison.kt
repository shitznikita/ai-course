import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong

data class PreparedRagPrompt(
    val retrieval: RetrievalPackage,
    val messages: List<PromptMessage>,
)

data class ComparisonRun(
    val retrieval: RetrievalPackage,
    val local: GenerationOutcome,
    val cloud: GenerationOutcome? = null,
)

class RagComparisonService(
    private val retriever: RetrievalEngine,
    private val localGenerator: AnswerGenerator,
    private val cloudGenerator: AnswerGenerator?,
    private val promptBuilder: GroundedPromptBuilder = GroundedPromptBuilder(),
    private val validator: GroundingValidator = GroundingValidator(),
) {
    /**
     * Runs the only embedding/retrieval step for a question. Benchmark callers reuse
     * the result for all local and cloud generations, so model variance is not
     * mixed with retrieval variance.
     */
    fun prepare(index: Week6Index, question: BenchmarkQuestion): PreparedRagPrompt {
        val retrieval = retriever.retrieve(index, question.question)
        return PreparedRagPrompt(retrieval, promptBuilder.messages(retrieval))
    }

    fun runLocal(index: Week6Index, question: BenchmarkQuestion): ComparisonRun {
        return runLocal(prepare(index, question), question)
    }

    fun runLocal(prepared: PreparedRagPrompt, question: BenchmarkQuestion): ComparisonRun {
        return ComparisonRun(
            retrieval = prepared.retrieval,
            local = generate(localGenerator, prepared.messages, prepared.retrieval, question),
        )
    }

    fun runComparison(index: Week6Index, question: BenchmarkQuestion): ComparisonRun {
        return runComparison(prepare(index, question), question)
    }

    fun runComparison(prepared: PreparedRagPrompt, question: BenchmarkQuestion): ComparisonRun {
        val cloud = cloudGenerator
            ?: throw CloudConfigurationException("Cloud generator is required for compare and benchmark.")
        return ComparisonRun(
            retrieval = prepared.retrieval,
            local = generate(localGenerator, prepared.messages, prepared.retrieval, question),
            cloud = generate(cloud, prepared.messages, prepared.retrieval, question),
        )
    }

    private fun generate(
        generator: AnswerGenerator,
        messages: List<PromptMessage>,
        retrieval: RetrievalPackage,
        question: BenchmarkQuestion,
    ): GenerationOutcome = try {
        val reply = generator.generate(messages)
        val metrics = reply.toMetrics(generator.kind)
        val answer = try {
            GroundedAnswerParser.parse(reply.content)
        } catch (error: LocalRagException) {
            return GenerationOutcome(
                transportSucceeded = true,
                metrics = metrics.copy(error = "invalid_grounded_json"),
            )
        }
        GenerationOutcome(
            transportSucceeded = true,
            answer = answer,
            validation = validator.validate(answer, retrieval, question.expectedAnswerPoints, question.expectedSources),
            metrics = metrics,
        )
    } catch (error: Exception) {
        GenerationOutcome(
            transportSucceeded = false,
            metrics = GenerationMetrics(
                model = generator.configuredModel,
                kind = generator.kind,
                latencyMs = 0,
                error = errorCode(error, generator.kind),
            ),
        )
    }

    private fun errorCode(error: Exception, kind: String): String = when (error) {
        is CloudConfigurationException -> "cloud_not_configured"
        is CloudRequestException -> error.statusCode?.let { "cloud_http_$it" } ?: "cloud_request_failed"
        is OllamaUnavailableException -> "local_ollama_unavailable"
        is OllamaHttpException -> "local_ollama_http_${error.statusCode}"
        is OllamaProtocolException -> "local_ollama_protocol_error"
        else -> "${kind}_generation_failed"
    }
}

object BenchmarkMetrics {
    fun aggregate(
        model: String,
        kind: String,
        outcomes: List<GenerationOutcome>,
        stabilityOutcomeGroups: List<List<GenerationOutcome>> = listOf(outcomes),
    ): ModelAggregate {
        val successful = outcomes.filter { it.transportSucceeded }
        val grounded = outcomes.filter { it.validation?.grounded == true }
        val expectedTotals = outcomes.mapNotNull { validation ->
            validation.validation?.takeIf { it.expectedPointsTotal > 0 }
        }
        val latencies = successful.map { it.metrics.latencyMs }.filter { it > 0 }
        val speeds = successful.mapNotNull { it.metrics.outputTokensPerSecond }.filter { it.isFinite() && it > 0 }

        return ModelAggregate(
            model = model,
            kind = kind,
            runs = outcomes.size,
            transportSuccessRate = outcomes.rate { it.transportSucceeded },
            groundedRate = outcomes.rate { it.validation?.grounded == true },
            validJsonRate = outcomes.rate { it.answer != null },
            expectedPointRecall = expectedTotals.takeIf { it.isNotEmpty() }?.let { validations ->
                validations.sumOf { it.expectedPointsCovered }.toDouble() / validations.sumOf { it.expectedPointsTotal }
            },
            sourceSetStability = sourceSetStability(stabilityOutcomeGroups),
            latencyMinMs = latencies.minOrNull(),
            latencyMedianMs = latencies.medianLong(),
            latencyMaxMs = latencies.maxOrNull(),
            outputTokensPerSecondMedian = speeds.medianDouble(),
        )
    }

    fun newReport(
        config: AppConfig,
        records: List<BenchmarkRecord>,
    ): BenchmarkReport {
        val recordsByQuestion = records.groupBy { it.questionId }.values
        return BenchmarkReport(
            generatedAtIso = Instant.now().toString(),
            indexFile = config.week6IndexFile.toAbsolutePath().toString(),
            indexModel = config.embeddingModel,
            localModel = config.localModel,
            cloudModel = config.cloudModel,
            runsPerQuestion = config.benchmarkRuns,
            records = records,
            local = aggregate(
                config.localModel,
                "local",
                records.map { it.local },
                recordsByQuestion.map { group -> group.map { it.local } },
            ),
            cloud = aggregate(
                config.cloudModel,
                "cloud",
                records.map { it.cloud },
                recordsByQuestion.map { group -> group.map { it.cloud } },
            ),
        )
    }

    private fun List<GenerationOutcome>.rate(predicate: (GenerationOutcome) -> Boolean): Double =
        if (isEmpty()) 0.0 else count(predicate).toDouble() / size

    /**
     * Citation sets are comparable only across repeated runs of the same question.
     * Comparing q02's sources with q04's sources would make deterministic models
     * look unstable merely because the questions are different.
     */
    private fun sourceSetStability(groups: List<List<GenerationOutcome>>): Double? =
        groups.mapNotNull { outcomes ->
            val sourceSets = outcomes.mapNotNull { outcome ->
                outcome.answer?.sources
                    ?.map { "${it.source}|${it.section}|${it.chunkId}" }
                    ?.toSet()
            }
            sourceSetJaccard(sourceSets)
        }.takeIf { it.isNotEmpty() }?.average()

    private fun sourceSetJaccard(sets: List<Set<String>>): Double? {
        if (sets.size < 2) return null
        val pairs = buildList {
            sets.indices.forEach { left ->
                (left + 1 until sets.size).forEach { right -> add(sets[left] to sets[right]) }
            }
        }
        return pairs.map { (left, right) ->
            if (left.isEmpty() && right.isEmpty()) 1.0
            else left.intersect(right).size.toDouble() / left.union(right).size
        }.average()
    }

    private fun List<Long>.medianLong(): Long? =
        sorted().takeIf { it.isNotEmpty() }?.let { values ->
            if (values.size % 2 == 1) values[values.size / 2]
            else ((values[values.size / 2 - 1] + values[values.size / 2]) / 2.0).roundToLong()
        }

    private fun List<Double>.medianDouble(): Double? =
        sorted().takeIf { it.isNotEmpty() }?.let { values ->
            if (values.size % 2 == 1) values[values.size / 2]
            else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
        }
}

class ReportStorage(private val config: AppConfig) {
    fun save(report: BenchmarkReport): Pair<java.nio.file.Path, java.nio.file.Path> {
        java.nio.file.Files.createDirectories(config.reportsDir)
        val stamp = report.generatedAtIso.replace(':', '-').replace('.', '-')
        val jsonPath = config.reportsDir.resolve("benchmark-$stamp.json")
        val markdownPath = config.reportsDir.resolve("benchmark-$stamp.md")
        java.nio.file.Files.writeString(jsonPath, AppJson.pretty.encodeToString(BenchmarkReport.serializer(), report))
        java.nio.file.Files.writeString(markdownPath, renderMarkdown(report))
        return jsonPath to markdownPath
    }

    private fun renderMarkdown(report: BenchmarkReport): String = buildString {
        appendLine("# Day 28 Local RAG Benchmark")
        appendLine()
        appendLine("- Index model: `${report.indexModel}`")
        appendLine("- Local generation: `${report.localModel}`")
        appendLine("- Cloud generation: `${report.cloudModel}`")
        appendLine("- Runs per question: ${report.runsPerQuestion}")
        appendLine()
        appendLine("| Model | Transport | Grounded | JSON | Point recall | Source stability | Latency min/median/max | Output tok/s median |")
        appendLine("|---|---:|---:|---:|---:|---:|---|---:|")
        listOf(report.local, report.cloud).forEach { aggregate ->
            appendLine(
                "| ${aggregate.kind}: ${aggregate.model} | ${aggregate.transportSuccessRate.percent()} | " +
                    "${aggregate.groundedRate.percent()} | ${aggregate.validJsonRate.percent()} | " +
                    "${aggregate.expectedPointRecall?.percent() ?: "n/a"} | " +
                    "${aggregate.sourceSetStability?.percent() ?: "n/a"} | " +
                    "${aggregate.latencyMinMs ?: "n/a"}/${aggregate.latencyMedianMs ?: "n/a"}/${aggregate.latencyMaxMs ?: "n/a"} ms | " +
                    "${aggregate.outputTokensPerSecondMedian?.formatOne() ?: "n/a"} |",
            )
        }
    }
}

private fun Double.percent(): String = String.format(Locale.US, "%.0f%%", this * 100)

private fun Double.formatOne(): String = String.format(Locale.US, "%.1f", this)
