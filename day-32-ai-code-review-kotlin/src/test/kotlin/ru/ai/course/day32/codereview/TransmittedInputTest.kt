package ru.ai.course.day32.codereview

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransmittedInputTest {
    @Test
    fun `constrained prompt authorizes only whole transmitted files and evidence`() {
        val batch = constrainedBatch()

        val prepared = PromptBuilder(8_000).build(batch)
        val input = prepared.input

        assertTrue(input.files.isNotEmpty())
        assertTrue(input.files.size < batch.files.size)
        assertTrue(input.evidenceItems.isNotEmpty())
        assertTrue(input.evidenceItems.size < batch.evidence.items.size)
        assertTrue(prepared.prompt.bytes <= prepared.prompt.maxBytes)
        assertFalse("[evidence truncated]" in prepared.prompt.user)
        assertFalse("[truncated by prompt budget]" in prepared.prompt.user)

        input.files.forEach { file ->
            assertTrue(file.rendered in prepared.prompt.user)
            assertTrue(file.patch.substringAfterLast("sentinel-") in prepared.prompt.user)
        }
        val omittedPaths = batch.files.map(ChangedFile::path).toSet() - input.reviewedPaths
        omittedPaths.forEach { assertFalse("path=$it" in prepared.prompt.user) }
        input.evidenceItems.forEach { assertTrue(it.rendered in prepared.prompt.user) }
        val omittedEvidence = batch.evidence.items.filterNot { it.chunk.id in input.sourceIds }
        omittedEvidence.forEach { assertFalse("[sourceId=${it.chunk.id}]" in prepared.prompt.user) }

        val selected = input.files.first()
        val allowedSource = input.evidenceItems.first().chunk.id
        val omittedSource = omittedEvidence.first().chunk.id
        val validator = ReviewValidator()
        val omittedSourceResult = validator.validate(
            findingJson(selected.path, selected.parsedDiff.addedLines.first(), omittedSource),
            input,
        )
        val omittedPath = omittedPaths.first()
        val omittedPathResult = validator.validate(
            findingJson(omittedPath, 2, allowedSource),
            input,
        )

        assertFalse(omittedSourceResult.valid)
        assertFalse(omittedPathResult.valid)
    }

    @Test
    fun `oversized evidence is omitted whole and can never be cited`() {
        val huge = source("SRC-huge", CorpusCategory.DOCUMENTATION, "H".repeat(12_000))
        val small = source("SRC-small", CorpusCategory.CODE, "interface SafePort")
        val pack = EvidencePackBuilder().build(
            listOf(
                RetrievalHit(huge, 1.0, 1.0, 1.0, 0.0),
                RetrievalHit(small, 0.9, 0.9, 0.9, 0.0),
            ),
            2_000,
        )
        val base = testBatch()
        val prepared = PromptBuilder(8_000).build(base.copy(evidence = pack))

        assertFalse("SRC-huge" in pack.sourceIds)
        assertFalse("H".repeat(100) in pack.rendered)
        assertTrue("SRC-small" in prepared.input.sourceIds)
        assertFalse("[sourceId=SRC-huge]" in prepared.prompt.user)
        val invalid = ReviewValidator().validate(
            findingJson("src/Service.kt", 10, "SRC-huge"),
            prepared.input,
        )
        assertFalse(invalid.valid)
    }

    @Test
    fun `coverage counts only files actually sent through a successful model call`() {
        val root = Files.createTempDirectory("day32-transmitted-coverage-")
        val config = testConfig(
            root,
            mapOf(
                "REVIEW_PROMPT_BYTES" to "8000",
                "RAG_EVIDENCE_BYTES" to "2000",
                "REVIEW_MAX_BATCHES" to "1",
            ),
        )
        val batch = constrainedBatch()
        val incomplete = batch.files.first().copy(
            path = "src/Incomplete.kt",
            patchTruncated = true,
        )
        val mismatched = batch.files[1].copy(
            path = "src/Mismatched.kt",
            additions = batch.files[1].additions + 1,
        )
        val binary = ChangedFile(
            path = "assets/logo.png",
            status = "modified",
            additions = 0,
            deletions = 0,
            changes = 0,
            binary = true,
        )
        val files = batch.files + incomplete + mismatched + binary
        val snapshot = PullRequestSnapshot(
            metadata = PullRequestMetadata(
                repository = "owner/repo",
                number = 7,
                title = "Constrained review",
                baseSha = "a".repeat(40),
                headSha = "b".repeat(40),
                draft = false,
                fromFork = false,
                changedFiles = files.size,
            ),
            files = files,
            fullDiff = null,
            fullDiffTruncated = true,
        )
        val corpus = LoadedCorpus(
            documents = listOf(
                CorpusDocument(
                    "README.md",
                    "# Architecture\nUse services and ports.",
                    CorpusCategory.DOCUMENTATION,
                    0,
                ),
                CorpusDocument(
                    "src/BasePort.kt",
                    "interface BasePort { fun load(): String? }",
                    CorpusCategory.CODE,
                    1,
                ),
            ),
            metrics = CorpusMetrics(2, 2, 100, 0, false, emptyMap()),
        )
        var calls = 0
        val pipeline = ReviewPipeline(
            config,
            ReviewGenerator {
                calls++
                LlmReply("fixture-empty", """{"findings":[]}""")
            },
        )
        val prepared = pipeline.prepare(snapshot, corpus)
        val expectedReviewed = prepared
            .filter { it.input.files.isNotEmpty() && it.input.evidenceItems.isNotEmpty() }
            .flatMapTo(linkedSetOf()) { it.input.reviewedPaths }

        val result = pipeline.review(snapshot, corpus)

        assertEquals(1, calls)
        assertEquals(expectedReviewed.size, result.coverage.reviewedFiles)
        assertTrue(result.coverage.reviewedFiles < snapshot.files.size)
        assertFalse("src/Incomplete.kt" in expectedReviewed)
        assertFalse("src/Mismatched.kt" in expectedReviewed)
        assertFalse("assets/logo.png" in expectedReviewed)
        assertTrue(result.coverage.notes.any { "не были переданы модели" in it })
    }

    @Test
    fun `multiple successful batches count the union of their transmitted files`() {
        val root = Files.createTempDirectory("day32-multi-batch-")
        val config = testConfig(
            root,
            mapOf(
                "REVIEW_MAX_BATCHES" to "2",
                "RAG_EVIDENCE_BYTES" to "2000",
            ),
        )
        val files = (1..2).map { index ->
            val patch = """
                @@ -1 +1,2 @@
                 fun multi$index() {
                +  println("safe-$index")
            """.trimIndent()
            ChangedFile(
                path = "src/Multi$index.kt",
                status = "modified",
                additions = 1,
                deletions = 0,
                changes = 1,
                patch = patch,
            )
        }
        val snapshot = PullRequestSnapshot(
            metadata = PullRequestMetadata(
                repository = "owner/repo",
                number = 7,
                title = "Two batches",
                baseSha = "a".repeat(40),
                headSha = "b".repeat(40),
                draft = false,
                fromFork = false,
                changedFiles = files.size,
            ),
            files = files,
            fullDiff = null,
            fullDiffTruncated = true,
        )
        val corpus = LoadedCorpus(
            documents = listOf(
                CorpusDocument("README.md", "# Rules\nKeep changes safe.", CorpusCategory.DOCUMENTATION, 0),
                CorpusDocument("src/Port.kt", "interface Port", CorpusCategory.CODE, 1),
            ),
            metrics = CorpusMetrics(2, 2, 50, 0, false, emptyMap()),
        )
        var calls = 0
        val result = ReviewPipeline(
            config,
            ReviewGenerator {
                calls++
                LlmReply("fixture-empty", """{"findings":[]}""")
            },
        ).review(snapshot, corpus)

        assertEquals(2, calls)
        assertEquals(2, result.coverage.reviewedFiles)
    }

    private fun constrainedBatch(): ReviewBatch {
        val files = (1..8).map { index ->
            val payload = "x".repeat(1_100)
            val patch = """
                @@ -1 +1,2 @@
                 fun file$index() {
                +  val value$index = "$payload-sentinel-$index"
            """.trimIndent()
            ChangedFile(
                path = "src/File$index.kt",
                status = "modified",
                additions = 1,
                deletions = 0,
                changes = 1,
                patch = patch,
                content = "fun file$index() = \"${"y".repeat(500)}\"",
            )
        }
        val parsed = files.map { DiffParser().parse(it.path, requireNotNull(it.patch)) }
        val hits = (1..6).map { index ->
            val category = if (index % 2 == 0) CorpusCategory.CODE else CorpusCategory.DOCUMENTATION
            val chunk = source("SRC-$index", category, "evidence-$index-${"z".repeat(850)}")
            RetrievalHit(chunk, 1.0 - index / 100.0, 0.8, 0.8, 0.1)
        }
        val evidence = EvidencePackBuilder().build(hits, 20_000)
        return ReviewBatch(1, files, parsed, evidence)
    }

    private fun source(id: String, category: CorpusCategory, content: String): SourceChunk = SourceChunk(
        id = id,
        path = if (category == CorpusCategory.DOCUMENTATION) "docs/$id.md" else "src/$id.kt",
        startLine = 1,
        endLine = 20,
        category = category,
        section = id,
        contentHash = TextTools.sha256(content),
        content = content,
    )

    private fun findingJson(path: String, line: Int, sourceId: String): String =
        ReviewJson.strict.encodeToString(
            ModelReview(
                listOf(
                    ModelFinding(
                        category = FindingCategory.BUG,
                        severity = FindingSeverity.HIGH,
                        path = path,
                        line = line,
                        title = "Конкретная проблема изменения",
                        detail = "Описание подтверждённого риска в переданном изменении.",
                        recommendation = "Исправить изменение до объединения pull request.",
                        evidenceIds = listOf(sourceId),
                    ),
                ),
            ),
        )
}
