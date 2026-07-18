package ru.ai.course.day35.releaseprep
object ReleasePlanValidator {
    private val severities = setOf("LOW", "MEDIUM", "HIGH")
    private val recommendations = setOf("PROCEED", "HOLD")
    fun validate(plan: AiReleasePlan, authorizedEvidence: Map<String, EvidenceKind>): ValidatedPlan {
        require(plan.summary.size in 1..5) { "summary count must be 1..5" }
        require(plan.releaseNotes.size in 1..8) { "releaseNotes count must be 1..8" }
        require(plan.risks.size <= 8) { "risks count must be 0..8" }
        require(plan.videoSteps.size in 1..8) { "videoSteps count must be 1..8" }
        require(plan.recommendation in recommendations) { "Unknown recommendation" }
        validateTexts(plan.summary, "summary", authorizedEvidence)
        validateTexts(plan.releaseNotes, "releaseNotes", authorizedEvidence)
        validateTexts(plan.videoSteps, "videoSteps", authorizedEvidence)
        plan.risks.forEachIndexed { index, risk ->
            require(risk.severity in severities) { "Unknown risk severity" }
            validateModelText(risk.text, "risks[$index].text", 1_500)
            validateModelText(risk.mitigation, "risks[$index].mitigation", 1_500)
            validateEvidence(risk.evidencePaths, authorizedEvidence, "risks[$index]")
        }
        require(
            plan.risks.map {
                listOf(
                    it.severity,
                    ContentPolicy.canonicalForm(it.text),
                    ContentPolicy.canonicalForm(it.mitigation),
                    *it.evidencePaths.map(ContentPolicy::canonicalForm).toTypedArray(),
                )
            }.distinct().size == plan.risks.size,
        ) { "Duplicate canonical risks" }
        return ValidatedPlan.create(plan)
    }
    private fun validateTexts(items: List<AiText>, label: String, authorized: Map<String, EvidenceKind>) {
        items.forEachIndexed { index, item ->
            validateModelText(item.text, "$label[$index].text", 1_500)
            validateEvidence(item.evidencePaths, authorized, "$label[$index]")
        }
        require(
            items.map {
                ContentPolicy.canonicalForm(it.text) to it.evidencePaths.map(ContentPolicy::canonicalForm)
            }.distinct().size == items.size,
        ) { "Duplicate canonical $label items" }
    }
    private fun validateEvidence(paths: List<String>, authorized: Map<String, EvidenceKind>, label: String) {
        require(paths.isNotEmpty() && paths.size <= 8) { "$label evidencePaths must be non-empty" }
        paths.forEach {
            ContentPolicy.validatePath(it)
            require(authorized.containsKey(it)) { "$label cites manifest-only or omitted path: $it" }
        }
        require(paths.map(ContentPolicy::canonicalForm).distinct().size == paths.size) {
            "$label evidencePaths must be canonically unique"
        }
        require(paths.any { authorized[it] == EvidenceKind.REVIEWED_RELEASE_BRIEF }) {
            "$label must cite REVIEWED_RELEASE_BRIEF"
        }
    }
    private fun validateModelText(value: String, label: String, cap: Int) {
        require(value.none { it == '\n' || it == '\r' || it == '\t' }) { "$label must be one terminal-safe line" }
        ContentPolicy.validateText(value, label, cap)
        require(ContentPolicy.canonicalForm(value).isNotBlank()) { "$label is blank" }
    }
}
object FixturePlan {
    fun generate(briefPath: String): AiReleasePlan = AiReleasePlan(
        summary = listOf(AiText("Добавлен локальный pipeline подготовки релиза с проверками и AI-черновиком.", listOf(briefPath))),
        releaseNotes = listOf(AiText("Перед PR pipeline собирает manifest, запускает Gradle и готовит release notes.", listOf(briefPath))),
        risks = listOf(AiRisk("LOW", "Live-режим зависит от доступности корпоративного Eliza endpoint.",
            "Сначала выполнить обязательный prepare-dry-run, затем один live prepare при наличии OAuth.", listOf(briefPath))),
        videoSteps = listOf(AiText("Показать разделение deterministic facts и AI release prose.", listOf(briefPath))),
        recommendation = "PROCEED",
    )
}
object ServerDecision {
    fun decide(checks: List<CheckResult>, stable: Boolean, modelValid: Boolean): String =
        if (checks.all(CheckResult::passed) && stable && modelValid) "READY_FOR_HUMAN_REVIEW" else "BLOCKED"
}
object SnapshotGate {
    fun requireStable(expected: SnapshotFingerprint, actual: SnapshotFingerprint) =
        require(expected == actual) { "Repository snapshot drifted; stale output discarded" }
}
object ExternalCallGate {
    fun <T> execute(expected: SnapshotFingerprint, actual: SnapshotFingerprint, credential: () -> String, call: (String) -> T): T {
        SnapshotGate.requireStable(expected, actual); return call(credential())
    }
}
