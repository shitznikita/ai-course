package ru.ai.course.day32.codereview

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubPullRequestClientTest {
    @Test
    fun `changed files are fetched through validated pagination`() {
        val root = Files.createTempDirectory("day32-github-pages-")
        val config = testConfig(root, mapOf("REVIEW_MAX_CHANGED_FILES" to "150"))
        val transport = RecordingTransport { call ->
            val page = Regex("""[?&]page=(\d+)""").find(call.uri.query.orEmpty())?.groupValues?.get(1)?.toInt() ?: 1
            val count = if (page == 1) 100 else 1
            jsonResult(fileArray((page - 1) * 100, count))
        }

        val files = GitHubPullRequestClient(config, transport).fetchChangedFiles(101)

        assertEquals(101, files.size)
        assertEquals(2, transport.calls.size)
        assertEquals("src/File100.kt", files.last().path)
    }

    @Test
    fun `missing provider patch is reported and text blob stays bounded`() {
        val root = Files.createTempDirectory("day32-github-truncated-")
        val config = testConfig(root)
        val sha = "c".repeat(40)
        val transport = RecordingTransport { call ->
            when {
                call.uri.path.endsWith("/pulls/7") &&
                    call.headers["Accept"]?.contains("diff") == true -> HttpResult(406, emptyMap(), "")
                call.uri.path.endsWith("/pulls/7") -> jsonResult(metadataJson(changedFiles = 1))
                call.uri.path.endsWith("/pulls/7/files") -> jsonResult(fileArray(0, 1, sha, includePatch = false))
                call.uri.path.endsWith("/git/blobs/$sha") -> {
                    val content = "fun safe() = Unit\n"
                    jsonResult(
                        ReviewJson.strict.encodeToString(
                            buildJsonObject {
                                put("encoding", JsonPrimitive("base64"))
                                put("size", JsonPrimitive(content.toByteArray().size))
                                put(
                                    "content",
                                    JsonPrimitive(Base64.getEncoder().encodeToString(content.toByteArray(StandardCharsets.UTF_8))),
                                )
                            },
                        ),
                    )
                }
                else -> error("Unexpected call ${call.method} ${call.uri}")
            }
        }

        val snapshot = GitHubPullRequestClient(config, transport).fetch()

        assertEquals(1, snapshot.files.size)
        assertTrue(snapshot.files.single().patchTruncated)
        assertEquals("fun safe() = Unit\n", snapshot.files.single().content)
        assertNull(snapshot.fullDiff)
        assertTrue(snapshot.fullDiffTruncated)
    }

    @Test
    fun `provider patch is bounded before parsing and marked truncated`() {
        val root = Files.createTempDirectory("day32-github-patch-budget-")
        val config = testConfig(
            root,
            mapOf(
                "REVIEW_MAX_FILE_BYTES" to "4096",
                "REVIEW_MAX_TOTAL_CHANGED_BYTES" to "16384",
            ),
        )
        val hugePatch = buildString {
            appendLine("@@ -1 +1,5000 @@")
            repeat(5_000) { appendLine("+line-$it-${"x".repeat(20)}") }
        }
        val transport = RecordingTransport {
            jsonResult(fileArrayWithPatch("d".repeat(40), hugePatch))
        }

        val file = GitHubPullRequestClient(config, transport).fetchChangedFiles(1).single()

        assertTrue(file.patchTruncated)
        assertTrue(TextTools.utf8Bytes(file.patch.orEmpty()) <= config.limits.maxFileBytes)
        assertTrue(DiffParser().parse(file.path, file.patch.orEmpty()).addedLines.isNotEmpty())
    }

    @Test
    fun `snapshot fails closed when pull request head moves during pagination`() {
        val root = Files.createTempDirectory("day32-github-moving-head-")
        val config = testConfig(root)
        var metadataCalls = 0
        val sha = "e".repeat(40)
        val transport = RecordingTransport { call ->
            when {
                call.uri.path.endsWith("/pulls/7") &&
                    call.headers["Accept"]?.contains("diff") == true -> HttpResult(406, emptyMap(), "")
                call.uri.path.endsWith("/pulls/7") -> {
                    metadataCalls++
                    val head = if (metadataCalls == 1) "b".repeat(40) else "f".repeat(40)
                    jsonResult(metadataJson(changedFiles = 1, headSha = head))
                }
                call.uri.path.endsWith("/pulls/7/files") -> jsonResult(fileArray(0, 1, sha, includePatch = true))
                call.uri.path.endsWith("/git/blobs/$sha") -> {
                    val content = "fun moving() = Unit\n"
                    jsonResult(
                        ReviewJson.strict.encodeToString(
                            buildJsonObject {
                                put("encoding", JsonPrimitive("base64"))
                                put("size", JsonPrimitive(content.toByteArray().size))
                                put("content", JsonPrimitive(Base64.getEncoder().encodeToString(content.toByteArray())))
                            },
                        ),
                    )
                }
                else -> error("Unexpected call ${call.method} ${call.uri}")
            }
        }

        assertFailsWith<IllegalArgumentException> {
            GitHubPullRequestClient(config, transport).fetch()
        }
    }

    private fun metadataJson(changedFiles: Int, headSha: String = "b".repeat(40)): String =
        ReviewJson.strict.encodeToString(
        buildJsonObject {
            put("title", JsonPrimitive("Review fixture"))
            put("draft", JsonPrimitive(false))
            put("changed_files", JsonPrimitive(changedFiles))
            put("base", buildJsonObject { put("sha", JsonPrimitive("a".repeat(40))) })
            put("head", buildJsonObject {
                put("sha", JsonPrimitive(headSha))
                put("repo", buildJsonObject { put("full_name", JsonPrimitive("owner/repo")) })
            })
        },
    )

    private fun fileArrayWithPatch(sha: String, patch: String): String = ReviewJson.strict.encodeToString(
        buildJsonArray {
            add(buildJsonObject {
                put("filename", JsonPrimitive("src/Large.kt"))
                put("status", JsonPrimitive("modified"))
                put("sha", JsonPrimitive(sha))
                put("additions", JsonPrimitive(5_000))
                put("deletions", JsonPrimitive(1))
                put("changes", JsonPrimitive(5_001))
                put("patch", JsonPrimitive(patch))
            })
        },
    )

    private fun fileArray(
        start: Int,
        count: Int,
        fixedSha: String? = null,
        includePatch: Boolean = true,
    ): String = ReviewJson.strict.encodeToString(
        buildJsonArray {
            repeat(count) { offset ->
                val number = start + offset
                add(buildJsonObject {
                    put("filename", JsonPrimitive("src/File$number.kt"))
                    put("status", JsonPrimitive("modified"))
                    put("sha", JsonPrimitive(fixedSha ?: number.toString(16).padStart(40, '0')))
                    put("additions", JsonPrimitive(1))
                    put("deletions", JsonPrimitive(0))
                    put("changes", JsonPrimitive(1))
                    if (includePatch) put("patch", JsonPrimitive("@@ -1 +1 @@\n-old\n+new"))
                })
            }
        },
    )

    private fun jsonResult(body: String): HttpResult =
        HttpResult(200, mapOf("content-type" to listOf("application/json")), body)

    private class RecordingTransport(private val route: (HttpCall) -> HttpResult) : HttpTransport {
        val calls = mutableListOf<HttpCall>()
        override fun execute(call: HttpCall): HttpResult {
            calls += call
            return route(call)
        }
    }
}
