import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

class DocumentLoader(private val config: AppConfig) {
    private val allowedExtensions = setOf("md", "kt", "kts", "txt", "pdf")
    private val ignoredDirs = setOf(
        ".git",
        ".gradle",
        ".kotlin",
        ".idea",
        ".certs",
        "build",
        "out",
        "index",
        "state",
        "memory",
        "profiles",
        "telegram-session",
        "telegram-files",
    )

    fun load(): LoadedCorpus {
        val root = config.documentsDir.toAbsolutePath().normalize()
        val skipped = mutableListOf<String>()
        val files = Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() }
                .filter { isAllowed(root, it) }
                .sortedBy { root.relativize(it).toString() }
                .take(config.corpusMaxFiles)
                .toList()
        }

        val documents = files.mapNotNull { path ->
            val source = root.relativize(path).toString()
            runCatching {
                val text = extractText(path).trim()
                if (text.isBlank()) {
                    skipped += "$source: empty after extraction"
                    null
                } else {
                    SourceDocument(
                        source = source,
                        title = titleFor(path, text),
                        type = path.extension.lowercase(),
                        text = text,
                    )
                }
            }.getOrElse { error ->
                skipped += "$source: ${error.message ?: error::class.simpleName}"
                null
            }
        }

        return LoadedCorpus(
            documentsDir = root.toString(),
            documents = documents,
            skipped = skipped,
        )
    }

    private fun isAllowed(root: Path, path: Path): Boolean {
        val relative = root.relativize(path)
        if (relative.any { it.name in ignoredDirs }) return false
        if (path.name == ".env" || path.name == ".DS_Store") return false
        if (path.name.endsWith(".tmp") || path.name.endsWith(".local.md") || path.name.endsWith(".iml")) return false
        return path.extension.lowercase() in allowedExtensions
    }

    private fun extractText(path: Path): String =
        when (path.extension.lowercase()) {
            "pdf" -> Loader.loadPDF(path.toFile()).use { document ->
                PDFTextStripper().getText(document)
            }
            else -> path.readText()
        }

    private fun titleFor(path: Path, text: String): String {
        if (path.extension.lowercase() == "md") {
            val heading = text.lineSequence()
                .firstOrNull { it.trimStart().startsWith("# ") }
                ?.trim()
                ?.removePrefix("#")
                ?.trim()
            if (!heading.isNullOrBlank()) return heading
        }
        return path.name
    }
}
