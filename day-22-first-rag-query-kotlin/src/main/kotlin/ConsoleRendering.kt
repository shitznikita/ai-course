fun printRetrieved(results: List<SearchResult>) {
    results.forEachIndexed { index, result ->
        val chunk = result.chunk
        println("${index + 1}. score=${result.score.formatDigits()} source=${chunk.metadata.source}")
        println("   title=${chunk.metadata.title}")
        println("   section=${chunk.metadata.section}")
        println("   chunk_id=${chunk.metadata.chunkId}")
        println("   preview=${chunk.text.shortPreview(220)}")
    }
}

fun printAnswer(run: AnswerRun) {
    println(run.mode.uppercase())
    if (run.warningOrError != null) {
        println("warning/error: ${run.warningOrError}")
    }
    println(run.answer.ifBlank { "(empty)" })
    if (run.usage != null) {
        val usage = run.usage
        println("usage: prompt=${usage.promptTokens ?: "?"}, completion=${usage.completionTokens ?: "?"}, total=${usage.totalTokens ?: "?"}, cost=${usage.costUsd ?: "?"}")
    }
    if (run.retrievedChunks.isNotEmpty()) {
        println("sources:")
        run.retrievedChunks.forEach {
            println("- score=${it.score.formatDigits()} ${it.source} / ${it.section} / ${it.chunkId}")
        }
    }
}

fun printEvaluation(report: EvaluationReport) {
    println("EVALUATION: ${report.mode}")
    println("questions: ${report.questionCount}")
    println("expected source hits: ${report.expectedSourceHitCount}/${report.records.sumOf { it.expectedSources.size }}")
    println()
    report.records.forEach { record ->
        println("${record.id}. ${record.question}")
        println("   expected sources: ${record.expectedSources.joinToString()}")
        println("   retrieved sources: ${record.retrievedSources.joinToString()}")
        println("   hits: ${record.expectedSourceHits.joinToString().ifBlank { "none" }}")
    }
}
