package ru.ai.course.day32.codereview

class ReviewPipeline(
    private val config: AppConfig,
    private val generator: ReviewGenerator,
    private val parser: DiffParser = DiffParser(),
    private val validator: ReviewValidator = ReviewValidator(),
    private val renderer: MarkdownReviewRenderer = MarkdownReviewRenderer(),
    private val cloudContentPolicy: CloudContentPolicy = CloudContentPolicy(),
) {
    fun review(snapshot: PullRequestSnapshot, corpus: LoadedCorpus): ReviewResult {
        require(!snapshot.metadata.draft) { "Draft pull requests are not reviewed." }
        require(!snapshot.metadata.fromFork) { "Fork pull requests are not reviewed by the privileged workflow." }
        val plan = preparePlan(snapshot, corpus)
        val generated = mutableListOf<ValidatedFinding>()
        val reviewedPaths = linkedSetOf<String>()
        var model = config.llmModel
        plan.prompts.forEach { prepared ->
            if (prepared.input.files.isEmpty() || prepared.input.evidenceItems.isEmpty()) return@forEach
            cloudContentPolicy.requireSafePrompt(prepared.prompt.user)
            val reply = generator.generate(prepared.prompt)
            model = reply.model
            val validation = validator.validate(reply.content, prepared.input)
            if (!validation.valid) throw ModelValidationException(validation.errors)
            generated += validation.findings
            reviewedPaths += prepared.input.reviewedPaths
        }
        val findings = validator.merge(generated)
        val coverage = coverage(snapshot, plan.prompts, reviewedPaths, plan.corpusMetrics)
        val markdown = renderer.render(snapshot.metadata, findings, coverage, model)
        return ReviewResult(snapshot.metadata, findings, coverage, model, markdown)
    }

    fun prepare(snapshot: PullRequestSnapshot, corpus: LoadedCorpus): List<PreparedReviewPrompt> =
        preparePlan(snapshot, corpus).prompts

    private fun preparePlan(snapshot: PullRequestSnapshot, corpus: LoadedCorpus): PreparedPlan {
        cloudContentPolicy.requireSafe(snapshot.files)
        val parsed = snapshot.files.mapNotNull { file ->
            file.patch?.takeIf(String::isNotBlank)?.let { parser.parse(file.path, it) }
        }
        val chunks = StructuredChunker(config.limits.chunkLines, config.limits.maxChunks)
            .chunk(corpus.documents)
        val effectiveCorpusMetrics = if (chunks.size >= config.limits.maxChunks) {
            corpus.metrics.copy(truncated = true)
        } else {
            corpus.metrics
        }
        val prompts = ReviewPlanner(config.limits).plan(snapshot.files, parsed, chunks)
            .map { PromptBuilder(config.limits.promptBytes, cloudContentPolicy).build(it) }
        return PreparedPlan(prompts, effectiveCorpusMetrics)
    }

    private fun coverage(
        snapshot: PullRequestSnapshot,
        prompts: List<PreparedReviewPrompt>,
        reviewedPaths: Set<String>,
        corpusMetrics: CorpusMetrics,
    ): ReviewCoverage {
        val transmittedPaths = prompts.flatMapTo(linkedSetOf()) { it.input.reviewedPaths }
        val omittedEvidenceItems = prompts.sumOf { it.input.omittedEvidenceCount }
        val contentOmittedFiles = prompts.sumOf { it.input.contentOmittedFileCount }
        val notes = buildList {
            if (snapshot.metadata.changedFiles > snapshot.files.size) {
                add(
                    "Лимит изменённых файлов: получено ${snapshot.files.size} из " +
                        "${snapshot.metadata.changedFiles}; оставшиеся файлы не анализировались.",
                )
            }
            val omittedFiles = snapshot.files.map(ChangedFile::path).toSet() - transmittedPaths
            if (omittedFiles.isNotEmpty()) {
                add(
                    "${omittedFiles.size} fetched file(s) не были переданы модели: " +
                        "binary/removed, incomplete patch или prompt budget.",
                )
            }
            if (transmittedPaths.size > reviewedPaths.size) {
                add("${transmittedPaths.size - reviewedPaths.size} transmitted file(s) не получили model review.")
            }
            if (contentOmittedFiles > 0) {
                add("$contentOmittedFiles file content item(s) omitted; complete patches remained transmitted.")
            }
            if (omittedEvidenceItems > 0) {
                add("$omittedEvidenceItems complete RAG evidence item(s) omitted by prompt budget.")
            }
            if (prompts.any { it.input.upstreamEvidenceTruncated }) {
                add("RAG retrieval/evidence budget omitted one or more whole evidence items.")
            }
            if (snapshot.fullDiffTruncated) add("GitHub full-diff endpoint был недоступен или ограничен.")
            if (snapshot.files.any(ChangedFile::patchTruncated)) add("Некоторые patch отсутствовали или были обрезаны GitHub.")
            if (snapshot.files.any(ChangedFile::contentTruncated)) add("Некоторые содержимые файлов не вошли в лимит.")
            if (corpusMetrics.truncated) add("RAG-корпус ограничен настроенными лимитами.")
        }
        return ReviewCoverage(
            reportedChangedFiles = snapshot.metadata.changedFiles,
            fetchedChangedFiles = snapshot.files.size,
            reviewedFiles = reviewedPaths.size,
            binaryFiles = snapshot.files.count(ChangedFile::binary),
            patchTruncatedFiles = snapshot.files.count(ChangedFile::patchTruncated),
            contentTruncatedFiles = snapshot.files.count(ChangedFile::contentTruncated),
            diffEndpointAvailable = snapshot.fullDiff != null && !snapshot.fullDiffTruncated,
            corpusMetrics = corpusMetrics,
            notes = notes,
        )
    }

    private data class PreparedPlan(
        val prompts: List<PreparedReviewPrompt>,
        val corpusMetrics: CorpusMetrics,
    )
}
