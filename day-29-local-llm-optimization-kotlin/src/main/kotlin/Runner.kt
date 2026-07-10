import kotlinx.serialization.decodeFromString
import java.nio.file.Files

class Day29Runner(private val config: AppConfig) {
    private val client = LocalOllamaClient(config)
    private val indexReader = Week6IndexReader(config)
    private val service = OptimizationService(LocalRetriever(client, config), client, client)
    private val prompts = OptimizationPromptBuilder()

    fun diagnose(): Boolean {
        println("Day 29: optimize local RAG on Qwen3")
        println("LOCAL OLLAMA: ${config.ollamaBaseUrl}")
        val status = client.diagnose()
        println("OLLAMA VERSION: ${status.version}")
        val required = listOf(config.q4Model, config.q8Model, config.embeddingModel)
        val ready = required.associateWith(status::hasModel)
        println("Q4 MODEL: ${config.q4Model} (${ready.getValue(config.q4Model).state()})")
        println("Q8 MODEL: ${config.q8Model} (${ready.getValue(config.q8Model).state()})")
        println("EMBED MODEL: ${config.embeddingModel} (${ready.getValue(config.embeddingModel).state()})")
        if (!ready.getValue(config.q4Model)) println("NEXT: ollama pull ${config.q4Model}")
        if (!ready.getValue(config.q8Model)) println("NEXT: ollama pull ${config.q8Model}")
        if (!ready.getValue(config.embeddingModel)) println("NEXT: ollama pull ${config.embeddingModel}")
        if (ready.values.any { !it }) return false

        listOf(config.q4Model, config.q8Model).forEach { model ->
            val details = client.show(model)
            println("MODEL INFO: $model; parameters=${details.parameterSize ?: "n/a"}; quantization=${details.quantizationLevel ?: "n/a"}; native context=${details.contextLength ?: "n/a"}")
        }
        val index = indexReader.load()
        val vector = client.embed(listOf("Day 29 local RAG optimization compatibility diagnostic")).embeddings.single()
        Week6IndexValidator.validateQueryEmbedding(index, vector)
        println("WEEK 6 INDEX: ${config.week6IndexFile.toAbsolutePath()}")
        println("INDEX: ${index.chunkCount} chunks, ${index.embeddingDimensions} dimensions, ${index.embeddingModel}")
        println("QUERY VECTOR: ${vector.size} dimensions (compatible)")
        val missingSources = missingBenchmarkSources(index, loadBenchmarkQuestions())
        if (missingSources.isEmpty()) println("BENCHMARK SOURCES: ready") else {
            println("BENCHMARK SOURCES: missing ${missingSources.joinToString()}")
            println("NEXT: ${rebuildIndexHint(config.embeddingModel)}")
        }
        println("CHECK: all generation, embeddings and resource snapshots stay on loopback Ollama; no cloud configuration exists.")
        return missingSources.isEmpty()
    }

    fun profile(profileId: String, questionText: String): Boolean {
        val profile = config.profile(profileId)
        val question = customQuestion(questionText)
        val prepared = service.prepare(indexReader.load(), question, config.profiles)
        println("PROFILE: ${profile.id} — ${profile.label}")
        println("PARAMETERS: model=${profile.model}; temperature=${profile.temperature}; num_predict=${profile.numPredict}; num_ctx=${profile.numCtx}")
        println("PROMPT: ${if (profile.optimizedPrompt) "strict grounded template" else "neutral baseline template"}")
        printRetrieval(prepared.retrieval)
        val run = service.run(profile, prepared, question)
        printOutcome(run.outcome, prepared.retrieval)
        printResources(run.after)
        return run.outcome.transportSucceeded
    }

    fun benchmark(): Boolean {
        val index = indexReader.load()
        val questions = loadBenchmarkQuestions()
        val missingSources = missingBenchmarkSources(index, questions)
        if (missingSources.isNotEmpty()) throw IndexValidationException(
            "Week 6 index does not contain benchmark source(s): ${missingSources.joinToString()}. ${rebuildIndexHint(config.embeddingModel)}",
        )
        println("Day 29: baseline → optimized local RAG benchmark")
        println("QUESTIONS: ${questions.size}; RUNS PER QUESTION: ${config.benchmarkRuns}; GENERATION RUNS: ${questions.size * config.benchmarkRuns * config.profiles.size}")
        println("RETRIEVAL: local ${config.embeddingModel}; CLOUD: disabled")
        val records = mutableListOf<OptimizationRecord>()
        questions.forEach { question ->
            println()
            println("========== ${question.id}: ${question.question} ==========")
            val prepared = service.prepare(index, question, config.profiles)
            printRetrieval(prepared.retrieval)
            (1..config.benchmarkRuns).forEach { iteration ->
                config.profiles.forEach { profile ->
                    val run = service.run(profile, prepared, question)
                    records += OptimizationRecord(
                        question.id, question.question, iteration, profile.id, profile.label, profile.temperature,
                        profile.numPredict, profile.numCtx, prepared.retrieval.selected.map { it.toSummary() }, run.outcome, run.before, run.after,
                    )
                    printCompact(iteration, profile, run.outcome, run.after)
                }
            }
        }
        val (report, jsonPath) = ReportStorage(config).save(records)
        printAggregateTable(report)
        println("REPORT JSON: ${jsonPath.toAbsolutePath()}")
        println("REPORT MARKDOWN: ${jsonPath.resolveSibling(jsonPath.fileName.toString().removeSuffix(".json") + ".md").toAbsolutePath()}")
        println("RECOMMENDATION: ${report.recommendation.text}")
        println("CHECK: retrieval executed exactly once per question; each profile received that question's same rendered local context.")
        return records.all { it.outcome.transportSucceeded }
    }

