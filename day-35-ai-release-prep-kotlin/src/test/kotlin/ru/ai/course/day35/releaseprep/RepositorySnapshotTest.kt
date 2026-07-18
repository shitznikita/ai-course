package ru.ai.course.day35.releaseprep

import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositorySnapshotTest {
    @Test
    fun manifestAccepts80AndRejects81WithoutPartialCoverage() {
        val (accepted, base) = repositoryWithChanges(80)
        assertEquals(80, GitReleaseInspector(accepted).inspect(base).manifest.items.size)
        val (rejected, rejectedBase) = repositoryWithChanges(81)
        assertFails { GitReleaseInspector(rejected).inspect(rejectedBase) }
        val raw = parseRaw(
            (
                ":100644 100755 ${"1".repeat(40)} ${"2".repeat(40)} M\u0000mode.kt\u0000" +
                    ":100644 000000 ${"3".repeat(40)} ${"0".repeat(40)} D\u0000deleted.kt\u0000"
                ).toByteArray(),
        )
        assertEquals(setOf("M", "D"), raw.values.map(RawItem::status).toSet())
        assertTrue(parseStats("-\t-\tbinary.png\u0000".toByteArray()).getValue("binary.png").third)
        assertFails { parseRaw(":100644 100644 1 2 R100\u0000old\u0000new\u0000".toByteArray()) }
        assertFails { parseNames("A\u0000late-without-nul".toByteArray()) }
        assertFails { ContentPolicy.validatePath("../escape") }
        assertFails { ContentPolicy.validatePath("bad\u0085path") }
    }

    @Test
    fun rootAndSingleModuleAllowlistRejectsRootOnlyUnknownMultiAndOutsidePaths() {
        val settings = """include("$module")"""
        assertEquals(module, ReleaseShapePolicy.validate(listOf(readme, ".gitignore"), settings))
        listOf(
            listOf(".gitignore"),
            listOf(readme, "gradle.properties"),
            listOf(readme, "day-34-other-kotlin/README.md"),
            listOf(readme, "docs/outside.md"),
            listOf("$module/.env", readme),
        ).forEach { paths -> assertFails { ReleaseShapePolicy.validate(paths, settings) } }
    }

    @Test
    fun settingsRequiresExactHeadIncludeAndRegularNonSymlinkRequiredFiles() {
        val paths = listOf(readme)
        assertFails { ReleaseShapePolicy.validate(paths, """include("${module}-extra")""") }
        assertFails { ReleaseShapePolicy.validate(paths, """include("$module")\ninclude("$module")""") }
        val root = createTempDirectory("day35-symlink").toRealPath()
        Files.createDirectories(root.resolve(module))
        root.resolve("settings.gradle.kts").writeText("""include("$module")""")
        root.resolve(readme).writeText("readme")
        root.resolve(briefPath).writeText(briefJson("brief"))
        val target = root.resolve("outside.gradle").also { it.writeText("plugins {}") }
        Files.createSymbolicLink(root.resolve("$module/build.gradle.kts"), target)
        assertFails { ReleaseShapePolicy.requireRegularModuleFiles(root, module) }
    }

    @Test
    fun subprocessTimeoutAndOutputCapFailClosed() {
        val root = createTempDirectory("day35-command")
        val runner = BoundedCommandRunner(root)
        assertFails { runner.run(listOf("/bin/sh", "-c", "sleep 2"), Duration.ofMillis(50), 128) }
        assertFails { runner.run(listOf("/bin/sh", "-c", "yes x"), Duration.ofSeconds(2), 32) }
    }

    @Test
    fun childCheckEnvironmentScrubsCredentialGitJvmGradleAndProxyVariables() {
        val cleaned = BoundedCommandRunner.scrubbedEnvironment(
            mapOf(
                "HOME" to "/tmp/home",
                "PATH" to "/bin",
                "LLM_API_KEY" to "secret",
                "JAVA_OPTS" to "-Dhostile=true",
                "JAVA_TOOL_OPTIONS" to "-Dhostile=true",
                "JDK_JAVA_OPTIONS" to "-Dhostile=true",
                "_JAVA_OPTIONS" to "-Dhostile=true",
                "GRADLE_OPTS" to "-Dhostile=true",
                "GIT_DIR" to "/tmp/evil",
                "GIT_WORK_TREE" to "/tmp/evil",
                "GIT_COMMON_DIR" to "/tmp/evil",
                "GIT_INDEX_FILE" to "/tmp/evil",
                "GIT_OBJECT_DIRECTORY" to "/tmp/evil",
                "GIT_ALTERNATE_OBJECT_DIRECTORIES" to "/tmp/evil",
                "GIT_NAMESPACE" to "evil",
                "HTTPS_PROXY" to "proxy",
            ),
        )
        assertEquals(mapOf("HOME" to "/tmp/home", "PATH" to "/bin"), cleaned)
        assertFalse(cleaned.keys.any { it.startsWith("GIT_") || "JAVA" in it || "GRADLE" in it || "PROXY" in it })
    }

    @Test
    fun changedHeadOrWorktreeAfterChecksStopsBeforeCredentialAndHttp() {
        val expected = snapshot("a")
        var credentialReads = 0
        var httpCalls = 0
        assertFails {
            ExternalCallGate.execute(
                expected,
                snapshot("b"),
                { credentialReads++; "credential" },
                { httpCalls++ },
            )
        }
        assertEquals(0, credentialReads)
        assertEquals(0, httpCalls)
    }

    @Test
    fun requiredTypedEvidenceUsesManifestFactsAndReviewedBriefWithoutRepositoryPatches() {
        listOf("binary", "mode").forEach { kind ->
            val (root, base) = evidenceRepository(kind)
            var credentials = 0
            var clients = 0
            assertFails {
                ReleaseApplication(
                    root,
                    { credentials++; error("credential construction") },
                    { _, _ -> clients++; error("HTTP construction") },
                ).runArgs(listOf("prepare", "--base", base))
            }
            assertEquals(0, credentials)
            assertEquals(0, clients)
        }

        val (root, base) = evidenceRepository("optional")
        val release = GitReleaseInspector(root).inspect(base)
        val evidence = EvidenceBuilder(root).build(release)
        assertEquals(listOf(readme, briefPath), evidence.items.map(EvidenceItem::path))
        assertFalse(release.manifest.items.first { it.path.endsWith("build.gradle.kts") }.supportsSemanticEvidence())
        assertFalse(release.manifest.items.first { it.path.endsWith(".env.example") }.supportsSemanticEvidence())
        assertFalse(item("deleted", "D", 1, 0, false).supportsSemanticEvidence())
        assertFalse(item("type", "T", 1, 0, false).supportsSemanticEvidence())

        val prompt = PromptRenderer.render(release.snapshot.branch, base, release, evidence)
        assertFalse("diff --git" in prompt.user)
        assertFalse("\"patch\"" in prompt.user)
        assertTrue("REVIEWED_RELEASE_BRIEF" in prompt.user)

        val (subjectRoot, subjectBase) = evidenceRepository("subject")
        val subjectRelease = GitReleaseInspector(subjectRoot).inspect(subjectBase)
        val subjectPrompt = PromptRenderer.render(
            subjectRelease.snapshot.branch,
            subjectBase,
            subjectRelease,
            EvidenceBuilder(subjectRoot).build(subjectRelease),
        )
        assertFalse("request.header" in subjectPrompt.user)
        assertFalse(reviewSafeFixture("YWIkMTIzNDU2Nzg5MA==") in subjectPrompt.user)
        assertFalse("COMMIT_SUBJECTS" in subjectPrompt.user)
    }

    @Test
    fun arbitraryReadmeAndBuildSourcePayloadsAreNeverTransmitted() {
        unsafeReleaseTextCases().forEachIndexed { index, payload ->
            val kind = if (index % 2 == 0) "payload-readme" else "payload-build"
            val (root, base) = evidenceRepository(kind, payload)
            val release = GitReleaseInspector(root).inspect(base)
            val prompt = PromptRenderer.render(
                release.snapshot.branch,
                base,
                release,
                EvidenceBuilder(root).build(release),
            )
            assertFalse(payload in prompt.user, "Repository source escaped into prompt: $payload")
            assertFalse("diff --git" in prompt.user)
        }
    }

    @Test
    fun sharedUnsafeMatrixInReviewedBriefStopsBeforeCredentialClientHttpAndReport() {
        unsafeReleaseTextCases().forEach { payload ->
            val (root, base) = evidenceRepository("payload-brief", payload)
            assertFails("Typed brief accepted: $payload") {
                EvidenceBuilder(root).build(GitReleaseInspector(root).inspect(base))
            }
            var credentials = 0
            var clients = 0
            assertFails("Live admission accepted: $payload") {
                ReleaseApplication(
                    root,
                    { credentials++; error("credential") },
                    { _, _ -> clients++; error("HTTP") },
                ).runArgs(listOf("prepare", "--base", base))
            }
            assertEquals(0, credentials, "Credential construction for: $payload")
            assertEquals(0, clients, "Client construction for: $payload")
            assertFalse(Files.exists(root.resolve("$module/reports/release-readiness.md")))
        }
    }

    @Test
    fun duplicateReviewedBriefKeysStopBeforeCredentialClientHttpAndReport() {
        listOf("duplicate-brief-root", "duplicate-brief-escaped", "duplicate-brief-nested").forEach { kind ->
            val (root, base) = evidenceRepository(kind)
            assertFails { EvidenceBuilder(root).build(GitReleaseInspector(root).inspect(base)) }
            var credentials = 0
            var clients = 0
            assertFails {
                ReleaseApplication(
                    root,
                    { credentials++; error("credential") },
                    { _, _ -> clients++; error("HTTP") },
                ).runArgs(listOf("prepare", "--base", base))
            }
            assertEquals(0, credentials)
            assertEquals(0, clients)
            assertFalse(Files.exists(root.resolve("$module/reports/release-readiness.md")))
        }
    }

    @Test
    fun sharedBenignAndPlaceholderMatrixReachesPrepareDryRunWithoutElizaEffects() {
        (benignReleaseTextCases() + exactPlaceholderCases()).forEach { payload ->
            val (root, base) = evidenceRepository("payload-brief", payload)
            var credentials = 0
            var clients = 0
            ReleaseApplication(
                root,
                { credentials++; error("credential construction") },
                { _, _ -> clients++; error("client construction") },
            ).runArgs(listOf("prepare-dry-run", "--base", base))
            assertEquals(0, credentials)
            assertEquals(0, clients)
            assertFalse(Files.exists(root.resolve("$module/reports/release-readiness.md")))
        }
    }

    @Test
    fun sharedBenignAndPlaceholderMatrixReachesSuccessfulStubLiveReport() {
        fun providerBody(content: String): String =
            """{"choices":[{"message":{"content":${AppJson.strict.encodeToString(content)}}}]}"""

        (benignReleaseTextCases() + exactPlaceholderCases()).forEach { payload ->
            val (root, base) = evidenceRepository("payload-brief", payload)
            val rawPlan = AppJson.strict.encodeToString(
                FixturePlan.generate(briefPath).copy(
                    summary = listOf(AiText(payload, listOf(briefPath))),
                ),
            )
            var credentialReads = 0
            var clients = 0
            var httpCalls = 0
            runCatching {
                ReleaseApplication(
                    root,
                    { onRead ->
                        CredentialSource {
                            credentialReads++
                            onRead()
                            "fixture-credential"
                        }
                    },
                    { provider, onCall ->
                        clients++
                        ElizaClient(
                            provider,
                            HttpExecutor {
                                httpCalls++
                                RawHttpResponse(
                                    200,
                                    ByteArrayInputStream(providerBody(rawPlan).toByteArray()),
                                )
                            },
                            onCall,
                        )
                    },
                ).runArgs(listOf("prepare", "--base", base))
            }.getOrElse { throw AssertionError("Expected successful stub report for: $payload", it) }
            assertEquals(1, credentialReads)
            assertEquals(1, clients)
            assertEquals(1, httpCalls)
            val report = root.resolve("$module/reports/release-readiness.md")
            assertTrue(Files.isRegularFile(report))
            assertTrue("Server readiness: **READY_FOR_HUMAN_REVIEW**" in report.toFile().readText())
            assertNoReportTemps(report.parent)
        }
    }

    @Test
    fun fixtureEvalAndPrepareDryRunConstructNoCredentialOrElizaHttpAndPreserveOldReport() {
        val (root, base) = evidenceRepository("application")
        var credentials = 0
        var clients = 0
        val app = ReleaseApplication(
            root,
            { credentials++; error("credential construction") },
            { _, _ -> clients++; error("HTTP construction") },
        )
        app.runArgs(listOf("fixture-demo"))
        app.runArgs(listOf("eval-dry-run"))
        val liveReport = ReleaseReportWriter(root).writeLive("previous live report\n") {}
        app.runArgs(listOf("prepare-dry-run", "--base", base))
        assertEquals("previous live report\n", liveReport.path.toFile().readText())
        assertEquals(liveReport.sha256, sha256Bytes(Files.readAllBytes(liveReport.path)))
        assertEquals(0, credentials)
        assertEquals(0, clients)
    }

    @Test
    fun everyFailedLiveStagePreservesPriorReportAndSuccessfulStubReplacesIt() {
        val validPlan = AppJson.strict.encodeToString(FixturePlan.generate(briefPath))

        fun providerBody(content: String): String =
            """{"choices":[{"message":{"content":${AppJson.strict.encodeToString(content)}}}]}"""

        fun client(
            provider: ProviderConfig,
            body: () -> RawHttpResponse,
            onCall: () -> Unit,
        ): ElizaClient = ElizaClient(provider, HttpExecutor { body() }, onCall = onCall)

        fun assertPreserved(
            kind: String = "application",
            baseOverride: String? = null,
            providerFactory: () -> ProviderConfig = { FixedProvider.preflight() },
            credentialFactory: ((() -> Unit) -> CredentialSource) =
                { onRead -> CredentialSource { onRead(); "fixture-credential" } },
            clientFactory: (Path, ProviderConfig, () -> Unit) -> ElizaClient,
        ) {
            val (root, base) = evidenceRepository(kind)
            val previous = ReleaseReportWriter(root).writeLive("previous live report\n") {}
            val before = previous.path.readBytes()
            assertFails {
                ReleaseApplication(
                    root,
                    credentialFactory,
                    { provider, onCall -> clientFactory(root, provider, onCall) },
                    providerFactory,
                ).runArgs(listOf("prepare", "--base", baseOverride ?: base))
            }
            assertContentEquals(before, previous.path.readBytes())
            assertEquals(previous.sha256, sha256Bytes(previous.path.readBytes()))
            assertNoReportTemps(previous.path.parent)
        }

        var credentials = 0
        var clients = 0
        assertPreserved(
            providerFactory = {
                FixedProvider.preflight(NamedEnvironment {
                    if (it == "LLM_API_URL") "${FixedProvider.URL}/" else null
                })
            },
            credentialFactory = { credentials++; error("credential construction") },
            clientFactory = { _, _, _ -> clients++; error("client construction") },
        )
        assertEquals(0, credentials)
        assertEquals(0, clients)

        assertPreserved(
            baseOverride = "missing-ref",
            credentialFactory = { credentials++; error("credential construction") },
            clientFactory = { _, _, _ -> clients++; error("client construction") },
        )
        assertEquals(0, credentials)
        assertEquals(0, clients)

        assertPreserved(
            kind = "check-fail",
            credentialFactory = { credentials++; error("credential construction") },
            clientFactory = { _, _, _ -> clients++; error("client construction") },
        )
        assertEquals(0, credentials)
        assertEquals(0, clients)

        assertPreserved(
            credentialFactory = { CredentialSource { error("credential failure") } },
            clientFactory = { _, provider, onCall ->
                client(provider, { error("HTTP must not run") }, onCall)
            },
        )
        assertPreserved(
            clientFactory = { _, provider, onCall ->
                client(provider, { RawHttpResponse(500, ByteArrayInputStream("{}".toByteArray())) }, onCall)
            },
        )
        assertPreserved(
            clientFactory = { _, provider, onCall ->
                client(
                    provider,
                    {
                        RawHttpResponse(
                            200,
                            ByteArrayInputStream(
                                providerBody(
                                    reviewSafeFixture(
                                        "QXV0aG9yaXpcdVpaWlphdGlvbjogT0F1dGggYWIkMTIzNDU2Nzg5MA==",
                                    ),
                                ).toByteArray(),
                            ),
                        )
                    },
                    onCall,
                )
            },
        )
        assertPreserved(
            clientFactory = { _, provider, onCall ->
                val invalid = AppJson.strict.encodeToString(
                    FixturePlan.generate(briefPath).copy(recommendation = "PUBLISH"),
                )
                client(
                    provider,
                    { RawHttpResponse(200, ByteArrayInputStream(providerBody(invalid).toByteArray())) },
                    onCall,
                )
            },
        )
        assertPreserved(
            clientFactory = { _, provider, onCall ->
                val duplicate = validPlan.replace(
                    "\"recommendation\": \"PROCEED\"",
                    "\"recommendation\": \"HOLD\",\n    \"recommendation\": \"PROCEED\"",
                )
                client(
                    provider,
                    { RawHttpResponse(200, ByteArrayInputStream(providerBody(duplicate).toByteArray())) },
                    onCall,
                )
            },
        )
        assertPreserved(
            clientFactory = { root, provider, onCall ->
                client(
                    provider,
                    {
                        root.resolve(readme).writeText("drift after HTTP")
                        RawHttpResponse(200, ByteArrayInputStream(providerBody(validPlan).toByteArray()))
                    },
                    onCall,
                )
            },
        )

        val (successRoot, successBase) = evidenceRepository("application")
        val previous = ReleaseReportWriter(successRoot).writeLive("previous live report\n") {}
        val previousBytes = previous.path.readBytes()
        ReleaseApplication(
            successRoot,
            { onRead -> CredentialSource { onRead(); "fixture-credential" } },
            { provider, onCall ->
                client(
                    provider,
                    { RawHttpResponse(200, ByteArrayInputStream(providerBody(validPlan).toByteArray())) },
                    onCall,
                )
            },
        ).runArgs(listOf("prepare", "--base", successBase))
        val replacementBytes = previous.path.readBytes()
        assertFalse(previousBytes.contentEquals(replacementBytes))
        assertFalse(previous.sha256 == sha256Bytes(replacementBytes))
        assertNoReportTemps(previous.path.parent)
    }

    private fun repositoryWithChanges(count: Int): Pair<Path, String> {
        val root = createTempDirectory("day35-git").toRealPath()
        val modulePath = root.resolve(module)
        Files.createDirectories(modulePath)
        root.resolve("settings.gradle.kts").writeText("""include("$module")""")
        modulePath.resolve("build.gradle.kts").writeText("plugins {}")
        root.resolve(readme).writeText("base")
        root.resolve(briefPath).writeText(briefJson("base brief"))
        git(root, "init", "-q")
        git(root, "config", "user.email", "test@example.com")
        git(root, "config", "user.name", "Test")
        git(root, "add", ".")
        git(root, "commit", "-qm", "base")
        val base = git(root, "rev-parse", "HEAD").trim()
        root.resolve(readme).writeText("changed")
        repeat(count - 1) { modulePath.resolve("file-$it.txt").writeText("value $it") }
        git(root, "add", ".")
        git(root, "commit", "-qm", "head")
        return root to base
    }

    private fun evidenceRepository(kind: String, payload: String? = null): Pair<Path, String> {
        val root = createTempDirectory("day35-evidence").toRealPath()
        val modulePath = root.resolve(module)
        Files.createDirectories(modulePath.resolve("fixtures"))
        root.resolve(".gitignore").writeText("$module/reports/\n")
        root.resolve("settings.gradle.kts").writeText("""include("$module")""")
        modulePath.resolve("build.gradle.kts").writeText("plugins {}")
        modulePath.resolve(".env.example").writeText("LLM_API_KEY=replace-with-oauth-token")
        root.resolve(readme).writeText("base")
        root.resolve(briefPath).writeText(briefJson("base brief"))
        Files.copy(
            courseRoot().resolve("$module/fixtures/release-input.json"),
            modulePath.resolve("fixtures/release-input.json"),
        )
        root.resolve("gradlew").writeText(
            if (kind == "check-fail") "#!/bin/sh\nexit 1\n" else "#!/bin/sh\nexit 0\n",
        )
        root.resolve("gradlew").toFile().setExecutable(true)
        git(root, "init", "-q")
        git(root, "config", "user.email", "test@example.com")
        git(root, "config", "user.name", "Test")
        git(root, "add", ".")
        git(root, "commit", "-qm", "base")
        val base = git(root, "rev-parse", "HEAD").trim()

        root.resolve(readme).writeText("changed")
        root.resolve(briefPath).writeText(briefJson("changed reviewed release brief"))
        when (kind) {
            "binary" -> root.resolve(readme).writeBytes(byteArrayOf(0, 1, 2, 3))
            "mode" -> {
                root.resolve(readme).writeText("base")
                root.resolve(readme).toFile().setExecutable(true)
            }
            "optional" -> {
                modulePath.resolve("build.gradle.kts").toFile().setExecutable(true)
                modulePath.resolve(".env.example").writeBytes(byteArrayOf(0, 1, 2))
            }
            "payload-readme" -> root.resolve(readme).writeText(requireNotNull(payload))
            "payload-build" -> modulePath.resolve("build.gradle.kts").writeText(requireNotNull(payload))
            "payload-brief" -> root.resolve(briefPath).writeText(briefJson(requireNotNull(payload)))
            "duplicate-brief-root" -> root.resolve(briefPath).writeText(
                """{"objective":"one","objective":"two","highlights":["h"],"operationalNotes":[],"videoFocus":["v"]}""",
            )
            "duplicate-brief-escaped" -> root.resolve(briefPath).writeText(
                """{"objective":"one","\u006fbjective":"two","highlights":["h"],"operationalNotes":[],"videoFocus":["v"]}""",
            )
            "duplicate-brief-nested" -> root.resolve(briefPath).writeText(
                """{"objective":"one","highlights":["h"],"operationalNotes":[],"videoFocus":["v"],"extra":{"x":1,"x":2}}""",
            )
        }
        git(root, "add", "-A")
        val subject = if (kind == "subject") {
            reviewSafeFixture(
                "cmVxdWVzdC5oZWFkZXIoIkF1dGhvcml6YXRpb24iLCAiT0F1dGggYWIkMTIzNDU2Nzg5MCIpIHBhc3N3b3JkXG49YWIkMTIzNDU2Nzg5MA==",
            )
        } else {
            "head"
        }
        git(root, "commit", "-qm", subject)
        return root to base
    }

    private fun briefJson(objective: String): String = AppJson.strict.encodeToString(
        ReleaseBriefDocument.serializer(),
        ReleaseBriefDocument(
            objective,
            listOf("Complete manifest and deterministic checks."),
            listOf("Trusted local release branch only."),
            listOf("Show typed evidence and server readiness."),
        ),
    )

    private fun item(
        path: String,
        status: String,
        additions: Int,
        deletions: Int,
        binary: Boolean,
    ): ManifestItem = ManifestItem(
        path,
        status,
        additions,
        deletions,
        binary,
        null,
        "2".repeat(40),
        "100644",
        "100644",
        "fingerprint",
    )

    private fun courseRoot(): Path = Path.of("").toRealPath().let {
        if (Files.isRegularFile(it.resolve("fixtures/release-input.json"))) it.parent else it
    }

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        require(process.waitFor() == 0) { output }
        return output
    }

    private fun assertNoReportTemps(reports: Path) {
        Files.list(reports).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    private fun snapshot(seed: String) = SnapshotFingerprint(
        "/repo",
        "branch",
        seed.repeat(40),
        "1".repeat(40),
        "1".repeat(40),
        sha256(seed),
    )

    private companion object {
        const val module = "day-35-ai-release-prep-kotlin"
        const val readme = "$module/README.md"
        const val briefPath = "$module/release-brief.json"
    }
}
