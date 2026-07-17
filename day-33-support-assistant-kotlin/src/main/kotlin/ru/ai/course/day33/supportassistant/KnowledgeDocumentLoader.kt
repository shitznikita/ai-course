package ru.ai.course.day33.supportassistant

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class KnowledgeDocumentLoader(
    private val directory: Path,
    private val allowedFiles: List<String> = listOf(
        "faq.md",
        "authentication.md",
        "billing.md",
        "escalation.md",
    ),
) {
    fun load(): List<KnowledgeDocument> {
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Knowledge directory does not exist: $directory"
        }
        require(!Files.isSymbolicLink(directory)) { "Knowledge directory must not be a symlink." }
        val canonicalDirectory = directory.toRealPath()
        return allowedFiles.map { relative ->
            require('/' !in relative && '\\' !in relative && relative.endsWith(".md")) {
                "Knowledge allowlist contains an unsafe path: $relative"
            }
            val path = canonicalDirectory.resolve(relative).normalize()
            require(path.parent == canonicalDirectory) { "Knowledge path escapes its directory: $relative" }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                "Approved knowledge document is missing or unsafe: $relative"
            }
            val markdown = TextTools.readUtf8Bounded(
                path,
                100_000,
                "Knowledge document $relative",
            )
            require(markdown.isNotEmpty()) { "Knowledge document $relative is empty." }
            val title = markdown.lineSequence()
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: error("Knowledge document $relative has no level-1 title.")
            KnowledgeDocument(relative, title, markdown)
        }
    }
}
