package ru.ai.course.day35.releaseprep
import java.nio.file.Path
import kotlin.system.exitProcess
private enum class Mode { FIXTURE_DEMO, EVAL_DRY_RUN, PREPARE_DRY_RUN, PREPARE }
private data class Cli(val mode: Mode, val base: String?)
fun main(args: Array<String>) {
    try {
        ReleaseApplication(Path.of("").toRealPath()).runArgs(args.toList())
    } catch (failure: Exception) {
        System.err.println("ERROR: ${failure.message}")
        exitProcess(1)
    }
}
internal class ReleaseApplication(
    private val repository: Path,
    private val credentialFactory: ((() -> Unit) -> CredentialSource) =
        { onRead -> FileCredentialSource(repository, onRead = onRead) },
    private val clientFactory: (ProviderConfig, () -> Unit) -> ElizaClient =
        { provider, onCall -> ElizaClient(provider, onCall = onCall) },
    private val providerFactory: () -> ProviderConfig = { FixedProvider.preflight() },
) {
    internal fun runArgs(args: List<String>) = run(parseCli(args))
    private fun run(cli: Cli) {
        when (cli.mode) {
            Mode.FIXTURE_DEMO -> fixtureDemo()
            Mode.EVAL_DRY_RUN -> evalDryRun()
            Mode.PREPARE_DRY_RUN -> prepare(requireNotNull(cli.base), live = false)
            Mode.PREPARE -> prepare(requireNotNull(cli.base), live = true)
        }
    }
    private fun fixtureDemo() {
        val fixture = FixtureLoader(repository).load()
        val prepared = validateFixture(fixture)
        val plan = ReleasePlanValidator.validate(fixture.ai, authorization(prepared.prompt.evidence))
        val facts = ReleaseFacts(
            fixture.source,
            fixture.branch,
            fixture.base,
            fixture.mergeBase,
            fixture.head,
            prepared.snapshot,
            fixture.manifest,
            prepared.release.module,
            listOf(CheckResult("fixture-contract", "synthetic downstream validation", 0, 0)),
            prepared.prompt,
        )
        val readiness = ServerDecision.decide(facts.checks, stable = true, modelValid = true)
        val report = ReleaseReportWriter(repository).writeFixture(ReleaseReportRenderer.render(facts, plan, readiness))
        println("SOURCE: ${fixture.source}")
        println("REAL GIT/CHECK EXECUTION: NOT CLAIMED")
        printCoverage(fixture.manifest, prepared.prompt)
        println(ReleaseReportRenderer.console(plan))
        println("SERVER READINESS: $readiness")
        println("REPORT: ${repository.relativize(report.path)}")
        println("REPORT SHA-256: ${report.sha256}")
        printCounters(0, 0, 0)
    }
    private fun evalDryRun() {
        val fixture = FixtureLoader(repository).load()
        val prepared = validateFixture(fixture)
        val authorized = authorization(prepared.prompt.evidence)
        var passed = 0
        ReleasePlanValidator.validate(fixture.ai, authorized)
        passed++
        runCatching {
            ReleasePlanValidator.validate(
                fixture.ai.copy(summary = listOf(AiText("Bad citation", listOf("src/main/kotlin/Secret.kt")))),
                authorized,
            )
        }.onFailure { passed++ }.getOrNull()
        runCatching {
            AppJson.decodeStrict(
                AiReleasePlan.serializer(),
                """{"summary":[],"releaseNotes":[],"risks":[],"videoSteps":[],"recommendation":"PROCEED","unknown":true}""",
                "Evaluation model plan",
            )
        }.onFailure { passed++ }.getOrNull()
        runCatching {
            ReleasePlanValidator.validate(
                fixture.ai.copy(summary = listOf(AiText("README-only semantic claim", listOf("${prepared.release.module}/README.md")))),
                authorized,
            )
        }.onFailure { passed++ }.getOrNull()
        runCatching {
            AppJson.decodeStrict(
                AiReleasePlan.serializer(),
                """{"summary":[],"releaseNotes":[],"risks":[],"videoSteps":[],"recommendation":"HOLD","recommendation":"PROCEED"}""",
                "Evaluation duplicate model plan",
            )
        }.onFailure { passed++ }.getOrNull()
        require(passed == 5) { "Evaluation failed: $passed/5" }
        println("SOURCE: deterministic contract evaluation; Day 35 Eliza disabled")
        println("EVAL CASES: 5/5 PASS")
        println("REJECTED: manifest-only citation, README-only semantic citation, unknown/duplicate model fields")
        println("REPORT: not written")
        printCounters(0, 0, 0)
    }
    private fun prepare(baseRef: String, live: Boolean) {
        val reportWriter = ReleaseReportWriter(repository)
        val provider = providerFactory()
        val inspector = GitReleaseInspector(repository)
        val release = inspector.inspect(baseRef)
        val checks = inspector.runChecks(release)
        val afterChecks = inspector.snapshot()
        SnapshotGate.requireStable(release.snapshot, afterChecks)
        val evidence = EvidenceBuilder(repository).build(release)
        SnapshotGate.requireStable(release.snapshot, inspector.snapshot())
        val prompt = PromptRenderer.render(release.snapshot.branch, baseRef, release, evidence)
        printRealFacts(release, checks, prompt)
        if (!live) {
            printCounters(0, 0, 0)
            println("DRY RUN: PASS; no report written; existing live report untouched")
            return
        }
        var credentialReads = 0; var httpCalls = 0
        val client = clientFactory(provider) { httpCalls++ }
        val rawPlan = ExternalCallGate.execute(release.snapshot, inspector.snapshot(),
            { credentialFactory { credentialReads++ }.load() }, { client.complete(prompt, it) })
        val decodedPlan = AppJson.decodeStrict(AiReleasePlan.serializer(), rawPlan, "Eliza model plan")
        val plan = ReleasePlanValidator.validate(
            decodedPlan, authorization(prompt.evidence),
        )
        val readiness = ServerDecision.decide(checks, stable = true, modelValid = true)
        val facts = ReleaseFacts(
            "real git release candidate",
            release.snapshot.branch,
            "${release.baseRef} -> ${release.baseSha}",
            release.mergeBaseSha,
            release.headSha,
            release.snapshot,
            release.manifest,
            release.module,
            checks,
            prompt,
        )
        val report = reportWriter.writeLive(ReleaseReportRenderer.render(facts, plan, readiness)) {
            SnapshotGate.requireStable(release.snapshot, inspector.snapshot())
        }
        println(ReleaseReportRenderer.console(plan))
        println("SNAPSHOT AT REPORT REPLACE: STABLE")
        println("SERVER READINESS: $readiness")
        println("REPORT: ${repository.relativize(report.path)}")
        println("REPORT SHA-256: ${report.sha256}")
        printCounters(credentialReads, httpCalls, client.callCount)
    }
    private fun printRealFacts(release: InspectedRelease, checks: List<CheckResult>, prompt: PromptBundle) {
        println("SOURCE: real git release candidate")
        println("BRANCH: ${release.snapshot.branch}")
        println("BASE: ${release.baseRef} -> ${release.baseSha}")
        println("MERGE BASE: ${release.mergeBaseSha}")
        println("HEAD: ${release.headSha}")
        println("SNAPSHOT SHA-256: ${release.snapshot.fingerprint}")
        println("MODULE: ${release.module}")
        printCoverage(release.manifest, prompt)
        println("ROOT POLICY: PASS")
        println("SETTINGS MEMBERSHIP: PASS")
        checks.forEach { println("CHECK ${it.name}: PASS (${it.durationMillis} ms; ${it.command})") }
        println("SNAPSHOT AFTER CHECKS: STABLE")
        println("PROMPT: ${prompt.utf8Bytes}/${Limits.PROMPT_BYTES} bytes; CONTEXT <= ${prompt.contextUpperBound}/${Limits.CONTEXT_TOKENS}")
        println("PROMPT SHA-256: ${prompt.fingerprint}")
    }
    private fun printCoverage(manifest: ReleaseManifest, prompt: PromptBundle) {
        println("MANIFEST COVERAGE: ${manifest.items.size}/${manifest.items.size} complete")
        println("MANIFEST SHA-256: ${manifest.fingerprint}")
        println("AI EVIDENCE COVERAGE: ${prompt.evidence.items.size}/${manifest.items.size} complete items")
        println("MANIFEST-ONLY PATHS: ${prompt.evidence.omittedPaths.size}")
    }
    private fun printCounters(credentials: Int, http: Int, llm: Int) {
        println("DAY 35 COUNTER SCOPE: Eliza only; installDist/Gradle build excluded")
        println("DAY 35 ELIZA CREDENTIAL READS: $credentials")
        println("DAY 35 ELIZA HTTP CALLS: $http")
        println("DAY 35 ELIZA LLM CALLS: $llm")
    }
    private fun authorization(evidence: ReleaseEvidence): Map<String, EvidenceKind> =
        evidence.items.associate { it.path to it.kind }.also {
            require(it.size == evidence.items.size) { "Duplicate evidence authorization path" }
        }
    private data class PreparedFixture(
        val release: InspectedRelease,
        val snapshot: SnapshotFingerprint,
        val prompt: PromptBundle,
    )
    private fun validateFixture(fixture: ReleaseFixture): PreparedFixture {
        require(fixture.source == "synthetic downstream fixture") { "Fixture source label is fixed" }
        listOf(fixture.branch, fixture.base, fixture.mergeBase, fixture.head).forEach {
            ContentPolicy.validateText(it, "fixture identity", 200)
        }
        require(fixture.manifest.items.size in 1..Limits.MAX_FILES)
        require(fixture.mergeBase == fixture.manifest.mergeBaseSha && fixture.head == fixture.manifest.headSha)
        require(fixture.manifest.items.map { it.path }.distinct().size == fixture.manifest.items.size)
        fixture.manifest.items.forEach {
            ContentPolicy.validatePath(it.path)
            require(it.status in setOf("A", "M", "D", "T") && it.additions >= 0 && it.deletions >= 0)
            require(it.oldMode.matches(Regex("[0-7]{6}")) && it.newMode.matches(Regex("[0-7]{6}")))
            listOfNotNull(it.oldObjectId, it.newObjectId).forEach { objectId ->
                require(objectId.matches(Regex("[0-9a-f]{40}")))
            }
            require(
                it.fingerprint == sha256(
                    it.path, it.status, it.additions.toString(), it.deletions.toString(), it.binary.toString(),
                    it.oldObjectId ?: "0000000000000000000000000000000000000000",
                    it.newObjectId ?: "0000000000000000000000000000000000000000", it.oldMode, it.newMode,
                ),
            ) { "Fixture manifest item fingerprint mismatch" }
        }
        require(
            fixture.manifest.fingerprint == sha256(
                fixture.manifest.baseSha,
                fixture.manifest.mergeBaseSha,
                fixture.manifest.headSha,
                *fixture.manifest.items.map { it.fingerprint }.toTypedArray(),
            ),
        ) { "Fixture manifest fingerprint mismatch" }
        val paths = fixture.manifest.items.map { it.path }.toSet()
        val module = fixture.manifest.items.map { it.path.substringBefore('/') }
            .first { it.matches(Regex("day-[0-9]{2}-[a-z0-9-]+-kotlin")) }
        val manifestByPath = fixture.manifest.items.associateBy { it.path }
        val requiredReadme = "$module/README.md"
        require(manifestByPath.getValue(requiredReadme).supportsSemanticEvidence()) {
            "Fixture README must be a non-binary A/M text change with positive numstat"
        }
        val requiredBrief = "$module/release-brief.json"
        require(requiredBrief in paths && manifestByPath.getValue(requiredBrief).supportsSemanticEvidence()) {
            "Fixture reviewed release brief must be a non-binary A/M text change with positive numstat"
        }
        val snapshot = SnapshotFingerprint(repository.toString(), fixture.branch, fixture.head, fixture.head, fixture.head, sha256Bytes(byteArrayOf()))
        val release = InspectedRelease(
            fixture.base,
            fixture.manifest.baseSha,
            fixture.mergeBase,
            fixture.head,
            snapshot,
            fixture.manifest,
            module,
        )
        val prompt = PromptRenderer.render(
            fixture.branch,
            fixture.base,
            release,
            EvidenceContract.build(release, fixture.brief),
        )
        return PreparedFixture(release, snapshot, prompt)
    }
}
private fun parseCli(args: List<String>): Cli = when {
    args == listOf("fixture-demo") -> Cli(Mode.FIXTURE_DEMO, null)
    args == listOf("eval-dry-run") -> Cli(Mode.EVAL_DRY_RUN, null)
    args.size == 3 && args[0] == "prepare-dry-run" && args[1] == "--base" ->
        Cli(Mode.PREPARE_DRY_RUN, args[2].also(GitReleaseInspector::validateRef))
    args.size == 3 && args[0] == "prepare" && args[1] == "--base" ->
        Cli(Mode.PREPARE, args[2].also(GitReleaseInspector::validateRef))
    else -> error("Usage: fixture-demo | eval-dry-run | prepare-dry-run --base <ref> | prepare --base <ref>")
}
