package ru.ai.course.day32.codereview

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RendererPublisherTest {
    @Test
    fun `renderer always prints three sections evidence and explicit limits`() {
        val input = testInput()
        val findings = listOf("BUG", "ARCHITECTURE", "RECOMMENDATION").flatMapIndexed { index, category ->
            ReviewValidator().validate(
                validFindingJson(category = category, line = 10 + index),
                input,
            ).findings
        }
        val injected = findings.first().copy(
            title = "@team [click](https://example.invalid) <script>",
        )
        val metadata = PullRequestMetadata(
            "owner/repo",
            7,
            "Title",
            "a".repeat(40),
            "b".repeat(40),
            draft = false,
            fromFork = false,
            changedFiles = 2,
        )
        val metrics = CorpusMetrics(10, 4, 400, 6, true, mapOf("excluded" to 6))
        val coverage = ReviewCoverage(2, 1, 1, 0, 1, 1, false, metrics, listOf("Ограниченный fixture."))

        val markdown = MarkdownReviewRenderer().render(metadata, listOf(injected) + findings.drop(1), coverage, "fixture")

        assertTrue(markdown.startsWith(MarkdownReviewRenderer.MARKER))
        assertTrue("Potential bugs" in markdown)
        assertTrue("Architectural problems" in markdown)
        assertTrue("Recommendations" in markdown)
        assertTrue("README.md:1-8" in markdown)
        assertTrue("1/2" in markdown)
        assertTrue("Ограниченный fixture" in markdown)
        assertTrue("&#64;team" in markdown)
        assertTrue("\\[click\\]" in markdown)
        assertTrue("&lt;script&gt;" in markdown)
    }

    @Test
    fun `sticky publisher creates once then updates matching bot comment`() {
        val root = Files.createTempDirectory("day32-publisher-")
        val config = testConfig(root)
        val createTransport = QueueTransport(
            mutableListOf(
                jsonResult("[]"),
                jsonResult("""{"id":55}""", status = 201),
            ),
        )
        val markdown = MarkdownReviewRenderer.MARKER + "\n# Review\n"

        val created = GitHubReviewPublisher(config, createTransport).publish(markdown)

        assertEquals(PublishOperation.CREATED, created)
        assertEquals("POST", createTransport.calls.last().method)

        val comments = ReviewJson.strict.encodeToString(
            buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive(44))
                    put("body", JsonPrimitive(markdown))
                    put("user", buildJsonObject {
                        put("type", JsonPrimitive("Bot"))
                        put("login", JsonPrimitive("foreign-reviewer[bot]"))
                    })
                })
                add(buildJsonObject {
                    put("id", JsonPrimitive(55))
                    put("body", JsonPrimitive(markdown))
                    put("user", buildJsonObject {
                        put("type", JsonPrimitive("Bot"))
                        put("login", JsonPrimitive("github-actions[bot]"))
                    })
                })
            },
        )
        val updateTransport = QueueTransport(
            mutableListOf(
                jsonResult(comments),
                jsonResult("""{"id":55}"""),
            ),
        )

        val updated = GitHubReviewPublisher(config, updateTransport).publish(markdown)

        assertEquals(PublishOperation.UPDATED, updated)
        assertEquals("PATCH", updateTransport.calls.last().method)
        assertTrue(updateTransport.calls.last().uri.path.endsWith("/issues/comments/55"))
    }

    @Test
    fun `step summary contains only rendered report`() {
        val root = Files.createTempDirectory("day32-summary-")
        val summary = root.resolve("summary.md")
        val publisher = GitHubReviewPublisher(testConfig(root), QueueTransport(mutableListOf()))
        val markdown = MarkdownReviewRenderer.MARKER + "\nreview\n"

        assertTrue(publisher.writeStepSummary(markdown, summary))
        assertEquals(markdown.trim(), Files.readString(summary).trim())
    }

    private fun jsonResult(body: String, status: Int = 200): HttpResult =
        HttpResult(status, mapOf("content-type" to listOf("application/json")), body)

    private class QueueTransport(private val results: MutableList<HttpResult>) : HttpTransport {
        val calls = mutableListOf<HttpCall>()
        override fun execute(call: HttpCall): HttpResult {
            calls += call
            return results.removeFirst()
        }
    }
}
