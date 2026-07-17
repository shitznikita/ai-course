package ru.ai.course.day32.codereview

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudContentBoundaryTest {
    @Test
    fun `compiled cloud policy rejects OAuth and special-character credential matrix`() {
        val policy = CloudContentPolicy()

        sensitiveContentCases.forEachIndexed { index, scenario ->
            assertEquals(
                CloudContentRejection.SENSITIVE_CONTENT,
                policy.contentRejection(scenario.content),
                "sensitive policy case $index",
            )
        }
        listOf(
            "Authorization: Custom_Auth abcdefghijklmnop123456",
            """{"password":"sampleActualCredential123"}""",
            """{"password":"PROD_PASSWORD_123456"}""",
            """{"password":"${'$'}{PASSWORD:-Password${'$'}123456789}"}""",
            """{"password":"%sPassword${'$'}123456789"}""",
            """printf 'Authorization: Bearer %s\nX-Api-Key: Api${'$'}Key-123456789\n' "${'$'}APP_API_TOKEN" |""",
        ).forEachIndexed { index, content ->
            assertEquals(
                CloudContentRejection.SENSITIVE_CONTENT,
                policy.contentRejection(content),
                "placeholder hardening case $index",
            )
        }
        listOf(
            "Authorization: OAuth <token>",
            """{"Authorization":"OAuth ${'$'}APP_API_TOKEN"}""",
            """{\"password\":\"replace-with-oauth-token\"}""",
            """{"apiKey":"${'$'}{API_KEY}"}""",
            """{"password":"not-for-cloud"}""",
            """{"password":"${'$'}{YANDEX_TRUSTSTORE_PASSWORD:-changeit}"}""",
            """printf 'Authorization: Bearer %s\n' "${'$'}APP_API_TOKEN" |""",
            """printf 'Authorization: Bearer %s\nContent-Type: application/json\n' "${'$'}APP_API_TOKEN" |""",
        ).forEachIndexed { index, placeholder ->
            assertEquals(null, policy.contentRejection(placeholder), "placeholder policy case $index")
        }
    }

    @Test
    fun `sensitive changed path patch and decoded blob stop before Eliza and never echo literals`() {
        val scenarios = buildList {
            addAll(
                listOf(
                    Scenario(
                        path = "config/credentials.json",
                        patch = safePatch,
                        blob = "safe content",
                        forbiddenLiteral = "config/credentials.json",
                    ),
                    Scenario(
                        path = "src/PatchSecret.kt",
                        patch = safePatch + "\n+val token = \"$slackCredential\"",
                        blob = "safe content",
                        forbiddenLiteral = slackCredential,
                    ),
                    Scenario(
                        path = "src/QuotedApiKey.kt",
                        patch = safePatch +
                            "\n+val payload = \"{\\\"apiKey\\\":\\\"abcdefghijklmnop1234567890\\\"}\"",
                        blob = "safe content",
                        forbiddenLiteral = "abcdefghijklmnop1234567890",
                    ),
                    Scenario(
                        path = "src/QuotedAuthorization.kt",
                        patch = safePatch +
                            "\n+val headers = " +
                            "\"{\\\"Authorization\\\":\\\"Bearer $githubCredential\\\"}\"",
                        blob = "safe content",
                        forbiddenLiteral = githubCredential,
                    ),
                    Scenario(
                        path = "src/BlobSecret.kt",
                        patch = safePatch,
                        blob = "Authorization: Bearer $githubCredential",
                        forbiddenLiteral = githubCredential,
                    ),
                    Scenario(
                        path = "src/QuotedPasswordBlob.kt",
                        patch = safePatch,
                        blob = "{\"password\":\"password123456\"}",
                        forbiddenLiteral = "password123456",
                    ),
                    Scenario(
                        path = "src/AwsSecret.kt",
                        patch = safePatch + "\n+AWS_SECRET_ACCESS_KEY=abcdefghijklmnop1234567890",
                        blob = "safe content",
                        forbiddenLiteral = "abcdefghijklmnop1234567890",
                    ),
                    Scenario(
                        path = "src/PasswordLikeSecret.kt",
                        patch = safePatch + "\n+AWS_SECRET_ACCESS_KEY=password123456",
                        blob = "safe content",
                        forbiddenLiteral = "password123456",
                    ),
                ),
            )
            sensitiveContentCases.forEachIndexed { index, sensitive ->
                add(
                    Scenario(
                        path = "src/BoundaryPatch$index.kt",
                        patch = safePatch + "\n+${sensitive.content}",
                        blob = "safe content",
                        forbiddenLiteral = sensitive.forbiddenLiteral,
                    ),
                )
                add(
                    Scenario(
                        path = "src/BoundaryBlob$index.kt",
                        patch = safePatch,
                        blob = sensitive.content,
                        forbiddenLiteral = sensitive.forbiddenLiteral,
                    ),
                )
            }
        }

        scenarios.forEach { scenario ->
            val root = Files.createTempDirectory("day32-cloud-boundary-")
            val config = testConfig(root)
            val llmCalls = mutableListOf<HttpCall>()
            val llm = ElizaLlmClient(
                config,
                HttpTransport { call ->
                    llmCalls += call
                    error("Eliza transport must not be called for sensitive changed data.")
                },
            )
            val error = assertFailsWith<CloudContentRejectedException> {
                val snapshot = GitHubPullRequestClient(config, ScenarioGitHubTransport(scenario)).fetch()
                ReviewPipeline(config, llm).review(snapshot, safeCorpus())
            }

            assertTrue(llmCalls.isEmpty())
            assertNonEchoArtifacts(error, scenario.forbiddenLiteral, root, config)
        }
    }

    @Test
    fun `Eliza final boundary rejects a sensitive prompt without an HTTP call`() {
        val root = Files.createTempDirectory("day32-eliza-boundary-")
        val config = testConfig(root)
        val calls = mutableListOf<HttpCall>()
        val client = ElizaLlmClient(
            config,
            HttpTransport { call ->
                calls += call
                error("HTTP must not be called.")
            },
        )

        val prompts = listOf(
            SensitiveContentCase("path=config/credentials.json", "config/credentials.json"),
            SensitiveContentCase(
                "{\"apiKey\":\"abcdefghijklmnop1234567890\"}",
                "abcdefghijklmnop1234567890",
            ),
        ) + sensitiveContentCases
        prompts.forEachIndexed { index, sensitive ->
            val prompt = PromptPack(
                system = "system",
                user = sensitive.content,
                preview = sensitive.content,
                bytes = TextTools.utf8Bytes(sensitive.content),
                maxBytes = 1_000,
                truncated = false,
            )
            val error = assertFailsWith<CloudContentRejectedException> {
                client.generate(prompt)
            }
            assertTrue(calls.isEmpty())
            assertNonEchoArtifacts(
                error,
                sensitive.forbiddenLiteral,
                root.resolve("case-$index").also(Files::createDirectories),
                config,
            )
        }
    }

    @Test
    fun `pipeline preflight blocks sensitive snapshots from alternate clients before generation`() {
        val root = Files.createTempDirectory("day32-pipeline-boundary-")
        val config = testConfig(root)
        val literal = slackCredential
        val file = ChangedFile(
            path = "src/Alternate.kt",
            status = "modified",
            additions = 1,
            deletions = 0,
            changes = 1,
            patch = safePatch + "\n+val token = \"$literal\"",
        )
        val snapshot = PullRequestSnapshot(
            metadata = PullRequestMetadata(
                repository = "owner/repo",
                number = 7,
                title = "Alternate client",
                baseSha = "a".repeat(40),
                headSha = "b".repeat(40),
                draft = false,
                fromFork = false,
                changedFiles = 1,
            ),
            files = listOf(file),
            fullDiff = null,
            fullDiffTruncated = true,
        )
        var calls = 0

        val error = assertFailsWith<CloudContentRejectedException> {
            ReviewPipeline(
                config,
                ReviewGenerator {
                    calls++
                    LlmReply("unexpected", """{"findings":[]}""")
                },
            ).review(snapshot, safeCorpus())
        }

        assertEquals(0, calls)
        assertFalse(literal in ReviewFailureDiagnostics.message(error))
    }

    @Test
    fun `sensitive allowlisted corpus stops the run before any model call`() {
        val root = Files.createTempDirectory("day32-corpus-boundary-")
        root.resolve("docs").toFile().mkdirs()
        val literal = "abcdefghijklmnop1234567890"
        Files.writeString(root.resolve("docs/unsafe.md"), "AWS_SECRET_ACCESS_KEY=$literal\n")
        val config = testConfig(root)
        var calls = 0

        val error = assertFailsWith<CloudContentRejectedException> {
            val corpus = RepositoryCorpusLoader(
                root,
                config.limits,
                TrackedFileProvider { listOf("docs/unsafe.md") },
            ).load(emptyList())
            ReviewPipeline(
                config,
                ReviewGenerator {
                    calls++
                    LlmReply("unexpected", """{"findings":[]}""")
                },
            ).review(safeSnapshot(), corpus)
        }

        assertEquals(0, calls)
        val diagnostic = MarkdownReviewRenderer().renderDiagnostic(ReviewFailureDiagnostics.message(error))
        assertFalse(literal in diagnostic)
        assertTrue("0 файлов проверено" in diagnostic)
    }

    @Test
    fun `sensitive bounded full diff stops before blobs and Eliza`() {
        sensitiveContentCases.forEachIndexed { index, sensitive ->
            val root = Files.createTempDirectory("day32-full-diff-boundary-$index-")
            val config = testConfig(root)
            val githubCalls = mutableListOf<HttpCall>()
            val github = HttpTransport { call ->
                githubCalls += call
                when {
                    call.uri.path.endsWith("/pulls/7") && call.headers["Accept"]?.contains("diff") == true ->
                        HttpResult(
                            200,
                            mapOf("content-type" to listOf("text/plain")),
                            "diff --git a/src/Safe.kt b/src/Safe.kt\n" +
                                "+${sensitive.content}\n",
                        )
                    call.uri.path.endsWith("/pulls/7") -> json(metadataJson())
                    call.uri.path.endsWith("/pulls/7/files") -> json(
                        filesJson(
                            Scenario(
                                "src/Safe.kt",
                                safePatch,
                                "safe content",
                                sensitive.forbiddenLiteral,
                            ),
                        ),
                    )
                    else -> error("Blob/model fetch must not occur after sensitive full diff.")
                }
            }
            val llmCalls = mutableListOf<HttpCall>()

            val error = assertFailsWith<CloudContentRejectedException> {
                val snapshot = GitHubPullRequestClient(config, github).fetch()
                ReviewPipeline(
                    config,
                    ElizaLlmClient(
                        config,
                        HttpTransport { call ->
                            llmCalls += call
                            error("Eliza must not be called.")
                        },
                    ),
                ).review(snapshot, safeCorpus())
            }

            assertTrue(llmCalls.isEmpty())
            assertTrue(githubCalls.none { "/git/blobs/" in it.uri.path })
            assertNonEchoArtifacts(error, sensitive.forbiddenLiteral, root, config)
        }
    }

    @Test
    fun `sensitive path in bounded full diff header stops before Eliza`() {
        val root = Files.createTempDirectory("day32-full-diff-path-")
        val config = testConfig(root)
        val forbiddenPath = "config/credentials.json"
        val github = HttpTransport { call ->
            when {
                call.uri.path.endsWith("/pulls/7") && call.headers["Accept"]?.contains("diff") == true ->
                    HttpResult(
                        200,
                        mapOf("content-type" to listOf("text/plain")),
                        """
                            diff --git a/$forbiddenPath b/$forbiddenPath
                            --- a/$forbiddenPath
                            +++ b/$forbiddenPath
                            @@ -1 +1 @@
                            -safe
                            +still-safe
                        """.trimIndent(),
                    )
                call.uri.path.endsWith("/pulls/7") -> json(metadataJson())
                call.uri.path.endsWith("/pulls/7/files") -> json(
                    filesJson(Scenario("src/Safe.kt", safePatch, "safe content", forbiddenPath)),
                )
                else -> error("Blob/model fetch must not occur after a sensitive diff path.")
            }
        }
        var modelCalls = 0

        val error = assertFailsWith<CloudContentRejectedException> {
            val snapshot = GitHubPullRequestClient(config, github).fetch()
            ReviewPipeline(
                config,
                ReviewGenerator {
                    modelCalls++
                    LlmReply("unexpected", """{"findings":[]}""")
                },
            ).review(snapshot, safeCorpus())
        }

        assertEquals(0, modelCalls)
        assertFalse(forbiddenPath in ReviewFailureDiagnostics.message(error))
    }

    @Test
    fun `changed file cap fails closed before partial review`() {
        val root = Files.createTempDirectory("day32-file-cap-")
        val config = testConfig(root)
        var githubCalls = 0
        var modelCalls = 0
        val github = HttpTransport { call ->
            githubCalls++
            if (call.uri.path.endsWith("/pulls/7")) {
                json(metadataJson(changedFiles = config.limits.maxChangedFiles + 1))
            } else {
                error("Only metadata may be fetched for an over-limit PR.")
            }
        }

        val error = assertFailsWith<ReviewInputLimitExceededException> {
            val snapshot = GitHubPullRequestClient(config, github).fetch()
            ReviewPipeline(
                config,
                ReviewGenerator {
                    modelCalls++
                    LlmReply("unexpected", """{"findings":[]}""")
                },
            ).review(snapshot, safeCorpus())
        }

        assertEquals(1, githubCalls)
        assertEquals(0, modelCalls)
        assertTrue("0 файлов проверено" in ReviewFailureDiagnostics.message(error))
    }

    private fun safeCorpus(): LoadedCorpus = LoadedCorpus(
        documents = listOf(
            CorpusDocument(
                path = "README.md",
                content = "# Architecture\nServices depend on repository ports.",
                category = CorpusCategory.DOCUMENTATION,
                priority = 0,
            ),
            CorpusDocument(
                path = "src/Repository.kt",
                content = "interface Repository { fun load(): String? }",
                category = CorpusCategory.CODE,
                priority = 1,
            ),
        ),
        metrics = CorpusMetrics(2, 2, 100, 0, false, emptyMap()),
    )

    private fun safeSnapshot(): PullRequestSnapshot {
        val file = ChangedFile(
            path = "src/Safe.kt",
            status = "modified",
            additions = 1,
            deletions = 0,
            changes = 1,
            patch = safePatch,
        )
        return PullRequestSnapshot(
            metadata = PullRequestMetadata(
                repository = "owner/repo",
                number = 7,
                title = "Safe",
                baseSha = "a".repeat(40),
                headSha = "b".repeat(40),
                draft = false,
                fromFork = false,
                changedFiles = 1,
            ),
            files = listOf(file),
            fullDiff = null,
            fullDiffTruncated = true,
        )
    }

    private data class Scenario(
        val path: String,
        val patch: String,
        val blob: String,
        val forbiddenLiteral: String,
    )

    private data class SensitiveContentCase(
        val content: String,
        val forbiddenLiteral: String,
    )

    private fun assertNonEchoArtifacts(
        error: Throwable,
        forbiddenLiteral: String,
        root: Path,
        config: AppConfig,
    ) {
        assertFalse(forbiddenLiteral in error.message.orEmpty())
        val diagnostic = MarkdownReviewRenderer().renderDiagnostic(ReviewFailureDiagnostics.message(error))
        assertFalse(forbiddenLiteral in diagnostic)
        assertTrue("0 файлов проверено" in diagnostic)

        val report = root.resolve("review.md")
        Files.writeString(report, diagnostic)
        assertFalse(forbiddenLiteral in Files.readString(report))

        val commentTransport = CommentCaptureTransport()
        GitHubReviewPublisher(config, commentTransport).publish(diagnostic)
        assertTrue(commentTransport.calls.isNotEmpty())
        assertTrue(commentTransport.calls.mapNotNull(HttpCall::body).none { forbiddenLiteral in it })
    }

    private class ScenarioGitHubTransport(private val scenario: Scenario) : HttpTransport {
        override fun execute(call: HttpCall): HttpResult = when {
            call.uri.path.endsWith("/pulls/7") && call.headers["Accept"]?.contains("diff") == true ->
                HttpResult(406, emptyMap(), "")
            call.uri.path.endsWith("/pulls/7") -> json(metadataJson())
            call.uri.path.endsWith("/pulls/7/files") -> json(filesJson(scenario))
            call.uri.path.endsWith("/git/blobs/${"c".repeat(40)}") -> json(blobJson(scenario.blob))
            else -> error("Unexpected GitHub call: ${call.method} ${call.uri}")
        }
    }

    private class CommentCaptureTransport : HttpTransport {
        val calls = mutableListOf<HttpCall>()
        override fun execute(call: HttpCall): HttpResult {
            calls += call
            return if (call.method == "GET") {
                json("[]")
            } else {
                json("""{"id":1}""", 201)
            }
        }
    }

    companion object {
        private const val oauthCredential = "abcdefghijklmnop123456"
        private const val passwordCredential = "Password\$123456789"
        private const val apiKeyCredential = "Api\$Key-123456789"
        private const val awsCredential = "Aws\$Secret-123456789"
        private val slackCredential = "xox" + "b-1234567890abcdefghijklmnop"
        private val githubCredential = "gh" + "p_1234567890abcdefghijklmnop"
        private val sensitiveContentCases = listOf(
            SensitiveContentCase(
                "Authorization: OAuth $oauthCredential",
                oauthCredential,
            ),
            SensitiveContentCase(
                """{"Authorization":"OAuth $oauthCredential"}""",
                oauthCredential,
            ),
            SensitiveContentCase(
                """{\"Authorization\":\"OAuth $oauthCredential\"}""",
                oauthCredential,
            ),
            SensitiveContentCase(
                """{"password":"$passwordCredential"}""",
                passwordCredential,
            ),
            SensitiveContentCase(
                """{"apiKey":"$apiKeyCredential"}""",
                apiKeyCredential,
            ),
            SensitiveContentCase(
                """{"aws_secret_access_key":"$awsCredential"}""",
                awsCredential,
            ),
        )
        private val safePatch = """
            @@ -1 +1,2 @@
             fun load() {
            +  println("safe")
        """.trimIndent()

        private fun metadataJson(changedFiles: Int = 1): String = ReviewJson.strict.encodeToString(
            buildJsonObject {
                put("title", JsonPrimitive("Cloud boundary"))
                put("draft", JsonPrimitive(false))
                put("changed_files", JsonPrimitive(changedFiles))
                put("base", buildJsonObject { put("sha", JsonPrimitive("a".repeat(40))) })
                put("head", buildJsonObject {
                    put("sha", JsonPrimitive("b".repeat(40)))
                    put("repo", buildJsonObject { put("full_name", JsonPrimitive("owner/repo")) })
                })
            },
        )

        private fun filesJson(scenario: Scenario): String = ReviewJson.strict.encodeToString(
            buildJsonArray {
                add(buildJsonObject {
                    put("filename", JsonPrimitive(scenario.path))
                    put("status", JsonPrimitive("modified"))
                    put("sha", JsonPrimitive("c".repeat(40)))
                    put("additions", JsonPrimitive(1))
                    put("deletions", JsonPrimitive(0))
                    put("changes", JsonPrimitive(1))
                    put("patch", JsonPrimitive(scenario.patch))
                })
            },
        )

        private fun blobJson(content: String): String = ReviewJson.strict.encodeToString(
            buildJsonObject {
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                put("encoding", JsonPrimitive("base64"))
                put("size", JsonPrimitive(bytes.size))
                put("content", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
            },
        )

        private fun json(body: String, status: Int = 200): HttpResult =
            HttpResult(status, mapOf("content-type" to listOf("application/json")), body)
    }
}
