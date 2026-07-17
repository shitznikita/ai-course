package ru.ai.course.day32.codereview

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "help"
    try {
        when (mode) {
            "fixture-demo" -> fixtureDemo()
            "eval-dry-run" -> evaluationDryRun()
            "prompt-dry-run" -> promptDryRun()
            "live-fixture" -> liveFixture()
            "ci" -> ciReview()
            "help", "--help", "-h" -> printHelp()
            else -> error("Unknown mode '$mode'. Run with --args=\"help\".")
        }
    } catch (error: Throwable) {
        System.err.println("ERROR: ${ReviewFailureDiagnostics.message(error)}")
        exitProcess(1)
    }
}

private fun fixtureDemo() {
    val config = AppConfig.load()
    val context = FixtureReviewGenerator.create(config.repositoryRoot)
    val result = FixtureReviewGenerator.run(config)
    val prepared = ReviewPipeline(config, ReviewGenerator { error("offline") }).prepare(context.snapshot, context.corpus)
    val evidence = prepared.flatMap { it.input.evidenceItems }
    println("Fixture review: changedFiles=${context.snapshot.files.size}, additions=${context.snapshot.files.sumOf { it.additions }}")
    println(
        "RAG evidence: documentation=${evidence.count { it.chunk.category == CorpusCategory.DOCUMENTATION }}, " +
            "code=${evidence.count { it.chunk.category == CorpusCategory.CODE }}",
    )
    evidence.distinctBy { it.chunk.id }.forEach {
        println("- ${it.chunk.id} ${it.chunk.category} ${it.chunk.path}:${it.chunk.startLine}-${it.chunk.endLine}")
    }
    println()
    println(result.markdown)
}

private fun evaluationDryRun() {
    val path = Path.of("eval/review-cases.json")
    val summary = Evaluation.run(path)
    summary.lines.forEach(::println)
    println("Evaluation: ${summary.passed}/${summary.total} passed")
    check(summary.passed == summary.total) { "Evaluation cases failed." }
}

private fun promptDryRun() {
    val config = AppConfig.load()
    val context = FixtureReviewGenerator.create(config.repositoryRoot)
    val prepared = ReviewPipeline(config, ReviewGenerator { error("offline") }).prepare(context.snapshot, context.corpus)
    prepared.forEach { item ->
        println(
            "batch=${item.input.batchIndex} transmittedFiles=${item.input.files.size} " +
                "omittedFiles=${item.input.omittedFileCount} " +
                "promptBytes=${item.prompt.bytes}/${item.prompt.maxBytes} " +
                "evidenceIds=${item.input.sourceIds.joinToString(",")}",
        )
        println(item.prompt.preview)
    }
}

private fun liveFixture() {
    val config = AppConfig.load()
    require(!config.llmApiKey.isNullOrBlank()) { "LLM_API_KEY is required for live-fixture." }
    val context = FixtureReviewGenerator.create(config.repositoryRoot)
    val result = ReviewPipeline(config, ElizaLlmClient(config)).review(context.snapshot, context.corpus)
    val report = Path.of("reports/review.md")
    report.parent.createDirectories()
    report.writeText(result.markdown)
    println("Live fixture review written to $report; findings=${result.findings.size}; model=${result.model}")
}

private fun ciReview() {
    val config = AppConfig.load()
    var publisher: GitHubReviewPublisher? = runCatching { GitHubReviewPublisher(config) }.getOrNull()
    try {
        config.requireCiValues()
        val activePublisher = publisher ?: GitHubReviewPublisher(config).also { publisher = it }
        val snapshot = GitHubPullRequestClient(config).fetch()
        val corpus = RepositoryCorpusLoader(config.repositoryRoot, config.limits).load(snapshot.files.map(ChangedFile::path))
        val result = ReviewPipeline(config, ElizaLlmClient(config)).review(snapshot, corpus)
        val operation = activePublisher.publish(result.markdown)
        activePublisher.writeStepSummary(result.markdown)
        println(
            "AI review ${operation.name.lowercase()}: findings=${result.findings.size}, " +
                "coverage=${result.coverage.reviewedFiles}/${result.coverage.reportedChangedFiles}",
        )
    } catch (error: Throwable) {
        val message = ReviewFailureDiagnostics.message(error)
        val diagnostic = MarkdownReviewRenderer().renderDiagnostic(message)
        runCatching { publisher?.publish(diagnostic) }
        runCatching { writeDiagnosticSummary(diagnostic) }
        throw IllegalStateException(message)
    }
}

private fun writeDiagnosticSummary(markdown: String) {
    val path = System.getenv("GITHUB_STEP_SUMMARY")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return
    path.parent?.let(Files::createDirectories)
    Files.writeString(
        path,
        markdown + "\n",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND,
    )
}

private fun printHelp() {
    println(
        """
        Day 32 — automatic AI code review

        Modes:
          fixture-demo    deterministic offline review with BUG/ARCHITECTURE/RECOMMENDATION
          eval-dry-run    run committed validation and safety cases
          prompt-dry-run  print bounded prompt metadata and untrusted-data delimiters
          live-fixture    one Eliza call; write reports/review.md; never post to GitHub
          ci              fetch PR data, review, update sticky comment and step summary
        """.trimIndent(),
    )
}
