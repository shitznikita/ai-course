package ru.ai.course.day31.developerassistant

object AnswerAssembler {
    const val MCP_ONLY_MESSAGE: String =
        "Проверенный MCP-контекст показан отдельно в server-owned полях."

    fun mcpOnlyDocumentationAnswer(): GeneratedDocumentationAnswer =
        GeneratedDocumentationAnswer(
            status = "answered",
            answer = MCP_ONLY_MESSAGE,
            sourceIds = emptyList(),
        )

    fun assemble(
        generated: GeneratedDocumentationAnswer,
        evidence: EvidencePack,
        requirements: GroundingRequirements,
        mcp: McpEvidence,
    ): AssistantAnswer {
        val answered = generated.status == "answered"
        val projectBranch = mcp.branch.displayName.takeIf { answered && requirements.branchRequired }
        val projectFiles = if (answered && requirements.filesRequired) {
            mcp.files?.files.orEmpty()
        } else {
            emptyList()
        }
        return AssistantAnswer(
            status = generated.status,
            answer = documentationText(generated, evidence, requirements),
            sourceIds = generated.sourceIds.toList(),
            usedProjectContext = projectBranch != null || projectFiles.isNotEmpty(),
            projectBranch = projectBranch,
            projectFiles = projectFiles.toList(),
        )
    }

    private fun documentationText(
        generated: GeneratedDocumentationAnswer,
        evidence: EvidencePack,
        requirements: GroundingRequirements,
    ): String {
        if (generated.status != "answered") return generated.answer
        if (!requirements.documentationRequired) return MCP_ONLY_MESSAGE
        if (!requirements.branchRequired && !requirements.filesRequired) return generated.answer

        val itemsById = evidence.items.associateBy(EvidenceItem::sourceId)
        val excerpts = generated.sourceIds.mapNotNull(itemsById::get).take(3).map { item ->
            val excerpt = item.text
                .lineSequence()
                .map(String::trim)
                .firstOrNull { line ->
                    line.length >= 24 &&
                        !line.startsWith('#') &&
                        !line.startsWith("```") &&
                        !line.endsWith(':')
                }
                ?.removePrefix("- ")
                ?.let { if (it.length <= 240) it else it.take(237).trimEnd() + "..." }
                ?: "См. указанный раздел."
            "${item.hit.chunk.metadata.source} — ${item.hit.chunk.metadata.section}: $excerpt"
        }
        return buildString {
            append("Документационная часть собрана server-side только из cited EvidencePack: ")
            append(excerpts.joinToString(" ").ifBlank { "доступные excerpts отсутствуют." })
        }.take(2400)
    }
}
