package ru.ai.course.day32.codereview

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigDiffTest {
    @Test
    fun `config validates security identifiers and bounded limits`() {
        val root = Files.createTempDirectory("day32-config-")
        val config = testConfig(root, mapOf("REVIEW_MAX_BATCHES" to "2", "RAG_MAX_FILES" to "25"))

        assertEquals(2, config.limits.maxBatches)
        assertEquals(25, config.limits.maxCorpusFiles)
        config.requireCiValues()
        assertFailsWith<IllegalArgumentException> { AppConfig.requireValidRepository("owner/repo/extra") }
        assertFailsWith<IllegalArgumentException> { AppConfig.requireSha("abc", "sha") }
        assertFailsWith<IllegalArgumentException> { AppConfig.requireSafePath("../secret") }
        assertFailsWith<IllegalArgumentException> {
            testConfig(root, mapOf("REVIEW_MAX_BATCHES" to "4"))
        }
        assertFailsWith<IllegalArgumentException> {
            testConfig(
                root,
                mapOf(
                    "REVIEW_PROMPT_BYTES" to "8000",
                    "RAG_EVIDENCE_BYTES" to "5000",
                ),
            )
        }
    }

    @Test
    fun `diff parser preserves old and new line mapping and added anchors`() {
        val patch = """
            @@ -10,3 +10,5 @@ fun demo() {
             keep()
            -old()
            +newOne()
            +newTwo()
            +++operatorLikeContent()
             done()
        """.trimIndent()

        val parsed = DiffParser().parse("src/Demo.kt", patch)

        assertEquals(setOf(11, 12, 13), parsed.addedLines)
        val lines = parsed.hunks.single().lines
        assertEquals(10, lines[0].oldLine)
        assertEquals(10, lines[0].newLine)
        assertEquals(11, lines[1].oldLine)
        assertEquals(null, lines[1].newLine)
        assertEquals(null, lines[2].oldLine)
        assertEquals(11, lines[2].newLine)
        assertTrue(lines[2].anchorId == "L11")
        assertEquals("++operatorLikeContent()", lines[4].text)
        assertEquals(13, lines[4].newLine)
    }

    @Test
    fun `full diff parser maps patches by safe changed path`() {
        val full = """
            diff --git a/src/A.kt b/src/A.kt
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1 +1 @@
            -old
            +new
            diff --git a/docs/R.md b/docs/R.md
            --- a/docs/R.md
            +++ b/docs/R.md
            @@ -1 +1,2 @@
             # R
            +text
        """.trimIndent()

        val patches = DiffParser().patchesByPath(full)

        assertEquals(setOf("src/A.kt", "docs/R.md"), patches.keys)
        assertTrue(patches.getValue("src/A.kt").contains("+new"))
        assertEquals(setOf("src/A.kt", "docs/R.md"), DiffParser().changedPaths(full))
    }
}
