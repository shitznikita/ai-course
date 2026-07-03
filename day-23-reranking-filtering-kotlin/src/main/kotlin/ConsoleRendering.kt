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

fun printReranked(retrieval: RerankedRetrieval) {
    println("ORIGINAL QUERY")
    println(retrieval.question)
    println()
    println("REWRITTEN QUERY")
    println(retrieval.rewrite.rewritten)
    if (retrieval.rewrite.addedHints.isNotEmpty()) {
        println("added hints: ${retrieval.rewrite.addedHints.joinToString()}")
    }
    println()
    println("CANDIDATES BEFORE FILTER: ${retrieval.before.size}")
    retrieval.before.forEachIndexed { index, result ->
        val chunk = result.chunk
        println("${index + 1}. base=${result.score.formatDigits()} source=${chunk.metadata.source}")
        println("   section=${chunk.metadata.section}")
        println("   preview=${chunk.text.shortPreview(180)}")
    }
    println()
    println("AFTER RERANK/FILTER: ${retrieval.selected.size}")
    println("filtered out: ${retrieval.filteredOutCount}")
    if (retrieval.lowConfidence) {
        println("low confidence: no chunk passed threshold, kept best fallback candidate")
    }
    retrieval.selected.forEachIndexed { index, chunk ->
        val metadata = chunk.result.chunk.metadata
        println(
            "${index + 1}. rerank=${chunk.rerankScore.formatDigits()} base=${chunk.result.score.formatDigits()} " +
                "lexical=${chunk.lexicalOverlap.formatDigits()} boost=${chunk.metadataBoost.formatDigits()} " +
                "passed=${chunk.passedThreshold} fallback=${chunk.fallback}",
        )
        println("   source=${metadata.source}")
        println("   section=${metadata.section}")
        println("   chunk_id=${metadata.chunkId}")
        println("   preview=${chunk.result.chunk.text.shortPreview(180)}")
    }
}

fun printAnswer(run: AnswerRun) {
    println(run.mode.uppercase())
    if (run.rewrittenQuery != null) {
        println("rewritten query: ${run.rewrittenQuery.shortPreview(400)}")
    }
    if (run.lowConfidence) {
        println("low confidence: no chunk passed rerank threshold; fallback context was used")
    }
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

fun printRerankEvaluation(report: RerankEvaluationReport) {
    println("EVALUATION: ${report.mode}")
    println("questions: ${report.questionCount}")
    println("baseline expected source hits: ${report.baselineExpectedSourceHitCount}/${report.expectedSourceCount}")
    println("reranked expected source hits: ${report.rerankedExpectedSourceHitCount}/${report.expectedSourceCount}")
    println("baseline false positives: ${report.baselineFalsePositiveCount}")
    println("reranked false positives: ${report.rerankedFalsePositiveCount}")
    println()
    report.records.forEach { record ->
        println("${record.id}. ${record.question}")
        println("   rewritten: ${record.rewrittenQuery.shortPreview(180)}")
        println("   expected sources: ${record.expectedSources.joinToString()}")
        println("   baseline sources: ${record.baselineSources.joinToString()}")
        println("   reranked sources: ${record.rerankedSources.joinToString()}")
        println("   baseline hits: ${record.baselineExpectedSourceHits.joinToString().ifBlank { "none" }}")
        println("   reranked hits: ${record.rerankedExpectedSourceHits.joinToString().ifBlank { "none" }}")
        println("   false positives baseline/reranked: ${record.baselineFalsePositiveSources.size}/${record.rerankedFalsePositiveSources.size}")
        println("   first hit rank baseline/reranked: ${record.baselineFirstHitRank ?: "-"} / ${record.rerankedFirstHitRank ?: "-"}")
        println("   candidates before/after/filter-out: ${record.candidatesBeforeFilter}/${record.chunksAfterFilter}/${record.filteredOutCount}")
    }
}
