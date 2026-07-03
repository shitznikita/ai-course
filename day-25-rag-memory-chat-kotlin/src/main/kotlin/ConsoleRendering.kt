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

fun printChatTurn(run: ChatTurnRun, includePrompt: Boolean = false) {
    println("USER")
    println(run.userMessage)
    println()
    println("TASK STATE")
    println("goal: ${run.taskStateAfter.goal ?: "not set"}")
    println("constraints: ${run.taskStateAfter.constraints.joinToString("; ").ifBlank { "none" }}")
    println("terms: ${run.taskStateAfter.terms.joinToString(", ").ifBlank { "none" }}")
    println("clarifications: ${run.taskStateAfter.clarifications.takeLast(3).joinToString(" | ").ifBlank { "none" }}")
    println()
    println("RAG QUERY")
    println(run.retrievalQuery.shortPreview(900))
    println()
    printGroundedRun(run.groundedRun, includePrompt)
    println()
    println("SESSION")
    println("messages saved: ${run.session.history.size}")
    println("session file updated")
}

fun printSession(session: ChatSession) {
    println("SESSION")
    println("id: ${session.sessionId}")
    println("created: ${session.createdAtIso}")
    println("updated: ${session.updatedAtIso}")
    println("messages: ${session.history.size}")
    println()
    println("TASK STATE")
    println("goal: ${session.taskState.goal ?: "not set"}")
    println("constraints: ${session.taskState.constraints.joinToString("; ").ifBlank { "none" }}")
    println("terms: ${session.taskState.terms.joinToString(", ").ifBlank { "none" }}")
    println("open questions: ${session.taskState.openQuestions.joinToString(" | ").ifBlank { "none" }}")
    println()
    println("RECENT HISTORY")
    session.history.takeLast(10).forEach {
        println("${it.turn}. ${it.role}: ${it.content.shortPreview(240)}")
        if (it.sources.isNotEmpty()) {
            println("   sources=${it.sources.size} quotes=${it.quotes.size} status=${it.status}")
        }
    }
    if (session.history.isEmpty()) println("(empty)")
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

fun printScenarioEvaluation(report: ScenarioEvaluationReport) {
    println("SCENARIO EVALUATION: ${report.mode}")
    println("scenarios: ${report.scenarioCount}")
    println("turns: ${report.totalTurns}")
    println("answers with sources: ${report.answersWithSources}/${report.totalTurns}")
    println("quotes match chunks: ${report.quotesMatchChunks}/${report.totalTurns}")
    println("RAG calls: ${report.ragCalls}/${report.totalTurns}")
    println("goals retained: ${report.goalsRetained}/${report.scenarioCount}")
    println("constraints retained: ${report.constraintsRetained}/${report.scenarioCount}")
    println("terms retained: ${report.termsRetained}/${report.scenarioCount}")
    println()
    report.records.forEach { scenario ->
        println("${scenario.id}: ${scenario.title}")
        println("   turns=${scenario.turnCount} sources=${scenario.answersWithSources}/${scenario.turnCount} quotes=${scenario.quotesMatchChunks}/${scenario.turnCount}")
        println("   goalRetained=${scenario.goalRetained} constraintsRetained=${scenario.constraintsRetained} termsRetained=${scenario.termsRetained}")
        println("   finalGoal=${scenario.finalTaskState.goal ?: "not set"}")
        val errors = scenario.records.flatMap { it.validationErrors }.distinct()
        println("   validationErrors=${errors.ifEmpty { listOf("none") }.joinToString()}")
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
