package ru.ai.course.day31.developerassistant

import kotlin.math.ceil

class GroundingValidator {
    fun validateGenerated(
        answer: GeneratedDocumentationAnswer,
        evidence: EvidencePack,
        documentationRequired: Boolean,
    ): GroundingValidation {
        val errors = mutableListOf<String>()
        val uniqueIds = answer.sourceIds.toSet()
        val unknownIds = uniqueIds.filterNot(evidence.sourceIds::contains).sorted()

        if (answer.status !in setOf("answered", "unknown")) errors += "status must be answered or unknown"
        if (answer.answer.isBlank()) errors += "answer must not be blank"
        if (answer.answer.length > 2400) errors += "answer exceeds 2400 characters"
        if (answer.sourceIds.size != uniqueIds.size) errors += "sourceIds contain duplicates"
        if (unknownIds.isNotEmpty()) errors += "answer contains source IDs outside the rendered evidence pack"

        when (answer.status) {
            "answered" -> {
                if (documentationRequired) {
                    if (answer.sourceIds.isEmpty()) errors += "documentation-grounded response must cite rendered evidence"
                    if (!hasLexicalEvidenceSupport(answer, evidence)) {
                        errors += "answered response has insufficient lexical support in its cited evidence"
                    }
                } else if (answer.sourceIds.isNotEmpty()) {
                    errors += "MCP-only response must not cite unrelated documentation"
                }
            }
            "unknown" -> if (answer.sourceIds.isNotEmpty()) {
                errors += "unknown response must not cite sources"
            }
        }
        return GroundingValidation(
            valid = errors.isEmpty(),
            unknownSourceIds = unknownIds,
            errors = errors,
        )
    }

    fun validateFinal(
        answer: AssistantAnswer,
        mcp: McpEvidence,
        requirements: GroundingRequirements,
    ): GroundingValidation {
        val errors = mutableListOf<String>()
        val expectedBranch = mcp.branch.displayName.takeIf {
            answer.status == "answered" && requirements.branchRequired
        }
        val expectedFiles = if (answer.status == "answered" && requirements.filesRequired) {
            mcp.files?.files.orEmpty()
        } else {
            emptyList()
        }

        if (answer.projectBranch != expectedBranch) {
            errors += "projectBranch must be assembled from the required bounded MCP branch"
        }
        if (answer.projectFiles != expectedFiles) {
            errors += "projectFiles must exactly equal the required bounded MCP file evidence"
        }
        val expectedUsage = expectedBranch != null || expectedFiles.isNotEmpty()
        if (answer.usedProjectContext != expectedUsage) {
            errors += "usedProjectContext must be derived from server-owned MCP fields"
        }
        if (answer.status == "answered" && requirements.filesRequired && expectedFiles.isEmpty()) {
            errors += "projectFiles must be nonempty for an explicit file-list question"
        }

        return GroundingValidation(
            valid = errors.isEmpty(),
            unknownSourceIds = emptyList(),
            errors = errors,
        )
    }

    private fun hasLexicalEvidenceSupport(
        answer: GeneratedDocumentationAnswer,
        evidence: EvidencePack,
    ): Boolean {
        if (answer.sourceIds.isEmpty()) return false
        val citedIds = answer.sourceIds.toSet()
        val evidenceTokens = evidence.items
            .filter { it.sourceId in citedIds }
            .flatMap { item ->
                TextTools.tokens(
                    "${item.hit.chunk.metadata.source} ${item.hit.chunk.metadata.section} ${item.text}",
                )
            }
            .filter { it.length >= 4 }
            .toSet()
        val answerTokens = TextTools.tokens(answer.answer)
            .filter { it.length >= 4 }
            .filterNot(genericAnswerTokens::contains)
            .toSet()
        if (answerTokens.isEmpty()) return false
        val overlap = answerTokens.count(evidenceTokens::contains)
        val required = if (answerTokens.size <= 4) {
            (answerTokens.size + 1) / 2
        } else {
            maxOf(2, ceil(answerTokens.size * 0.30).toInt())
        }
        return overlap >= required
    }

    private companion object {
        val genericAnswerTokens = setOf(
            "answer",
            "ответ",
            "project",
            "проект",
            "documentation",
            "документация",
            "source",
            "sources",
            "контекст",
        )
    }
}
