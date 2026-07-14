package ru.ai.course.day31.developerassistant

class FixtureResponder {
    fun answer(
        question: String,
        evidence: EvidencePack,
        requirements: GroundingRequirements,
    ): GeneratedDocumentationAnswer {
        require(requirements.documentationRequired) {
            "FixtureResponder is documentation-only; MCP-only answers are assembled locally."
        }
        val normalized = question.lowercase()
        val selected = evidence.items
            .sortedWith(
                compareByDescending<EvidenceItem> { item ->
                    when {
                        requirements.fetchFiles &&
                            item.hit.chunk.metadata.source == "docs/project-architecture.md" -> 3
                        listOf("ветк", "branch", "git").any(normalized::contains) &&
                            item.hit.chunk.metadata.source == "docs/developer-assistant-api.yaml" -> 2
                        item.hit.chunk.metadata.source == "README.md" -> 1
                        else -> 0
                    }
                }.thenByDescending { it.hit.score },
            )
            .take(3)
        val summaries = selected.map { item ->
            val section = item.hit.chunk.metadata.section
            val fact = item.text
                .lineSequence()
                .map(String::trim)
                .filter {
                    it.length >= 24 &&
                        !it.startsWith('#') &&
                        !it.startsWith("```") &&
                        !it.startsWith("->") &&
                        !it.endsWith(':')
                }
                .firstOrNull()
                ?.removePrefix("- ")
                ?.let(::boundedExcerpt)
                ?: "См. раздел $section."
            "${item.hit.chunk.metadata.source} — $section: $fact"
        }
        val answer = buildString {
            append("Проект — Gradle multi-project: каждый день находится в отдельном day-модуле, ")
            append("а документация Day 31 подключена через allowlisted RAG. ")
            append(summaries.joinToString(" "))
        }
        return GeneratedDocumentationAnswer(
            status = "answered",
            answer = answer.take(2400),
            sourceIds = selected.map(EvidenceItem::sourceId),
        )
    }

    private fun boundedExcerpt(text: String, limit: Int = 240): String {
        if (text.length <= limit) return text
        val prefix = text.take(limit - 3)
        val boundary = prefix.lastIndexOf(' ').takeIf { it >= limit / 2 } ?: prefix.length
        return prefix.take(boundary).trimEnd() + "..."
    }
}
