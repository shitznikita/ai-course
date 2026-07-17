package ru.ai.course.day32.codereview

import kotlinx.serialization.decodeFromString

class ReviewValidator(
    private val cloudContentPolicy: CloudContentPolicy = CloudContentPolicy(),
) {
    fun validate(rawOutput: String, input: TransmittedReviewInput): ValidationResult {
        val trimmed = rawOutput.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) {
            return invalid("model output must be exactly one JSON object")
        }
        val review = runCatching { ReviewJson.strict.decodeFromString<ModelReview>(trimmed) }
            .getOrElse { return invalid("model output does not match the strict review schema") }
        if (review.findings.size > 30) return invalid("model output contains more than 30 findings")

        val files = input.files.associateBy(TransmittedChangedFile::path)
        val addedLines = input.files.associate { it.path to it.parsedDiff.addedLines }
        val evidence = input.evidenceItems.associateBy { it.chunk.id }
        val errors = mutableListOf<String>()
        val validated = mutableListOf<ValidatedFinding>()
        val seen = mutableSetOf<String>()

        review.findings.forEachIndexed { index, finding ->
            val prefix = "finding ${index + 1}"
            val file = files[finding.path]
            if (file == null) errors += "$prefix references an unknown changed path"
            if (finding.line !in addedLines[finding.path].orEmpty()) {
                errors += "$prefix is not anchored to an added line"
            }
            if (finding.title.length !in 5..180 || finding.title.any { it == '\u0000' }) {
                errors += "$prefix has an invalid title"
            }
            if (finding.detail.length !in 10..2_000 || finding.detail.any { it == '\u0000' }) {
                errors += "$prefix has an invalid detail"
            }
            if (finding.recommendation.length !in 5..1_200 || finding.recommendation.any { it == '\u0000' }) {
                errors += "$prefix has an invalid recommendation"
            }
            val sensitiveModelText = listOf(finding.title, finding.detail, finding.recommendation).any { text ->
                cloudContentPolicy.contentRejection(text) != null
            }
            if (sensitiveModelText) errors += "$prefix contains sensitive model-controlled text"
            if (finding.evidenceIds.isEmpty()) errors += "$prefix has no RAG evidence"
            if (finding.evidenceIds.size != finding.evidenceIds.distinct().size) {
                errors += "$prefix has duplicate evidence IDs"
            }
            val unknownEvidence = finding.evidenceIds.filterNot(evidence::containsKey)
            if (unknownEvidence.isNotEmpty()) errors += "$prefix references evidence outside its batch"
            val key = listOf(
                finding.category.name,
                finding.path,
                finding.line.toString(),
                finding.title.trim().lowercase(),
            ).joinToString("\u0000")
            if (!seen.add(key)) errors += "$prefix duplicates another finding"
            if (errors.none { it.startsWith(prefix) }) {
                validated += ValidatedFinding(
                    category = finding.category,
                    severity = finding.severity,
                    path = finding.path,
                    line = finding.line,
                    title = finding.title.trim(),
                    detail = finding.detail.trim(),
                    recommendation = finding.recommendation.trim(),
                    evidence = finding.evidenceIds.map(evidence::getValue).map(EvidenceItem::chunk),
                )
            }
        }
        return if (errors.isEmpty()) ValidationResult(validated, emptyList()) else ValidationResult(emptyList(), errors)
    }

    fun merge(findings: List<ValidatedFinding>): List<ValidatedFinding> {
        val severityOrder = mapOf(
            FindingSeverity.BLOCKER to 0,
            FindingSeverity.HIGH to 1,
            FindingSeverity.MEDIUM to 2,
            FindingSeverity.LOW to 3,
        )
        return findings.distinctBy {
            "${it.category}|${it.path}|${it.line}|${it.title.trim().lowercase()}"
        }.sortedWith(
            compareBy<ValidatedFinding> { it.category.ordinal }
                .thenBy { severityOrder.getValue(it.severity) }
                .thenBy(ValidatedFinding::path)
                .thenBy(ValidatedFinding::line),
        )
    }

    private fun invalid(message: String) = ValidationResult(emptyList(), listOf(message))
}

class ModelValidationException(errors: List<String>) :
    IllegalStateException("Model review failed validation: ${errors.joinToString("; ")}")
