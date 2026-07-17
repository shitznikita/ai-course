package ru.ai.course.day33.supportassistant

class EvidencePackBuilder(private val maxChars: Int) {
    init {
        require(maxChars >= 1_000) { "Evidence budget is too small." }
    }

    fun build(query: String, hits: List<RetrievedKnowledge>): KnowledgeEvidencePack {
        val selected = mutableListOf<RetrievedKnowledge>()
        var used = 0
        hits.forEach { hit ->
            val rendered = render(hit)
            if (used + rendered.length <= maxChars) {
                selected += hit
                used += rendered.length
            }
        }
        val block = selected.joinToString("\n\n", transform = ::render)
        return KnowledgeEvidencePack(
            query = query,
            items = selected.toList(),
            allowedSourceIds = selected.map { it.chunk.sourceId }.toSet(),
            renderedBlock = block,
            totalChars = block.length,
        )
    }

    private fun render(hit: RetrievedKnowledge): String = buildString {
        append("<<<KNOWLEDGE sourceId=\"").append(sanitizeMarker(hit.chunk.sourceId)).append("\">>>\n")
        append("HEADING: ").append(sanitizeMarker(hit.chunk.heading)).append('\n')
        append("SCORE: ").append("%.3f".format(hit.score)).append('\n')
        append(sanitizeMarker(hit.chunk.text)).append('\n')
        append("<<<END_KNOWLEDGE>>>")
    }

    private fun sanitizeMarker(value: String): String = value
        .replace("<<<", "‹‹‹")
        .replace(">>>", "›››")
}
