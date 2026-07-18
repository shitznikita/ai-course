package ru.ai.course.day35.releaseprep

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixtureAndReportTest {
    @Test
    fun fixtureRejectsUnknownFieldsOversizeAndSymlinkBeforeDecode() {
        val root = root()
        val fixture = root.resolve("$module/fixtures/release-input.json")
        fixture.writeText("""{"unknown":true}""")
        assertFails { FixtureLoader(root).load(fixture) }
        fixture.writeText("""{"source":"one","source":"two"}""")
        assertFails { FixtureLoader(root).load(fixture) }
        fixture.writeText("""{"outer":{"x":1,"x":2}}""")
        assertFails { FixtureLoader(root).load(fixture) }
        fixture.writeText("x".repeat(Limits.FIXTURE_REPORT_BYTES + 1))
        assertFails { FixtureLoader(root).load(fixture) }
        Files.delete(fixture)
        val target = root.resolve("target.json").also { it.writeText("{}") }
        Files.createSymbolicLink(fixture, target)
        assertFails { FixtureLoader(root).load(fixture) }
    }

    @Test
    fun fixturePipelineProducesValidatedServerOwnedReport() {
        val root = root()
        val facts = facts(root)
        val plan = ReleasePlanValidator.validate(FixturePlan.generate(briefPath), authorization())
        val readiness = ServerDecision.decide(facts.checks, stable = true, modelValid = true)
        val report = ReleaseReportWriter(root).writeFixture(
            ReleaseReportRenderer.render(facts, plan, readiness),
        )
        val content = report.path.readText()
        assertEquals("READY_FOR_HUMAN_REVIEW", readiness)
        assertTrue("synthetic downstream fixture" in content)
        assertTrue("AI recommendation" in content && "advisory" in content)
        assertTrue("Server readiness: **READY_FOR_HUMAN_REVIEW**" in content)
        assertTrue("AI evidence coverage: `2/3`" in content)
    }

    @Test
    fun reportRejectsParentOrFileSymlink() {
        val root = root()
        val modulePath = root.resolve(module)
        val outside = root.resolve("outside").also(Files::createDirectory)
        Files.createSymbolicLink(modulePath.resolve("reports"), outside)
        assertFails { ReleaseReportWriter(root).writeFixture("safe") }
        Files.delete(modulePath.resolve("reports"))
        Files.createDirectory(modulePath.resolve("reports"))
        val target = root.resolve("target.md").also { it.writeText("target") }
        Files.createSymbolicLink(modulePath.resolve("reports/fixture-release-readiness.md"), target)
        assertFails { ReleaseReportWriter(root).writeFixture("safe") }
    }

    @Test
    fun repeatedAtomicReplaceIsDeterministicAndHashesExactPersistedBytes() {
        val root = root()
        val writer = ReleaseReportWriter(root)
        val first = writer.writeFixture("deterministic\n")
        val second = writer.writeFixture("deterministic\n")
        assertEquals(first.sha256, second.sha256)
        assertEquals("deterministic\n", second.path.readText())
        assertEquals(sha256Bytes(Files.readAllBytes(second.path)), second.sha256)
        assertNoTemps(second.path.parent)
    }

    @Test
    fun modelValidationAndLateSnapshotDriftPreservePreviousLiveReport() {
        val root = gitRoot()
        val writer = ReleaseReportWriter(root)
        val inspector = GitReleaseInspector(root)
        val expected = inspector.snapshot()
        val previous = writer.writeLive("previous live report\n") {
            SnapshotGate.requireStable(expected, inspector.snapshot())
        }
        val previousBytes = Files.readAllBytes(previous.path)

        assertFails {
            ReleasePlanValidator.validate(
                FixturePlan.generate(briefPath).copy(recommendation = "PUBLISH"),
                authorization(),
            )
        }
        assertContentEquals(previousBytes, Files.readAllBytes(previous.path))
        assertEquals(previous.sha256, sha256Bytes(Files.readAllBytes(previous.path)))

        ReleasePlanValidator.validate(FixturePlan.generate(briefPath), authorization())
        var sawForcedTemp = false
        assertFails {
            writer.writeLive("must not happen") {
                Files.list(previous.path.parent).use { files ->
                    sawForcedTemp = files.anyMatch { it.fileName.toString().endsWith(".tmp") }
                }
                root.resolve(readme).writeText("drift")
                SnapshotGate.requireStable(expected, inspector.snapshot())
            }
        }
        assertTrue(sawForcedTemp)
        assertContentEquals(previousBytes, Files.readAllBytes(previous.path))
        assertEquals(previous.sha256, sha256Bytes(Files.readAllBytes(previous.path)))
        assertNoTemps(previous.path.parent)
    }

    @Test
    fun successfulFinalGateReplacesPreviousLiveReportOnlyAfterTempForce() {
        val root = gitRoot()
        val writer = ReleaseReportWriter(root)
        val previous = writer.writeLive("previous live report\n") {}
        val previousBytes = Files.readAllBytes(previous.path)
        var sawForcedTemp = false
        val replacement = writer.writeLive("replacement live report\n") {
            assertContentEquals(previousBytes, Files.readAllBytes(previous.path))
            assertEquals(previous.sha256, sha256Bytes(Files.readAllBytes(previous.path)))
            Files.list(previous.path.parent).use { files ->
                sawForcedTemp = files.anyMatch { it.fileName.toString().endsWith(".tmp") }
            }
        }
        assertTrue(sawForcedTemp)
        assertEquals("replacement live report\n", replacement.path.readText())
        assertEquals(replacement.sha256, sha256Bytes(Files.readAllBytes(replacement.path)))
        assertFalse(previous.sha256 == replacement.sha256)
        assertNoTemps(replacement.path.parent)
    }

    @Test
    fun fixturesRequireSemanticReadmeAndReviewedBriefWhileBuildRemainsManifestOnly() {
        val source = courseRoot()
        val original = FixtureLoader(source).load()
        listOf(
            original.copy(manifest = changedManifest(original, readme) { it.copy(binary = true, additions = 0) }),
            original.copy(manifest = changedManifest(original, readme) {
                it.copy(status = "M", additions = 0, deletions = 0)
            }),
            original.copy(manifest = changedManifest(original, briefPath) { it.copy(binary = true, additions = 0) }),
            original.copy(manifest = changedManifest(original, briefPath) {
                it.copy(status = "T", additions = 0, deletions = 0)
            }),
        ).forEach { invalid ->
            val root = root()
            writeFixture(root, invalid)
            var credentials = 0
            var clients = 0
            assertFails {
                ReleaseApplication(
                    root,
                    { credentials++; error("credential") },
                    { _, _ -> clients++; error("HTTP") },
                ).runArgs(listOf("fixture-demo"))
            }
            assertEquals(0, credentials)
            assertEquals(0, clients)
        }

        val root = root()
        val buildBinary = original.copy(
            manifest = changedManifest(original, "$module/build.gradle.kts") {
                it.copy(binary = true, additions = 0, deletions = 0)
            },
        )
        writeFixture(root, buildBinary)
        ReleaseApplication(root).runArgs(listOf("fixture-demo"))
        assertTrue(
            "AI evidence coverage: `2/3`" in
                root.resolve("$module/reports/fixture-release-readiness.md").readText(),
        )
    }

    private fun root(): Path {
        val root = createTempDirectory("day35-report").toRealPath()
        Files.createDirectories(root.resolve("$module/fixtures"))
        return root
    }

    private fun gitRoot(): Path {
        val root = root()
        root.resolve(".gitignore").writeText("$module/reports/\n")
        root.resolve(readme).writeText("stable")
        git(root, "init", "-q")
        git(root, "config", "user.email", "test@example.com")
        git(root, "config", "user.name", "Test")
        git(root, "add", ".")
        git(root, "commit", "-qm", "base")
        return root
    }

    private fun writeFixture(root: Path, fixture: ReleaseFixture) {
        root.resolve("$module/fixtures/release-input.json")
            .writeText(AppJson.strict.encodeToString(ReleaseFixture.serializer(), fixture))
    }

    private fun changedManifest(
        fixture: ReleaseFixture,
        path: String,
        change: (ManifestItem) -> ManifestItem,
    ): ReleaseManifest {
        val items = fixture.manifest.items.map { original ->
            val item = if (original.path == path) change(original) else original
            item.copy(fingerprint = fingerprint(item))
        }
        return fixture.manifest.copy(
            items = items,
            fingerprint = sha256(
                fixture.manifest.baseSha,
                fixture.manifest.mergeBaseSha,
                fixture.manifest.headSha,
                *items.map(ManifestItem::fingerprint).toTypedArray(),
            ),
        )
    }

    private fun fingerprint(item: ManifestItem) = sha256(
        item.path,
        item.status,
        item.additions.toString(),
        item.deletions.toString(),
        item.binary.toString(),
        item.oldObjectId ?: "0000000000000000000000000000000000000000",
        item.newObjectId ?: "0000000000000000000000000000000000000000",
        item.oldMode,
        item.newMode,
    )

    private fun git(root: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args)).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        require(process.waitFor() == 0) { output }
    }

    private fun facts(root: Path): ReleaseFacts {
        val base = "1".repeat(40)
        val head = "2".repeat(40)
        val items = listOf(
            item(readme, base, head),
            item("$module/build.gradle.kts", base, head),
            item(briefPath, base, head),
        )
        val manifest = ReleaseManifest(
            base,
            base,
            head,
            items,
            sha256(base, base, head, *items.map(ManifestItem::fingerprint).toTypedArray()),
        )
        val release = InspectedRelease(
            "fixture-main",
            base,
            base,
            head,
            snapshot(root, "2"),
            manifest,
            module,
        )
        val evidence = EvidenceContract.build(
            release,
            ReleaseBriefDocument(
                "Prepare a reviewed release candidate.",
                listOf("Complete manifest and checks."),
                listOf("Trusted local branch only."),
                listOf("Show typed evidence."),
            ),
        )
        val prompt = PromptBundle("system", "user", evidence, 10, 6_154, sha256("system", "user"))
        return ReleaseFacts(
            "synthetic downstream fixture",
            "fixture/day-35",
            "fixture-main",
            base,
            head,
            release.snapshot,
            manifest,
            module,
            listOf(CheckResult("fixture-contract", "synthetic", 0, 0)),
            prompt,
        )
    }

    private fun item(path: String, old: String, new: String): ManifestItem = ManifestItem(
        path,
        "M",
        1,
        1,
        false,
        old,
        new,
        "100644",
        "100644",
        sha256(path, "M", "1", "1", "false", old, new, "100644", "100644"),
    )

    private fun snapshot(root: Path, seed: String) = SnapshotFingerprint(
        root.toString(),
        "fixture/day-35",
        seed.repeat(40),
        "3".repeat(40),
        "3".repeat(40),
        sha256(""),
    )

    private fun courseRoot(): Path = Path.of("").toRealPath().let {
        if (Files.isRegularFile(it.resolve("fixtures/release-input.json"))) it.parent else it
    }

    private fun assertNoTemps(reports: Path) {
        Files.list(reports).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    private fun authorization(): Map<String, EvidenceKind> = mapOf(
        readme to EvidenceKind.REQUIRED_README_CHANGE,
        briefPath to EvidenceKind.REVIEWED_RELEASE_BRIEF,
    )

    private companion object {
        const val module = "day-35-ai-release-prep-kotlin"
        const val readme = "$module/README.md"
        const val briefPath = "$module/release-brief.json"
    }
}
