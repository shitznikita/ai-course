package ru.ai.course.day31.developerassistant

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectDocumentLoaderTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun `loads exactly the approved allowlist`() {
        writeApprovedDocuments(temporaryRoot)
        temporaryRoot.resolve("notes.md").writeText("# Private notes\nThis must not be indexed.")

        val corpus = ProjectDocumentLoader(config()).load()

        assertEquals(
            listOf(
                "README.md",
                "docs/project-architecture.md",
                "docs/developer-assistant-api.yaml",
                "day-31-developer-assistant-kotlin/README.md",
            ),
            corpus.documents.map { it.source },
        )
        assertTrue(corpus.documents.none { it.text.contains("Private notes") })
        assertEquals(corpus.documents.map { it.source }, corpus.manifest.map { it.source })
    }

    @Test
    fun `rejects a symlink anywhere in an approved path`() {
        writeApprovedDocuments(temporaryRoot)
        val outside = Files.createTempDirectory("day31-outside").resolve("project-architecture.md")
        outside.writeText("# Outside\nThis file must never be accepted.")
        Files.delete(temporaryRoot.resolve("docs/project-architecture.md"))
        Files.createSymbolicLink(temporaryRoot.resolve("docs/project-architecture.md"), outside)

        val error = assertFailsWith<IllegalArgumentException> {
            ProjectDocumentLoader(config()).load()
        }

        assertTrue(error.message.orEmpty().contains("Symlinks are not allowed"))
    }

    @Test
    fun `rejects traversal even when it is placed in the configured allowlist`() {
        writeApprovedDocuments(temporaryRoot)
        val unsafeConfig = config().copy(allowedDocuments = listOf("../outside.md"))

        val error = assertFailsWith<IllegalArgumentException> {
            ProjectDocumentLoader(unsafeConfig).load()
        }

        assertTrue(error.message.orEmpty().contains("must not escape"))
    }

    @Test
    fun `rejects an approved document over the configured byte cap`() {
        writeApprovedDocuments(temporaryRoot, rootReadme = "# Project\n" + "x".repeat(1_100))

        val error = assertFailsWith<IllegalArgumentException> {
            ProjectDocumentLoader(config(mapOf("MAX_DOCUMENT_BYTES" to "1024"))).load()
        }

        assertTrue(error.message.orEmpty().contains("MAX_DOCUMENT_BYTES"))
    }

    @Test
    fun `structured chunks retain original markdown and YAML source text with stable SHA IDs`() {
        writeApprovedDocuments(temporaryRoot)
        val corpus = ProjectDocumentLoader(config()).load()
        val chunker = StructuredChunker(config().chunkMaxTokens)

        val architecture = corpus.documents.single { it.source == "docs/project-architecture.md" }
        val yaml = corpus.documents.single { it.source == "docs/developer-assistant-api.yaml" }
        val architectureChunks = chunker.chunk(architecture)
        val yamlChunks = chunker.chunk(yaml)

        assertEquals(architecture.text, architectureChunks.joinToString(separator = "") { it.text })
        assertEquals(yaml.text, yamlChunks.joinToString(separator = "") { it.text })
        assertTrue(architectureChunks.any { it.metadata.section == "Architecture" && it.text.startsWith("# Architecture") })
        assertTrue(yamlChunks.any { it.metadata.section == "yaml:info" && it.text.startsWith("info:") })
        assertTrue(yamlChunks.all { it.metadata.chunkId.matches(Regex("""sha256:[0-9a-f]{64}""")) })
        assertEquals(
            yamlChunks.map { it.metadata.chunkId },
            chunker.chunk(yaml).map { it.metadata.chunkId },
        )
    }

    private fun config(overrides: Map<String, String> = emptyMap()): AppConfig =
        AppConfig.fromValues(
            mapOf(
                "PROJECT_ROOT" to temporaryRoot.toString(),
                "RAG_INDEX_FILE" to temporaryRoot.resolve("runtime/rag-index.json").toString(),
            ) + overrides,
        )

    private fun writeApprovedDocuments(root: Path, rootReadme: String = "# Project\nDeveloper assistant overview.\n") {
        root.resolve("README.md").writeText(rootReadme)
        root.resolve("docs").createDirectories()
        root.resolve("docs/project-architecture.md").writeText(
            "# Architecture\n\nThe developer assistant reads an exact allowlist of project documents.\n",
        )
        root.resolve("docs/developer-assistant-api.yaml").writeText(
            "openapi: 3.1.0\ninfo:\n  title: Developer Assistant API\npaths:\n  /ask:\n    post:\n      summary: Ask a grounded question\n",
        )
        root.resolve("day-31-developer-assistant-kotlin").createDirectories()
        root.resolve("day-31-developer-assistant-kotlin/README.md").writeText(
            "# Day 31\n\nBounded developer assistant RAG implementation.\n",
        )
    }
}
