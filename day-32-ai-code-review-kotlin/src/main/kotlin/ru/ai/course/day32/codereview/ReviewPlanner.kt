package ru.ai.course.day32.codereview

class ReviewPlanner(
    private val limits: ReviewLimits,
    private val retriever: HybridRetriever = HybridRetriever(),
    private val evidenceBuilder: EvidencePackBuilder = EvidencePackBuilder(),
) {
    fun plan(
        files: List<ChangedFile>,
        parsedDiffs: List<ParsedFileDiff>,
        chunks: List<SourceChunk>,
    ): List<ReviewBatch> {
        if (files.isEmpty()) return emptyList()
        val batchCount = minOf(limits.maxBatches, files.size)
        val groups = MutableList(batchCount) { mutableListOf<ChangedFile>() }
        val weights = IntArray(batchCount)
        files.sortedByDescending(::weight).forEach { file ->
            val target = weights.indices.minBy { weights[it] }
            groups[target] += file
            weights[target] += weight(file)
        }
        val parsedByPath = parsedDiffs.associateBy(ParsedFileDiff::path)
        return groups.filter(List<ChangedFile>::isNotEmpty).mapIndexed { index, group ->
            val diffs = group.mapNotNull { parsedByPath[it.path] }
            val query = buildQuery(group, diffs)
            val hits = retriever.retrieve(query, chunks)
            ReviewBatch(
                index = index + 1,
                files = group.sortedBy(ChangedFile::path),
                parsedDiffs = diffs,
                evidence = evidenceBuilder.build(hits, limits.evidenceBytes),
            )
        }
    }

    fun buildQuery(files: List<ChangedFile>, parsedDiffs: List<ParsedFileDiff>): String {
        val diffByPath = parsedDiffs.associateBy(ParsedFileDiff::path)
        return buildString {
            files.forEach { file ->
                appendLine("path=${file.path}")
                file.content.orEmpty().lineSequence()
                    .filter { line -> importPattern.containsMatchIn(line) }
                    .take(20)
                    .forEach { appendLine(it.trim()) }
                diffByPath[file.path]?.hunks.orEmpty()
                    .flatMap(DiffHunk::lines)
                    .filter { it.kind == DiffLineKind.ADDED }
                    .take(80)
                    .forEach { appendLine(it.text.take(300)) }
            }
        }.take(16_000)
    }

    private fun weight(file: ChangedFile): Int =
        file.patch?.length.orZero() + file.content?.length.orZero() + 500

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        val importPattern = Regex("""^\s*(?:import|package)\s+""")
    }
}
