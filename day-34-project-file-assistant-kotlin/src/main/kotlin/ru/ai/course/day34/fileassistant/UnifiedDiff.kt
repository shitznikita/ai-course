package ru.ai.course.day34.fileassistant

object UnifiedDiff {
    fun render(changes: Map<String, Pair<String?, String>>): String =
        changes.toSortedMap().entries.joinToString(separator = "") { (path, versions) ->
            renderFile(path, versions.first, versions.second)
        }

    private fun renderFile(path: String, oldText: String?, newText: String): String {
        val oldLines = oldText?.normalizedNewlines()?.splitLines() ?: emptyList()
        val newLines = newText.normalizedNewlines().splitLines()
        val operations = diff(oldLines, newLines)
        val oldHeader = if (oldText == null) "/dev/null" else "a/$path"
        val oldStart = if (oldLines.isEmpty()) 0 else 1
        val newStart = if (newLines.isEmpty()) 0 else 1
        return buildString {
            append("--- ").append(oldHeader).append('\n')
            append("+++ b/").append(path).append('\n')
            append("@@ -").append(oldStart).append(',').append(oldLines.size)
                .append(" +").append(newStart).append(',').append(newLines.size).append(" @@\n")
            operations.forEach { (prefix, line) ->
                append(prefix).append(line).append('\n')
            }
        }
    }

    private fun diff(oldLines: List<String>, newLines: List<String>): List<Pair<Char, String>> {
        if (oldLines.size.toLong() * newLines.size.toLong() > MAX_LCS_CELLS) {
            return oldLines.map { '-' to it } + newLines.map { '+' to it }
        }
        val lcs = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (i in oldLines.indices.reversed()) {
            for (j in newLines.indices.reversed()) {
                lcs[i][j] = if (oldLines[i] == newLines[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }
        val result = mutableListOf<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < oldLines.size || j < newLines.size) {
            when {
                i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j] -> {
                    result += ' ' to oldLines[i]
                    i++
                    j++
                }
                j < newLines.size && (i == oldLines.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    result += '+' to newLines[j++]
                }
                else -> result += '-' to oldLines[i++]
            }
        }
        return result
    }

    private fun String.splitLines(): List<String> {
        if (isEmpty()) return emptyList()
        val raw = split('\n')
        return if (raw.lastOrNull().isNullOrEmpty()) raw.dropLast(1) else raw
    }

    private const val MAX_LCS_CELLS = 2_000_000L
}
