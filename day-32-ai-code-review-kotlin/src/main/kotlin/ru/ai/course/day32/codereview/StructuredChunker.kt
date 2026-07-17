package ru.ai.course.day32.codereview

class StructuredChunker(
    private val maxLines: Int,
    private val maxChunks: Int,
) {
    init {
        require(maxLines >= 10)
        require(maxChunks > 0)
    }

    fun chunk(documents: List<CorpusDocument>): List<SourceChunk> {
        val chunks = mutableListOf<SourceChunk>()
        for (document in documents) {
            if (chunks.size >= maxChunks) break
            val lines = document.content.lines()
            if (lines.isEmpty()) continue
            val starts = structureStarts(document.path, lines)
            val ranges = ranges(lines.size, starts)
            for (range in ranges) {
                var start = range.first
                while (start <= range.last && chunks.size < maxChunks) {
                    val end = minOf(range.last, start + maxLines - 1)
                    val content = lines.subList(start - 1, end).joinToString("\n").trimEnd()
                    if (content.isNotBlank()) {
                        val section = sectionName(document.path, lines[start - 1], start, end)
                        val hash = TextTools.sha256(content)
                        chunks += SourceChunk(
                            id = "SRC-${TextTools.sha256("${document.path}:$start:$end:$hash").take(20)}",
                            path = document.path,
                            startLine = start,
                            endLine = end,
                            category = document.category,
                            section = section,
                            contentHash = hash,
                            content = content,
                        )
                    }
                    start = end + 1
                }
            }
        }
        return chunks
    }

    private fun structureStarts(path: String, lines: List<String>): Set<Int> {
        val lower = path.lowercase()
        val regex = when {
            lower.endsWith(".md") -> Regex("""^\s*#{1,6}\s+\S+""")
            lower.endsWith(".kt") || lower.endsWith(".kts") || lower.endsWith(".java") ->
                Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+)?(?:data\s+|sealed\s+|enum\s+)?(?:class|interface|object|fun|record)\s+\S+""")
            lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json") ->
                Regex("""^[A-Za-z0-9_"'-][^:]{0,80}:\s*.*$""")
            else -> null
        }
        return buildSet {
            add(1)
            if (regex != null) lines.forEachIndexed { index, line -> if (regex.containsMatchIn(line)) add(index + 1) }
        }
    }

    private fun ranges(lineCount: Int, starts: Set<Int>): List<IntRange> {
        val ordered = starts.filter { it in 1..lineCount }.sorted()
        return ordered.mapIndexed { index, start ->
            val next = ordered.getOrNull(index + 1) ?: (lineCount + 1)
            start..(next - 1)
        }
    }

    private fun sectionName(path: String, firstLine: String, start: Int, end: Int): String {
        val cleaned = firstLine.trim().take(100)
        return if (cleaned.isBlank()) "$path:$start-$end" else cleaned
    }
}
