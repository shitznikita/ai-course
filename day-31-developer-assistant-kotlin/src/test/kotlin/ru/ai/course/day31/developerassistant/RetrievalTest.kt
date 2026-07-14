package ru.ai.course.day31.developerassistant

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RetrievalTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun `hash embeddings are deterministic and retrieval honors candidate and top K limits`() {
        writeApprovedDocuments()
        val config = config(
            "RAG_TOP_K" to "2",
            "RAG_CANDIDATE_COUNT" to "4",
            "RAG_MIN_RELEVANCE" to "0.10",
        )
        val embeddings = HashEmbeddingClient()
        val firstVector = embeddings.embed(listOf("developer assistant API")).single()
        val secondVector = embeddings.embed(listOf("developer assistant API")).single()
        assertEquals(firstVector, secondVector)

        val index = RagIndexManager(config, embeddings = embeddings).ensureIndex()
        val result = Retriever(config, embeddings).retrieve(index, "Which endpoint returns a grounded answer?")

        assertTrue(result.hits.size <= 2)
        assertFalse(result.lowConfidence)
        assertEquals("docs/developer-assistant-api.yaml", result.hits.first().chunk.metadata.source)
        assertTrue(result.hits.all { it.score >= 0.0 && it.score <= 1.0 })
    }

    @Test
    fun `manifest fingerprint mismatch rebuilds index and unknown query closes the relevance gate`() {
        writeApprovedDocuments()
        val config = config(
            "RAG_MIN_RELEVANCE" to "0.75",
            "RAG_TOP_K" to "2",
            "RAG_CANDIDATE_COUNT" to "4",
        )
        val embeddings = HashEmbeddingClient()
        val manager = RagIndexManager(config, embeddings = embeddings)
        val first = manager.ensureIndex()
        val firstFingerprint = manager.corpusFingerprint(first.manifest)

        val apiDocument = temporaryRoot.resolve("docs/developer-assistant-api.yaml")
        apiDocument.writeText(apiDocument.readText() + "\ncomponents:\n  schemas:\n    GroundedAnswer:\n      type: object\n")
        val rebuilt = manager.ensureIndex()

        assertNotEquals(firstFingerprint, manager.corpusFingerprint(rebuilt.manifest))
        assertTrue(rebuilt.chunks.any { it.text.contains("GroundedAnswer") })

        val result = Retriever(config, embeddings).retrieve(
            rebuilt,
            "interstellar singularity telescope galactic antimatter",
        )
        assertTrue(result.lowConfidence)
    }

    @Test
    fun `lexical candidates can enter the bounded pool outside the vector shortlist`() {
        writeApprovedDocuments()
        val embeddings = QueryEmbeddingClient()
        val retrieved = Retriever(
            config("RAG_TOP_K" to "1", "RAG_CANDIDATE_COUNT" to "1"),
            embeddings,
        ).retrieve(testIndex(), "target endpoint")

        assertEquals("docs/developer-assistant-api.yaml", retrieved.hits.single().chunk.metadata.source)
    }

    private fun config(vararg overrides: Pair<String, String>): AppConfig =
        AppConfig.fromValues(
            mapOf(
                "PROJECT_ROOT" to temporaryRoot.toString(),
                "RAG_INDEX_FILE" to temporaryRoot.resolve("runtime/rag-index.json").toString(),
            ) + overrides.toMap(),
        )

    private fun writeApprovedDocuments() {
        temporaryRoot.resolve("README.md").writeText("# Project\nA Kotlin course repository.\n")
        temporaryRoot.resolve("docs").createDirectories()
        temporaryRoot.resolve("docs/project-architecture.md").writeText(
            "# Architecture\n\nOnly explicit project documents are available to retrieval.\n",
        )
        temporaryRoot.resolve("docs/developer-assistant-api.yaml").writeText(
            "openapi: 3.1.0\ninfo:\n  title: Developer Assistant API\npaths:\n  /ask:\n    post:\n      summary: Return a grounded answer\n",
        )
        temporaryRoot.resolve("day-31-developer-assistant-kotlin").createDirectories()
        temporaryRoot.resolve("day-31-developer-assistant-kotlin/README.md").writeText(
            "# Day 31\n\nThe assistant uses deterministic hash embeddings for offline tests.\n",
        )
    }

    private fun testIndex(): RagIndex = RagIndex(
        generatedAtIso = "2026-01-01T00:00:00Z",
        embeddingBackend = "test",
        embeddingModel = "query-vector",
        chunkMaxTokens = 260,
        embeddingDimensions = 2,
        manifest = emptyList(),
        chunks = listOf(
            indexedChunk(
                source = "day-31-developer-assistant-kotlin/README.md",
                section = "overview",
                text = "general information",
                embedding = listOf(0.95, 0.3122498999),
            ),
            indexedChunk(
                source = "docs/developer-assistant-api.yaml",
                section = "yaml:paths",
                text = "target endpoint returns a grounded answer",
                embedding = listOf(0.8, 0.6),
            ),
        ),
    )

    private fun indexedChunk(
        source: String,
        section: String,
        text: String,
        embedding: List<Double>,
    ): IndexedChunk = IndexedChunk(
        metadata = ChunkMetadata(
            source = source,
            section = section,
            chunkId = "sha256:${TextTools.sha256("$source\u0000$section\u0000$text")}",
            contentSha256 = TextTools.sha256(text),
            ordinal = 1,
            approxTokens = TextTools.approxTokens(text),
        ),
        text = text,
        embedding = embedding,
    )

    private class QueryEmbeddingClient : EmbeddingClient {
        override val backend: String = "test"
        override val model: String = "query-vector"

        override fun embed(texts: List<String>): List<List<Double>> =
            texts.map { listOf(1.0, 0.0) }
    }
}
