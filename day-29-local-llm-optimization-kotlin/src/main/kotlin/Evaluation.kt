import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong

interface ResourceGateway { fun snapshot(): ResourceSnapshot }

data class PreparedOptimization(val retrieval: RetrievalPackage, val messagesByProfile: Map<String, List<PromptMessage>>)
data class ProfileRun(val outcome: GenerationOutcome, val before: ResourceSnapshot?, val after: ResourceSnapshot?)

class OptimizationService(
    private val retriever: RetrievalEngine,
    private val generator: OptimizationGenerator,
    private val resources: ResourceGateway? = null,
    private val prompts: OptimizationPromptBuilder = OptimizationPromptBuilder(),
    private val validator: GroundingValidator = GroundingValidator(),
) {
    /** Retrieval runs once for this object; all profiles then receive its identical context. */
    fun prepare(index: Week6Index, question: BenchmarkQuestion, profiles: List<OptimizationProfile>): PreparedOptimization {
        val retrieval = retriever.retrieve(index, question.question)
        return PreparedOptimization(retrieval, profiles.associate { it.id to prompts.messages(it, retrieval) })
    }

    fun run(profile: OptimizationProfile, prepared: PreparedOptimization, question: BenchmarkQuestion): ProfileRun {
        val before = snapshotOrNull()
        val outcome = try {
            val reply = generator.generate(profile, checkNotNull(prepared.messagesByProfile[profile.id]))
            val metrics = reply.toMetrics(profile)
            val answer = try { GroundedAnswerParser.parse(reply.content) } catch (error: OptimizationException) {
                return ProfileRun(
                    GenerationOutcome(true, metrics = metrics.copy(error = "invalid_grounded_json: ${error.message}")),
                    before,
                    snapshotOrNull(),
                )
            }
            GenerationOutcome(true, answer, validator.validate(answer, prepared.retrieval, question.expectedAnswerPoints), metrics)
        } catch (error: Exception) {
            GenerationOutcome(false, metrics = GenerationMetrics(profile.model, profile.id, 0, error = errorCode(error)))
        }
        return ProfileRun(outcome, before, snapshotOrNull())
    }

    private fun snapshotOrNull(): ResourceSnapshot? = try { resources?.snapshot() } catch (_: Exception) { null }

    private fun errorCode(error: Exception): String = when (error) {
        is OllamaUnavailableException -> "local_ollama_unavailable"
        is OllamaHttpException -> "local_ollama_http_${error.statusCode}"
        is OllamaProtocolException -> "local_ollama_protocol_error"
        else -> "local_generation_failed"
    }
}

object GroundedAnswerParser {
    fun parse(raw: String): GroundedAnswer {
        val normalized = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val first = normalized.indexOf('{'); val last = normalized.lastIndexOf('}')
        if (first < 0 || last <= first) throw OllamaProtocolException("Model response does not contain a JSON object.")
        return try { AppJson.compact.decodeFromString(normalized.substring(first, last + 1)) } catch (error: Exception) {
            throw OllamaProtocolException("Model response does not match the grounded answer schema: ${error.message?.take(240)}")
        }
    }
}

class GroundingValidator {
    fun validate(answer: GroundedAnswer, retrieval: RetrievalPackage, expectedPoints: List<String>): GroundingValidation {
        val errors = mutableListOf<String>()
        val answered = answer.status == "answered"
        val unknown = answer.status == "unknown"
        val statusValid = answered || unknown
        val answerPresent = answer.answer.isNotBlank()
        val sourcesPresent = !answered || answer.sources.isNotEmpty()
        val quotesPresent = !answered || answer.quotes.isNotEmpty()
        fun sourceMatches(chunkId: String) = retrieval.selected.any { it.chunk.metadata.chunkId == chunkId }
        val sourcesMatch = answer.sources.all(::sourceMatches)
        val quotesMatch = answer.quotes.all { quote -> retrieval.selected.any { normalizedContains(it.chunk.text, quote) } }
        val expectedCovered = expectedPoints.count { point -> expectedPointCovered(point, answer) }
        val unknownValid = !unknown || (answer.sources.isEmpty() && answer.quotes.isEmpty() &&
            answer.answer.lowercase().contains("не знаю") && !answer.clarifyingQuestion.isNullOrBlank())
        if (!statusValid) errors += "status must be answered or unknown"
        if (!answerPresent) errors += "answer is blank"
        if (!sourcesPresent) errors += "answered response has no sources"
        if (!quotesPresent) errors += "answered response has no quotes"
        if (!sourcesMatch) errors += "at least one source was not retrieved"
        if (!quotesMatch) errors += "at least one quote is not a verbatim retrieved fragment"
        if (!unknownValid) errors += "unknown response must say 'не знаю', have no citations, and ask a clarifying question"
        return GroundingValidation(true, statusValid, answerPresent, sourcesPresent, quotesPresent, sourcesMatch, quotesMatch,
            expectedCovered, expectedPoints.size, unknownValid, errors)
    }

