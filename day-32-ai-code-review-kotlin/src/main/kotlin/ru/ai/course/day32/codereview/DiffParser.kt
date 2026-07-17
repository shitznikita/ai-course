package ru.ai.course.day32.codereview

class DiffParser {
    fun parse(path: String, patch: String): ParsedFileDiff {
        AppConfig.requireSafePath(path)
        val hunks = mutableListOf<DiffHunk>()
        val lines = patch.lineSequence().toList()
        var index = 0
        while (index < lines.size) {
            val match = hunkHeader.matchEntire(lines[index])
            if (match == null) {
                index++
                continue
            }
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifBlank { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifBlank { "1" }.toInt()
            val header = lines[index]
            var oldLine = oldStart
            var newLine = newStart
            val changedLines = mutableListOf<ChangedLine>()
            index++
            while (index < lines.size && hunkHeader.matchEntire(lines[index]) == null) {
                val raw = lines[index]
                when {
                    raw.startsWith("diff --git ") -> break
                    raw.startsWith("+") -> {
                        changedLines += ChangedLine(DiffLineKind.ADDED, null, newLine++, raw.drop(1))
                    }
                    raw.startsWith("-") -> {
                        changedLines += ChangedLine(DiffLineKind.REMOVED, oldLine++, null, raw.drop(1))
                    }
                    raw.startsWith(" ") -> {
                        changedLines += ChangedLine(DiffLineKind.CONTEXT, oldLine++, newLine++, raw.drop(1))
                    }
                    raw == "\\ No newline at end of file" -> Unit
                    else -> {
                        changedLines += ChangedLine(DiffLineKind.CONTEXT, oldLine++, newLine++, raw)
                    }
                }
                index++
            }
            hunks += DiffHunk(oldStart, oldCount, newStart, newCount, header, changedLines)
        }
        return ParsedFileDiff(path, hunks)
    }

    fun patchesByPath(fullDiff: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var currentPath: String? = null
        var collecting = false
        val buffer = StringBuilder()

        fun flush() {
            val path = currentPath
            if (path != null && buffer.isNotEmpty()) result[path] = buffer.toString().trimEnd()
            buffer.clear()
        }

        fullDiff.lineSequence().forEach { line ->
            when {
                line.startsWith("diff --git ") -> {
                    flush()
                    currentPath = null
                    collecting = false
                }
                line.startsWith("+++ b/") -> {
                    currentPath = line.removePrefix("+++ b/").trim().removeSurrounding("\"")
                    runCatching { AppConfig.requireSafePath(currentPath!!) }
                        .onFailure { currentPath = null }
                }
                line.startsWith("@@ ") -> {
                    collecting = currentPath != null
                    if (collecting) buffer.appendLine(line)
                }
                collecting -> buffer.appendLine(line)
            }
        }
        flush()
        return result
    }

    fun changedPaths(fullDiff: String): Set<String> {
        val paths = linkedSetOf<String>()
        var insideHunk = false
        fullDiff.lineSequence().forEach { line ->
            when {
                line.startsWith("diff --git ") -> insideHunk = false
                line.startsWith("@@ ") -> insideHunk = true
                !insideHunk && (line.startsWith("--- ") || line.startsWith("+++ ")) -> {
                    val raw = line.substring(4).trim()
                    if (raw != "/dev/null") {
                        val path = raw.removeSurrounding("\"")
                            .removePrefix("a/")
                            .removePrefix("b/")
                        paths += AppConfig.requireSafePath(path)
                    }
                }
            }
        }
        return paths
    }

    companion object {
        private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")
    }
}