    private fun loadBenchmarkQuestions(): List<BenchmarkQuestion> {
        val path = config.benchmarkQuestionsFile
        if (!Files.exists(path)) throw ConfigurationException("Benchmark questions file is missing: ${path.toAbsolutePath()}")
        val questions = try { AppJson.compact.decodeFromString<List<BenchmarkQuestion>>(Files.readString(path)) } catch (error: Exception) {
            throw ConfigurationException("Benchmark questions cannot be parsed: ${error.message}")
        }
        if (questions.size != 3 || questions.map { it.id }.toSet().size != 3 || questions.any { it.question.isBlank() }) {
            throw ConfigurationException("Benchmark questions file must contain exactly three unique non-empty questions.")
        }
        return questions
    }

    private fun missingBenchmarkSources(index: Week6Index, questions: List<BenchmarkQuestion>): List<String> {
        val available = index.chunks.map { it.metadata.source }.toSet()
        return questions.flatMap { it.expectedSources }.distinct().filterNot(available::contains)
    }

    private fun customQuestion(question: String): BenchmarkQuestion {
        val normalized = question.trim()
        if (normalized.isBlank()) throw ConfigurationException("Question must not be blank.")
        return BenchmarkQuestion("custom", normalized, emptyList(), emptyList())
    }

    private fun printRetrieval(retrieval: RetrievalPackage) {
        println("LOCAL RETRIEVAL: context=${retrieval.contextTokens} approximate tokens")
        retrieval.selected.forEachIndexed { position, result ->
            val metadata = result.chunk.metadata
            println("  ${position + 1}. score=${"%.4f".format(java.util.Locale.US, result.score)} ${metadata.source} / ${metadata.section} / ${metadata.chunkId}")
        }
    }

    private fun printOutcome(outcome: GenerationOutcome, retrieval: RetrievalPackage) {
        println("RESULT: transport=${outcome.transportSucceeded}; grounded=${outcome.validation?.grounded ?: false}; latency=${outcome.metrics.latencyMs}ms")
        println("TOKENS: input=${outcome.metrics.promptTokens ?: "n/a"}; output=${outcome.metrics.completionTokens ?: "n/a"}; speed=${outcome.metrics.outputTokensPerSecond?.let { "%.1f tok/s".format(java.util.Locale.US, it) } ?: "n/a"}")
        outcome.metrics.error?.let { println("ERROR: $it"); return }
        outcome.answer?.let { answer ->
            println("ANSWER: ${answer.answer}")
            answer.sources.forEach { chunkId ->
                val metadata = retrieval.selected.firstOrNull { it.chunk.metadata.chunkId == chunkId }?.chunk?.metadata
                println("SOURCE: ${metadata?.source ?: "unknown"} / ${metadata?.section ?: "unknown"} / $chunkId")
            }
            answer.quotes.forEach { println("QUOTE: $it") }
        }
        outcome.validation?.let { println("QUALITY: expected points=${it.expectedPointsCovered}/${it.expectedPointsTotal}; errors=${it.errors.ifEmpty { listOf("none") }.joinToString()}") }
    }

    private fun printCompact(iteration: Int, profile: OptimizationProfile, outcome: GenerationOutcome, resources: ResourceSnapshot?) {
        val memory = resources?.runningModels?.firstOrNull { it.name.matchesModelForPrint(profile.model) || it.model?.matchesModelForPrint(profile.model) == true }
        println("run $iteration | ${profile.id} | transport=${outcome.transportSucceeded} | grounded=${outcome.validation?.grounded ?: false} | latency=${outcome.metrics.latencyMs}ms | output=${outcome.metrics.outputTokensPerSecond?.let { "%.1f tok/s".format(java.util.Locale.US, it) } ?: "n/a"} | model_mem=${memory?.size?.bytes() ?: "n/a"}")
    }

    private fun printResources(resources: ResourceSnapshot?) {
        if (resources == null) return
        println("RESOURCES: memory_pressure=${resources.memoryPressure ?: "unavailable"}")
        resources.runningModels.forEach { model -> println("  ${model.name}: size=${model.size?.bytes() ?: "n/a"}; vram=${model.sizeVram?.bytes() ?: "n/a"}; context=${model.contextLength ?: "n/a"}; processor=${model.processor ?: "n/a"}") }
    }

    private fun printAggregateTable(report: OptimizationReport) {
        println(); println("QUALITY / SPEED / RESOURCES")
        println("PROFILE | grounded | JSON | point recall | source stability | latency min/median/max | output tok/s | model memory | context")
        report.aggregates.forEach { row -> println(
            "${row.profile} | ${row.groundedRate.asPercent()} | ${row.validJsonRate.asPercent()} | ${row.expectedPointRecall?.asPercent() ?: "n/a"} | " +
                "${row.sourceSetStability?.asPercent() ?: "n/a"} | ${row.latencyMinMs ?: "n/a"}/${row.latencyMedianMs ?: "n/a"}/${row.latencyMaxMs ?: "n/a"} ms | " +
                "${row.outputTokensPerSecondMedian?.let { "%.1f".format(java.util.Locale.US, it) } ?: "n/a"} | ${row.maxModelBytes?.bytes() ?: "n/a"} | ${row.maxContextLength ?: "n/a"}",
        ) }
    }
}

private fun Boolean.state() = if (this) "installed" else "missing"
private fun Double.asPercent() = "%.0f%%".format(java.util.Locale.US, this * 100)
private fun String.matchesModelForPrint(required: String) = equals(required, true) || removeSuffix(":latest").equals(required.removeSuffix(":latest"), true)