    private fun expectedPointCovered(point: String, answer: GroundedAnswer): Boolean {
        val pointTokens = Tokenizer.words(point).filter { it.length > 2 }.distinct()
        if (pointTokens.isEmpty()) return true
        val answerText = "${answer.answer} ${answer.quotes.joinToString(" ")}".lowercase()
        return pointTokens.count { answerText.contains(it) }.toDouble() / pointTokens.size >= 0.5
    }

    /** Index JSON may preserve Markdown's escaped quotes (\\\") while Ollama emits the same command with normal quotes. */
    private fun normalizedContains(text: String, quote: String): Boolean = normalizeQuote(quote).takeIf { it.isNotBlank() }
        ?.let { normalizedQuote -> normalizeQuote(text).contains(normalizedQuote) } ?: false

    private fun normalizeQuote(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\`", "`")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun ChatReply.toMetrics(profile: OptimizationProfile) = GenerationMetrics(
    model = model, profile = profile.id, latencyMs = clientElapsedNanos / 1_000_000, promptTokens = promptTokens,
    completionTokens = completionTokens, outputTokensPerSecond = completionTokens?.let { tokens ->
        val duration = evalDurationNanos?.takeIf { it > 0 } ?: clientElapsedNanos.takeIf { it > 0 }
        duration?.let { tokens * 1_000_000_000.0 / it }
    },
)

object OptimizationMetrics {
    fun aggregate(profile: OptimizationProfile, records: List<OptimizationRecord>): ProfileAggregate {
        val outcomes = records.map { it.outcome }
        val successful = outcomes.filter { it.transportSucceeded }
        val expected = outcomes.mapNotNull { it.validation?.takeIf { validation -> validation.expectedPointsTotal > 0 } }
        val latencies = successful.map { it.metrics.latencyMs }.filter { it > 0 }
        val speeds = successful.mapNotNull { it.metrics.outputTokensPerSecond }.filter { it.isFinite() && it > 0 }
        // `before` can still describe the previous profile after Ollama switches models.
        // The post-generation snapshot is the attributable resource state for this profile.
        val resources = records.mapNotNull { it.after ?: it.before }.flatMap { snapshot ->
            snapshot.runningModels.filter { it.name.matchesModel(profile.model) || it.model?.matchesModel(profile.model) == true }
        }
        return ProfileAggregate(
            profile.id, profile.model, outcomes.size, outcomes.rate { it.transportSucceeded }, outcomes.rate { it.validation?.grounded == true },
            outcomes.rate { it.answer != null }, expected.takeIf { it.isNotEmpty() }?.let { validations ->
                validations.sumOf { it.expectedPointsCovered }.toDouble() / validations.sumOf { it.expectedPointsTotal }
            }, sourceSetStability(records.groupBy { it.questionId }.values.map { rows -> rows.map { it.outcome } }),
            latencies.minOrNull(), latencies.medianLong(), latencies.maxOrNull(), speeds.medianDouble(),
            resources.mapNotNull { it.size }.maxOrNull(), resources.mapNotNull { it.sizeVram }.maxOrNull(), resources.mapNotNull { it.contextLength }.maxOrNull(),
        )
    }

