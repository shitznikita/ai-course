package ru.ai.course.day32.codereview

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixturePipelineTest {
    @Test
    fun `end to end fixture produces validated anchored review with doc and code evidence`() {
        val root = Files.createTempDirectory("day32-fixture-")
        val config = testConfig(root)

        val result = FixtureReviewGenerator.run(config)

        assertEquals(
            setOf(FindingCategory.BUG, FindingCategory.ARCHITECTURE, FindingCategory.RECOMMENDATION),
            result.findings.mapTo(linkedSetOf(), ValidatedFinding::category),
        )
        assertTrue(result.findings.all { it.line in setOf(6, 7, 8) })
        assertTrue(result.findings.all { it.evidence.isNotEmpty() })
        assertTrue(result.markdown.contains("AI Code Review"))
        assertFalse(result.markdown.contains("llm-test-token"))
    }

    @Test
    fun `binary and truncated changes remain explicit coverage instead of silent omission`() {
        val root = Files.createTempDirectory("day32-coverage-")
        val config = testConfig(root)
        val context = FixtureReviewGenerator.create(root)
        val binary = ChangedFile(
            path = "assets/image.png",
            status = "added",
            additions = 0,
            deletions = 0,
            changes = 0,
            binary = true,
            patchTruncated = false,
            contentTruncated = false,
        )
        val text = context.snapshot.files.single().copy(patchTruncated = true, contentTruncated = true)
        val snapshot = context.snapshot.copy(
            metadata = context.snapshot.metadata.copy(changedFiles = 3),
            files = listOf(text, binary),
            fullDiff = null,
            fullDiffTruncated = true,
        )
        val generator = FixtureAnswerGenerator()
        val pipeline = ReviewPipeline(
            config,
            ReviewGenerator { error("A batch-aware generator is not used in this coverage-only test.") },
        )

        val prepared = pipeline.prepare(snapshot, context.corpus)

        assertTrue(prepared.isNotEmpty())
        assertTrue(snapshot.files.any(ChangedFile::binary))
        assertTrue(snapshot.files.any(ChangedFile::patchTruncated))
        assertTrue(snapshot.fullDiffTruncated)
        assertEquals(3, snapshot.metadata.changedFiles)
        assertTrue(prepared.all { it.input.files.isEmpty() })
    }
}
