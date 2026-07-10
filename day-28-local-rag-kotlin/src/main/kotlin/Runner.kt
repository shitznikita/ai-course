import kotlinx.serialization.decodeFromString
import java.nio.file.Files

class Day28Runner(private val config: AppConfig) {
    private val localClient = LocalOllamaClient(config)
    private val indexReader = Week6IndexReader(config)
    private val retriever = LocalRetriever(localClient, config)
    private val comparison = RagComparisonService(
        retriever = retriever,
        localGenerator = localClient,
        cloudGenerator = CloudClient(config),
    )

    fun diagnose(): Boolean {
        println("Day 28: Local RAG + cloud comparison")
        println("LOCAL OLLAMA: ${config.ollamaBaseUrl}")
        val status = localClient.diagnose()
        println("OLLAMA VERSION: ${status.version}")
        val localReady = status.hasModel(config.localModel)
        val embeddingsReady = status.hasModel(config.embeddingModel)
        println("LOCAL CHAT MODEL: ${config.localModel} (${if (localReady) "installed" else "missing"})")
        println("LOCAL EMBED MODEL: ${config.embeddingModel} (${if (embeddingsReady) "installed" else "missing"})")

        if (!localReady) println("NEXT: ollama pull ${config.localModel}")
        if (!embeddingsReady) println("NEXT: ollama pull ${config.embeddingModel}")
        if (!localReady || !embeddingsReady) return false

        val index = indexReader.load()
        val queryEmbedding = localClient.embed(listOf("Day 28 local RAG compatibility diagnostic")).embeddings.single()
        Week6IndexValidator.validateQueryEmbedding(index, queryEmbedding)
        println("WEEK 6 INDEX: ${config.week6IndexFile.toAbsolutePath()}")
        println("INDEX: ${index.chunkCount} chunks, ${index.embeddingDimensions} dimensions, ${index.embeddingModel}")
        println("QUERY VECTOR: ${queryEmbedding.size} dimensions (compatible)")
        val missingBenchmarkSources = missingBenchmarkSources(index, loadBenchmarkQuestions())
        if (missingBenchmarkSources.isEmpty()) {
            println("BENCHMARK SOURCES: ready")
        } else {
            println("BENCHMARK SOURCES: missing ${missingBenchmarkSources.joinToString()}")
            println("NEXT: ${rebuildIndexHint(config.embeddingModel)}")
        }
        println("CLOUD: ${if (config.cloudConfigured) "configured" else "missing LLM_API_KEY"}")
        println("CLOUD MODEL: ${config.cloudModel}")
        println("CLOUD HOST: ${config.cloudEndpointHost}")
        println("CHECK: local retrieval and local qwen3 generation are ready; compare/benchmark additionally require cloud config and benchmark source coverage.")
        return missingBenchmarkSources.isEmpty()
    }

    fun local(question: String): Boolean {
        val run = comparison.runLocal(indexReader.load(), customQuestion(question))
        printRetrieval(run.retrieval)
        printOutcome("LOCAL", run.local)
        return run.local.isGroundedSuccess()
    }

    fun compare(question: String): Boolean {
        requireCloudConfiguration()
        val run = comparison.runComparison(indexReader.load(), customQuestion(question))
        printRetrieval(run.retrieval)
        printOutcome("LOCAL", run.local)
        printOutcome("CLOUD", requireNotNull(run.cloud))
        return run.local.isGroundedSuccess() && run.cloud.isGroundedSuccess()
    }

    fun benchmark(): Boolean {
        requireCloudConfiguration()
        val index = indexReader.load()
        val questions = loadBenchmarkQuestions()
        val missingSources = missingBenchmarkSources(index, questions)
        if (missingSources.isNotEmpty()) {
            throw IndexValidationException(
                "Week 6 index does not contain benchmark source(s): ${missingSources.joinToString()}. " +
                    rebuildIndexHint(config.embeddingModel),
            )
        }
        val records = mutableListOf<BenchmarkRecord>()

        println("Day 28: benchmark")
        println("QUESTIONS: ${questions.size}; RUNS PER QUESTION: ${config.benchmarkRuns}")
        println("RETRIEVAL: local ${config.embeddingModel}; GENERATION: local ${config.localModel} + cloud ${config.cloudModel}")
        println()

        questions.forEach { question ->
            println("========== ${question.id}: ${question.question} ==========")
            val prepared = comparison.prepare(index, question)
            val firstRun = comparison.runComparison(prepared, question)
            printRetrieval(firstRun.retrieval)
            records += firstRun.toRecord(question, iteration = 1)
            printBenchmarkLine(1, firstRun)

            (2..config.benchmarkRuns).forEach { iteration ->
                val repeated = comparison.runComparison(prepared, question)
                records += repeated.toRecord(question, iteration)
                printBenchmarkLine(iteration, repeated)
            }
            println()
        }

        val report = BenchmarkMetrics.newReport(config, records)
        val (jsonPath, markdownPath) = ReportStorage(config).save(report)
        printAggregateTable(report)
        println("REPORT JSON: ${jsonPath.toAbsolutePath()}")
        println("REPORT MARKDOWN: ${markdownPath.toAbsolutePath()}")
        println("CHECK: retrieval ran locally once per question; both models received the same rendered RAG prompt on every comparison.")
        if (records.any { !it.local.isGroundedSuccess() || !it.cloud.isGroundedSuccess() }) {
            println("QUALITY CHECK: at least one response was not grounded; the completed report records the failure rate.")
        }
        return records.all { it.local.transportSucceeded && it.cloud.transportSucceeded }
    }