    fun recommendation(aggregates: List<ProfileAggregate>): OptimizationRecommendation {
        val q4 = aggregates.first { it.profile == "optimized-q4" }
        val q8 = aggregates.first { it.profile == "optimized-q8" }
        val groundingDelta = q8.groundedRate - q4.groundedRate
        val coverageDelta = (q8.expectedPointRecall ?: 0.0) - (q4.expectedPointRecall ?: 0.0)
        val delta = maxOf(groundingDelta, coverageDelta)
        val text = if (delta > 0.05) {
            "Q8 improved grounded quality or point coverage by more than 5 p.p.; consider optimized-q8 when its larger memory use is acceptable."
        } else {
            "optimized-q4 remains recommended: Q8 did not improve grounded quality or point coverage by more than 5 p.p."
        }
        return OptimizationRecommendation("optimized-q4", delta, text)
    }

    private fun List<GenerationOutcome>.rate(predicate: (GenerationOutcome) -> Boolean) = if (isEmpty()) 0.0 else count(predicate).toDouble() / size
    private fun sourceSetStability(groups: Collection<List<GenerationOutcome>>): Double? = groups.mapNotNull { group ->
            val sets = group.mapNotNull { outcome -> outcome.answer?.sources?.toSet() }
        if (sets.size < 2) null else buildList {
            sets.indices.forEach { left -> (left + 1 until sets.size).forEach { right ->
                val a = sets[left]; val b = sets[right]
                add(if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / a.union(b).size)
            } }
        }.average()
    }.takeIf { it.isNotEmpty() }?.average()
    private fun List<Long>.medianLong(): Long? = sorted().takeIf { it.isNotEmpty() }?.let { values ->
        if (values.size % 2 == 1) values[values.size / 2] else ((values[values.size / 2 - 1] + values[values.size / 2]) / 2.0).roundToLong()
    }
    private fun List<Double>.medianDouble(): Double? = sorted().takeIf { it.isNotEmpty() }?.let { values ->
        if (values.size % 2 == 1) values[values.size / 2] else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
    }
}

class ReportStorage(private val config: AppConfig) {
    fun save(records: List<OptimizationRecord>): Pair<OptimizationReport, java.nio.file.Path> {
        val aggregates = config.profiles.map { profile -> OptimizationMetrics.aggregate(profile, records.filter { it.profile == profile.id }) }
        val report = OptimizationReport(Instant.now().toString(), config.week6IndexFile.toAbsolutePath().toString(), config.embeddingModel,
            config.benchmarkRuns, records, aggregates, OptimizationMetrics.recommendation(aggregates))
        Files.createDirectories(config.reportsDir)
        val stamp = report.generatedAtIso.replace(':', '-').replace('.', '-')
        val jsonPath = config.reportsDir.resolve("benchmark-$stamp.json")
        val markdownPath = config.reportsDir.resolve("benchmark-$stamp.md")
        Files.writeString(jsonPath, AppJson.pretty.encodeToString(OptimizationReport.serializer(), report))
        Files.writeString(markdownPath, renderMarkdown(report))
        return report to jsonPath
    }

    private fun renderMarkdown(report: OptimizationReport) = buildString {
        appendLine("# Day 29 Local LLM Optimization")
        appendLine(); appendLine("- Index: `${report.indexFile}`"); appendLine("- Embedding: `${report.embeddingModel}`")
        appendLine("- Runs per question: ${report.runsPerQuestion}"); appendLine(); appendLine("| Profile | Grounded | Point recall | Latency median | Output tok/s | Model memory |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        report.aggregates.forEach { row -> appendLine("| ${row.profile} | ${row.groundedRate.percent()} | ${row.expectedPointRecall?.percent() ?: "n/a"} | ${row.latencyMedianMs ?: "n/a"} ms | ${row.outputTokensPerSecondMedian?.one() ?: "n/a"} | ${row.maxModelBytes?.bytes() ?: "n/a"} |") }
        appendLine(); appendLine("Recommendation: ${report.recommendation.text}")
    }
}

private fun String.matchesModel(required: String) = equals(required, true) || removeSuffix(":latest").equals(required.removeSuffix(":latest"), true)
private fun Double.percent() = String.format(Locale.US, "%.0f%%", this * 100)
private fun Double.one() = String.format(Locale.US, "%.1f", this)
fun Long.bytes() = String.format(Locale.US, "%.2f GiB", this / 1024.0 / 1024.0 / 1024.0)
