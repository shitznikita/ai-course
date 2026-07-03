fun printGroundedRun(run: GroundedRun, includePrompt: Boolean = false) {
    println("QUESTION")
    println(run.question)
    println()
    println("RETRIEVAL")
    println("rewritten: ${run.retrieval.rewrite.rewritten.shortPreview(360)}")
    println("max relevance: ${(run.retrieval.selected.maxOfOrNull { it.rerankScore } ?: 0.0).formatDigits()}")
    println("selected chunks: ${run.retrieval.selected.size}")
    println("low confidence: ${run.retrieval.lowConfidence}")
    println()
    println("QUOTE CANDIDATES")
    run.quoteCandidates.forEach {
        println("- ${it.id} score=${it.score.formatDigits()} ${it.source} / ${it.section} / ${it.chunkId}")
        println("  ${it.text.shortPreview(240)}")
    }
    if (run.quoteCandidates.isEmpty()) println("(none)")
    println()
    println("ANSWER JSON")
    println(AppJson.pretty.encodeToString(GroundedAnswer.serializer(), run.answer))
    println()
    println("VALIDATION")
    printValidation(run.validation)
    if (run.warningOrError != null) {
        println("warning/error: ${run.warningOrError}")
    }
    if (includePrompt) {
        println()
        println("PROMPT PREVIEW")
        println(run.promptPreview.shortPreview(3200))
    }
}

fun printCitationEvaluation(report: CitationEvaluationReport) {
    println("EVALUATION: ${report.mode}")
    println("supported questions: ${report.supportedQuestionCount}")
    println("unknown questions: ${report.unknownQuestionCount}")
    println("sources present: ${report.sourcesPresentCount}/${report.supportedQuestionCount}")
    println("quotes present: ${report.quotesPresentCount}/${report.supportedQuestionCount}")
    println("quotes match chunks: ${report.quotesMatchChunksCount}/${report.supportedQuestionCount}")
    println("answer matches quotes: ${report.answerMatchesQuotesCount}/${report.supportedQuestionCount}")
    println("unknown answers: ${report.unknownCount}/${report.unknownQuestionCount}")
    println("unknown clarifying questions: ${report.unknownClarifyingQuestionCount}/${report.unknownQuestionCount}")
    println()
    report.records.forEach { record ->
        println("${record.id}. ${record.question}")
        println("   status=${record.status} maxRelevance=${record.maxRelevance.formatDigits()}")
        println("   sources=${record.sources.size} quotes=${record.quotes.size}")
        println("   validationErrors=${record.validation.errors.ifEmpty { listOf("none") }.joinToString()}")
    }
}

private fun printValidation(validation: GroundingValidation) {
    println("statusAnswered=${validation.statusAnswered}")
    println("statusUnknown=${validation.statusUnknown}")
    println("answerPresent=${validation.answerPresent}")
    println("sourcesPresent=${validation.sourcesPresent}")
    println("quotesPresent=${validation.quotesPresent}")
    println("sourcesMatchRetrieved=${validation.sourcesMatchRetrieved}")
    println("quotesMatchChunks=${validation.quotesMatchChunks}")
    println("expectedPoints=${validation.expectedPointsCovered}/${validation.expectedPointsTotal}")
    println("answerMatchesQuotes=${validation.answerMatchesQuotes}")
    println("unknownSaysDontKnow=${validation.unknownSaysDontKnow}")
    println("clarifyingQuestionPresent=${validation.clarifyingQuestionPresent}")
    println("errors=${validation.errors.ifEmpty { listOf("none") }.joinToString()}")
}
