package ru.ai.course.day31.developerassistant

import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Loads only the explicitly approved project documents. It deliberately does not walk the project
 * tree: a RAG index is an export boundary, not a convenient way to ingest a repository by accident.
 */
class ProjectDocumentLoader(private val config: AppConfig) {
    fun load(): LoadedProjectCorpus {
        val root = config.projectRoot.toRealPath()
        val seenSources = mutableSetOf<String>()
        val loadedDocuments = config.allowedDocuments.map { source ->
            require(seenSources.add(source)) { "Duplicate allowed document: $source" }
            loadDocument(root, source)
        }
        val documents = loadedDocuments.map { it.document }
        val manifest = loadedDocuments.map { loaded ->
            CorpusManifestEntry(
                source = loaded.document.source,
                contentSha256 = loaded.document.contentSha256,
                bytes = loaded.bytes,
            )
        }
        return LoadedProjectCorpus(documents = documents, manifest = manifest)
    }

    private fun loadDocument(root: Path, source: String): LoadedDocument {
        val relative = Path.of(source)
        require(!relative.isAbsolute) { "Allowed document must be a relative path: $source" }
        require(relative.normalize() == relative && relative.none { it.toString() == ".." }) {
            "Allowed document must not escape the project root: $source"
        }

        val candidate = root.resolve(relative).normalize()
        require(candidate.startsWith(root)) { "Document resolves outside project root: $source" }
        rejectSymbolicLinks(root, relative, source)
        require(candidate.isRegularFile(NOFOLLOW_LINKS)) { "Allowed document is not a regular file: $source" }
        require(Files.size(candidate) <= config.maxDocumentBytes) {
            "Allowed document exceeds MAX_DOCUMENT_BYTES (${config.maxDocumentBytes}): $source"
        }

        val canonical = candidate.toRealPath()
        require(canonical.startsWith(root)) { "Document canonical path escapes project root: $source" }
        val bytes = readBounded(canonical, source)
        val text = String(bytes, StandardCharsets.UTF_8)
        require(text.isNotBlank()) { "Allowed document is empty: $source" }
        return LoadedDocument(
            document = ProjectDocument(
                source = source,
                title = titleFor(source, text),
                type = source.substringAfterLast('.', missingDelimiterValue = "text").lowercase(),
                text = text,
                contentSha256 = TextTools.sha256(text),
            ),
            bytes = bytes.size.toLong(),
        )
    }

    private fun rejectSymbolicLinks(root: Path, relative: Path, source: String) {
        var current = root
        relative.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Symlinks are not allowed in document paths: $source" }
        }
    }

    private fun readBounded(path: Path, source: String): ByteArray {
        val initialCapacity = minOf(config.maxDocumentBytes, 16_384L).toInt()
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(8_192)
        var total = 0L

        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            while (true) {
                val remaining = config.maxDocumentBytes - total
                val requested = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
                val read = input.read(buffer, 0, requested)
                if (read < 0) break
                total += read
                require(total <= config.maxDocumentBytes) {
                    "Allowed document exceeds MAX_DOCUMENT_BYTES (${config.maxDocumentBytes}): $source"
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun titleFor(source: String, text: String): String {
        if (source.endsWith(".md", ignoreCase = true)) {
            text.lineSequence()
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return Path.of(source).name
    }

    private data class LoadedDocument(
        val document: ProjectDocument,
        val bytes: Long,
    )
}
