package ru.ai.course.day32.codereview

class EvidencePackBuilder {
    fun build(hits: List<RetrievalHit>, maxBytes: Int): EvidencePack {
        require(maxBytes >= 1_000)
        val selected = mutableListOf<RetrievalHit>()
        val documentation = hits.firstOrNull { it.chunk.category == CorpusCategory.DOCUMENTATION }
        val code = hits.firstOrNull { it.chunk.category == CorpusCategory.CODE }
        if (documentation != null) selected += documentation
        if (code != null && code.chunk.id != documentation?.chunk?.id) selected += code
        hits.forEach { hit ->
            if (selected.none { it.chunk.id == hit.chunk.id } && selected.size < 10) selected += hit
        }

        val items = mutableListOf<EvidenceItem>()
        var used = 0
        var truncated = selected.size < hits.size
        val categoryCounts = mutableMapOf<CorpusCategory, Int>()
        for (hit in selected) {
            if (categoryCounts.getOrDefault(hit.chunk.category, 0) >= 6) {
                truncated = true
                continue
            }
            val full = render(hit.chunk, hit.chunk.content)
            val remaining = maxBytes - used
            if (remaining <= 200) {
                truncated = true
                break
            }
            if (TextTools.utf8Bytes(full) > remaining) {
                truncated = true
                continue
            }
            items += EvidenceItem(hit.chunk, hit.score, full)
            used += TextTools.utf8Bytes(full) + 2
            categoryCounts[hit.chunk.category] = categoryCounts.getOrDefault(hit.chunk.category, 0) + 1
        }
        val rendered = items.joinToString("\n\n", transform = EvidenceItem::rendered)
        return EvidencePack(items, rendered, TextTools.utf8Bytes(rendered), maxBytes, truncated)
    }

    private fun render(chunk: SourceChunk, content: String): String = buildString {
        appendLine("[sourceId=${chunk.id}]")
        appendLine("path=${chunk.path}")
        appendLine("lines=${chunk.startLine}-${chunk.endLine}")
        appendLine("category=${chunk.category}")
        append(TextTools.sanitizePromptData(content))
    }

}
