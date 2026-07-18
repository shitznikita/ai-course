package ru.ai.course.day34.fileassistant

import java.nio.charset.StandardCharsets

class ProjectWorkspace(
    val policy: ProjectFilePolicy,
    val writeMode: WriteMode,
) {
    private val secureFiles = SecureProjectFiles(policy)
    private val baseline = linkedMapOf<String, String?>()
    private val overlay = linkedMapOf<String, String>()
    private val readSha256 = linkedMapOf<String, String>()
    private val discovered = linkedSetOf<String>()
    private val searched = linkedSetOf<String>()
    private val searchHits = linkedSetOf<String>()
    private val read = linkedSetOf<String>()
    private val written = linkedSetOf<String>()
    private var totalSearchHits = 0

    @Synchronized
    fun listFiles(prefix: String?, limit: Int): FileListResult {
        require(limit in 1..ProjectFilePolicy.MAX_DISCOVERED_FILES) {
            "limit must be between 1 and ${ProjectFilePolicy.MAX_DISCOVERED_FILES}."
        }
        val disk = policy.discover(prefix, ProjectFilePolicy.MAX_DISCOVERED_FILES)
        val normalizedPrefix = prefix?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
        val all = (disk.files + overlay.keys)
            .asSequence()
            .distinct()
            .filter { normalizedPrefix == null || it.startsWith(normalizedPrefix) }
            .sorted()
            .toList()
        val result = all.take(limit)
        discovered += result
        return FileListResult(result, disk.truncated || all.size > result.size)
    }

    @Synchronized
    fun searchText(query: String, prefix: String?, limit: Int): SearchResult {
        require(query.isNotBlank() && query.length <= 128) { "query must contain 1..128 characters." }
        require(limit in 1..100) { "limit must be between 1 and 100." }
        val remaining = 100 - totalSearchHits
        require(remaining > 0) { "Cumulative search hit budget exhausted." }
        val paths = listFiles(prefix, ProjectFilePolicy.MAX_DISCOVERED_FILES).files
        val hits = mutableListOf<SearchHit>()
        var searchedCount = 0
        for (path in paths) {
            searchedCount++
            searched += path
            currentContent(path).lineSequence().forEachIndexed { index, line ->
                if (query in line && hits.size < minOf(limit, remaining)) {
                    hits += SearchHit(path, index + 1, boundedText(line.trim(), 300))
                    searchHits += path
                }
            }
            if (hits.size >= minOf(limit, remaining)) break
        }
        totalSearchHits += hits.size
        return SearchResult(
            query = query,
            hits = hits,
            searchedFiles = searchedCount,
            truncated = hits.size >= minOf(limit, remaining),
        )
    }

    @Synchronized
    fun readFiles(paths: List<String>): ReadFilesResult {
        require(paths.size in 1..6) { "paths must contain 1..6 files." }
        require(paths.distinct().size == paths.size) { "paths must not contain duplicates." }
        val result = paths.map { requested ->
            val path = normalize(requested)
            require(path in discovered || path in searchHits) {
                "File must be discovered or found by search before read: $path"
            }
            val content = currentContent(path)
            val bytes = content.toByteArray(StandardCharsets.UTF_8).size
            require(bytes <= ProjectFilePolicy.MAX_READ_BYTES) { "Project file exceeds read limit: $path" }
            val digest = sha256(content)
            readSha256[path] = digest
            read += path
            ReadFileEntry(path, content, digest, bytes)
        }
        return ReadFilesResult(result)
    }

    @Synchronized
    fun writeFile(pathValue: String, contentValue: String, expectedSha256: String?): WriteFileResult {
        require(written.size < MAX_WRITES || pathValue in written) { "Write budget of $MAX_WRITES files exhausted." }
        val content = contentValue.normalizedNewlines()
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= ProjectFilePolicy.MAX_WRITE_BYTES) { "New content exceeds write limit." }
        require('\u0000' !in content) { "NUL content is not allowed." }
        val path = normalize(pathValue)
        val diskExists = secureFiles.exists(path)
        val existed = path in overlay || diskExists
        val current = if (existed) currentContent(path) else null

        if (existed) {
            val readDigest = readSha256[path]
                ?: throw IllegalStateException("Existing file must be read before write: $path")
            require(!expectedSha256.isNullOrBlank()) { "expectedSha256 is required for existing files." }
            require(expectedSha256 == readDigest) { "expectedSha256 does not match the session read for $path." }
            require(expectedSha256 == sha256(requireNotNull(current))) { "Stale SHA-256 for $path." }
        } else {
            require(expectedSha256.isNullOrBlank()) { "expectedSha256 must be omitted for a new file." }
        }

        if (!baseline.containsKey(path)) baseline[path] = if (diskExists) current else null
        val changed = current != content
        if (changed) {
            if (writeMode == WriteMode.APPLY) {
                secureFiles.write(
                    relativePath = path,
                    bytes = bytes,
                    expectedDiskSha256 = if (diskExists) current?.let(::sha256) else null,
                )
            }
            overlay[path] = content
            written += path
            discovered += path
        }
        return WriteFileResult(path, sha256(content), writeMode.name.lowercase(), changed)
    }

    @Synchronized
    fun unifiedDiff(): DiffResult {
        val changes = overlay.entries
            .filter { (path, content) -> baseline[path] != content }
            .associate { (path, content) -> path to (baseline[path] to content) }
        val diff = UnifiedDiff.render(changes)
        return DiffResult(changes.keys.sorted(), diff, sha256(diff))
    }

    @Synchronized
    fun snapshot(): SessionSummary {
        val diff = unifiedDiff()
        return SessionSummary(
            filesDiscovered = discovered.sorted(),
            filesSearched = searched.sorted(),
            filesRead = read.sorted(),
            filesWritten = written.sorted(),
            changedPaths = diff.changedPaths,
            diff = diff.diff,
            diffSha256 = diff.sha256,
        )
    }

    private fun normalize(value: String): String =
        policy.normalizeFile(value).joinToString("/") { it.toString() }

    private fun currentContent(path: String): String {
        overlay[path]?.let { return it }
        return secureFiles.read(path)
    }

    companion object {
        const val MAX_WRITES = 4
    }
}