    private fun loadBenchmarkQuestions(): List<BenchmarkQuestion> {
        val path = config.benchmarkQuestionsFile
        if (!Files.exists(path)) throw ConfigurationException("Benchmark questions file is missing: ${path.toAbsolutePath()}")
        val questions = try {
            AppJson.compact.decodeFromString<List<BenchmarkQuestion>>(Files.readString(path))
        } catch (error: Exception) {
            throw ConfigurationException("Benchmark questions cannot be parsed: ${error.message}")
        }
        if (questions.size != 3 || questions.map { it.id }.toSet().size != 3 || questions.any { it.question.isBlank() }) {
            throw ConfigurationException("Benchmark questions file must contain exactly three unique non-empty questions.")
        }
        return questions
    }

    private fun requireCloudConfiguration() {
        if (!config.cloudConfigured) {
            throw CloudConfigurationException(
                "LLM_API_KEY is required for compare and benchmark. Add it locally to day-01-llm-rest-kotlin/.env or day-28-local-rag-kotlin/.env; never commit it.",
            )
        }
    }

    private fun customQuestion(question: String): BenchmarkQuestion {
        val normalized = question.trim()
        if (normalized.isBlank()) throw ConfigurationException("Question must not be blank.")
        return BenchmarkQuestion("custom", normalized, emptyList(), emptyList())
    }

    private fun missingBenchmarkSources(index: Week6Index, questions: List<BenchmarkQuestion>): List<String> {
        val availableSources = index.chunks.map { it.metadata.source }.toSet()
        return questions
            .flatMap { it.expectedSources }
            .distinct()
            .filterNot(availableSources::contains)
    }

    private fun printRetrieval(retrieval: RetrievalPackage) {
        println()
        println("LOCAL RETRIEVAL")
        println("context tokens: ${retrieval.contextTokens}")
        if (retrieval.selected.isEmpty()) {
            println("  (no chunk fits the configured context budget)")
            return
        }
        retrieval.selected.forEachIndexed { position, result ->
            val metadata = result.chunk.metadata
            println("  ${position + 1}. score=${"%.4f".format(java.util.Locale.US, result.score)} ${metadata.source} / ${metadata.section} / ${metadata.chunkId}")
        }
    }

    private fun printOutcome(label: String, outcome: GenerationOutcome) {
        println()
        println("$label: ${outcome.metrics.model}")
        println("transport: ${if (outcome.transportSucceeded) "ok" else "failed"}")
        println("latency: ${outcome.metrics.latencyMs} ms")
        println("tokens: input=${outcome.metrics.promptTokens ?: "n/a"}, output=${outcome.metrics.completionTokens ?: "n/a"}")
        println("speed: ${outcome.metrics.outputTokensPerSecond?.let { "%.1f tokens/s".format(java.util.Locale.US, it) } ?: "n/a"}")
        outcome.metrics.error?.let {
            println("result: $it")
            return
        }
        val answer = outcome.answer ?: run {
            println("result: invalid_grounded_json")
            return
        }
        println("status: ${answer.status}")
        println("answer: ${answer.answer}")
        if (answer.sources.isNotEmpty()) {
            println("sources:")
            answer.sources.forEach { println("  - ${it.source} / ${it.section} / ${it.chunkId}") }
        }
        if (answer.quotes.isNotEmpty()) {
            println("quotes:")
            answer.quotes.forEach { println("  - ${it.text}") }
        }
        val validation = outcome.validation
        if (validation != null) {
            println("grounded: ${validation.grounded}; expected points=${validation.expectedPointsCovered}/${validation.expectedPointsTotal}; errors=${validation.errors.ifEmpty { listOf("none") }.joinToString()}")
        }
    }

    private fun printBenchmarkLine(iteration: Int, run: ComparisonRun) {
        fun compact(outcome: GenerationOutcome): String {
            val quality = outcome.validation?.grounded?.toString() ?: "false"
            return "${outcome.metrics.kind}: transport=${outcome.transportSucceeded}, grounded=$quality, latency=${outcome.metrics.latencyMs}ms${outcome.metrics.error?.let { ", error=$it" } ?: ""}"
        }
        println("run $iteration — ${compact(run.local)} | ${compact(requireNotNull(run.cloud))}")
    }

    private fun ComparisonRun.toRecord(question: BenchmarkQuestion, iteration: Int): BenchmarkRecord = BenchmarkRecord(
        questionId = question.id,
        question = question.question,
        iteration = iteration,
        retrievedChunks = retrieval.selected.map { it.toSummary() },
        local = local,
        cloud = requireNotNull(cloud),
    )

    private fun printAggregateTable(report: BenchmarkReport) {
        println()
        println("QUALITY / SPEED / STABILITY")
        println("MODEL | transport | grounded | JSON | point recall | source stability | latency min/median/max | output tok/s")
        listOf(report.local, report.cloud).forEach { aggregate ->
            println(
                "${aggregate.kind}:${aggregate.model} | ${aggregate.transportSuccessRate.asPercent()} | " +
                    "${aggregate.groundedRate.asPercent()} | ${aggregate.validJsonRate.asPercent()} | " +
                    "${aggregate.expectedPointRecall?.asPercent() ?: "n/a"} | " +
                    "${aggregate.sourceSetStability?.asPercent() ?: "n/a"} | " +
                    "${aggregate.latencyMinMs ?: "n/a"}/${aggregate.latencyMedianMs ?: "n/a"}/${aggregate.latencyMaxMs ?: "n/a"} ms | " +
                    "${aggregate.outputTokensPerSecondMedian?.let { "%.1f".format(java.util.Locale.US, it) } ?: "n/a"}",
            )
        }
    }
}

private fun GenerationOutcome.isGroundedSuccess(): Boolean =
    transportSucceeded && validation?.grounded == true

private fun Double.asPercent(): String = "%.0f%%".format(java.util.Locale.US, this * 100)
