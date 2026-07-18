package ru.ai.course.day35.releaseprep
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.Duration
class FixtureLoader(private val repository: Path) {
    fun load(path: Path = repository.resolve("day-35-ai-release-prep-kotlin/fixtures/release-input.json")): ReleaseFixture {
        val text = SecureFiles.readRegularContained(repository, path, Limits.FIXTURE_REPORT_BYTES)
        ContentPolicy.validateTransportText(text, "fixture", Limits.FIXTURE_REPORT_BYTES)
        return AppJson.decodeStrict(ReleaseFixture.serializer(), text, "Fixture")
    }
}
class EvidenceBuilder(
    private val repository: Path,
    private val runner: BoundedCommandRunner = BoundedCommandRunner(repository),
) {
    fun build(release: InspectedRelease): ReleaseEvidence {
        val briefPath = "${release.module}/release-brief.json"
        val raw = runner.run(
            listOf("git", "show", "${release.headSha}:$briefPath"),
            Duration.ofMinutes(1),
            Limits.RELEASE_BRIEF_BYTES,
        ).also { require(it.exitCode == 0) { "Reviewed release brief is unavailable" } }.text()
        ContentPolicy.validateTransportText(raw, "reviewed release brief JSON", Limits.RELEASE_BRIEF_BYTES)
        val brief = AppJson.decodeStrict(ReleaseBriefDocument.serializer(), raw, "Reviewed release brief")
        return EvidenceContract.build(release, brief)
    }
}
internal object EvidenceContract {
    fun build(release: InspectedRelease, brief: ReleaseBriefDocument): ReleaseEvidence {
        val manifestByPath = release.manifest.items.associateBy { it.path }
        val readmePath = "${release.module}/README.md"
        val briefPath = "${release.module}/release-brief.json"
        val readme = manifestByPath[readmePath]
            ?: error("Required README is absent from the complete manifest")
        val briefItem = manifestByPath[briefPath]
            ?: error("Reviewed release brief is absent from the complete manifest")
        require(readme.supportsSemanticEvidence()) {
            "Required README must be a non-binary A/M text change with positive numstat"
        }
        require(briefItem.supportsSemanticEvidence()) {
            "Reviewed release brief must be a non-binary A/M text change with positive numstat"
        }
        validateBrief(brief)
        val items = listOf(
            readme.toEvidence(EvidenceKind.REQUIRED_README_CHANGE),
            briefItem.toEvidence(EvidenceKind.REVIEWED_RELEASE_BRIEF, brief),
        )
        items.forEach(::validateItem)
        val encoded = AppJson.strict.encodeToString(ListSerializer(EvidenceItem.serializer()), items)
        require(utf8Bytes(encoded) <= Limits.EVIDENCE_BYTES) {
            "Whole typed release evidence exceeds ${Limits.EVIDENCE_BYTES} bytes"
        }
        return ReleaseEvidence(
            items,
            release.manifest.items.map(ManifestItem::path).toSet().minus(items.map(EvidenceItem::path).toSet()).sorted(),
        )
    }
    fun validateBrief(brief: ReleaseBriefDocument) {
        ContentPolicy.validateText(brief.objective, "release brief objective", 1_000)
        require(ContentPolicy.canonicalForm(brief.objective).isNotBlank()) { "Release brief objective is blank" }
        validateList(brief.highlights, "release brief highlights", 1..6)
        validateList(brief.operationalNotes, "release brief operational notes", 0..5)
        validateList(brief.videoFocus, "release brief video focus", 1..5)
    }
    fun validate(evidence: ReleaseEvidence, release: InspectedRelease) {
        require(evidence.items.size == 2) { "Exactly two typed evidence items are required" }
        require(evidence.items.map { ContentPolicy.canonicalForm(it.path) }.distinct().size == evidence.items.size)
        evidence.items.forEach(::validateItem)
        val readmePath = "${release.module}/README.md"
        val briefPath = "${release.module}/release-brief.json"
        require(evidence.items[0].path == readmePath && evidence.items[0].kind == EvidenceKind.REQUIRED_README_CHANGE)
        require(evidence.items[1].path == briefPath && evidence.items[1].kind == EvidenceKind.REVIEWED_RELEASE_BRIEF)
        val manifestByPath = release.manifest.items.associateBy(ManifestItem::path)
        evidence.items.forEach { item ->
            val manifest = manifestByPath[item.path] ?: error("Evidence path is absent from manifest")
            require(manifest.supportsSemanticEvidence()) {
                "Evidence path is not a semantic A/M text change"
            }
            require(
                item.status == manifest.status &&
                    item.additions == manifest.additions &&
                    item.deletions == manifest.deletions &&
                    item.manifestFingerprint == manifest.fingerprint,
            ) { "Typed evidence facts disagree with manifest" }
        }
        require(evidence.omittedPaths == release.manifest.items.map(ManifestItem::path).toSet()
            .minus(evidence.items.map(EvidenceItem::path).toSet()).sorted()) {
            "Manifest-only path inventory is inconsistent"
        }
        val encoded = AppJson.strict.encodeToString(ListSerializer(EvidenceItem.serializer()), evidence.items)
        require(utf8Bytes(encoded) <= Limits.EVIDENCE_BYTES) {
            "Whole typed release evidence exceeds ${Limits.EVIDENCE_BYTES} bytes"
        }
    }
    private fun validateList(values: List<String>, label: String, range: IntRange) {
        require(values.size in range) { "$label count is invalid" }
        values.forEachIndexed { index, value ->
            ContentPolicy.validateText(value, "$label[$index]", 1_000)
            require(ContentPolicy.canonicalForm(value).isNotBlank()) { "$label[$index] is blank" }
        }
        require(values.map(ContentPolicy::canonicalForm).distinct().size == values.size) {
            "$label canonical uniqueness is invalid"
        }
    }
    private fun validateItem(item: EvidenceItem) {
        ContentPolicy.validatePath(item.path)
        require(item.status in setOf("A", "M") && item.additions >= 0 && item.deletions >= 0)
        require(item.additions + item.deletions > 0)
        require(item.manifestFingerprint.matches(Regex("[0-9a-f]{64}")))
        when (item.kind) {
            EvidenceKind.REQUIRED_README_CHANGE -> require(item.brief == null)
            EvidenceKind.REVIEWED_RELEASE_BRIEF -> validateBrief(requireNotNull(item.brief))
        }
    }
    private fun ManifestItem.toEvidence(kind: EvidenceKind, brief: ReleaseBriefDocument? = null) =
        EvidenceItem(path, kind, status, additions, deletions, fingerprint, brief)
}
internal fun ManifestItem.supportsSemanticEvidence(): Boolean =
    status in setOf("A", "M") && !binary && additions + deletions > 0
object PromptRenderer {
    private const val SYSTEM = """You prepare human-facing release prose from typed release evidence.
Manifest inventory is not semantic evidence. Never claim code correctness.
The REQUIRED_README_CHANGE item authorizes only its server-owned change facts.
The REVIEWED_RELEASE_BRIEF item authorizes only the explicit reviewed objective, highlights, operational notes and video focus.
Every AI-authored item must cite the REVIEWED_RELEASE_BRIEF path; REQUIRED_README_CHANGE may only be an additional citation.
Use only exact evidencePaths from whole TYPED_RELEASE_EVIDENCE_ITEMS.
Return exactly one JSON object matching the requested schema. Recommendation is advisory; the server owns readiness."""
    fun render(
        branch: String,
        baseRef: String,
        release: InspectedRelease,
        evidence: ReleaseEvidence,
    ): PromptBundle {
        listOf(branch, baseRef).forEach { ContentPolicy.validateText(it, "repository label", 200) }
        EvidenceContract.validate(evidence, release)
        evidence.omittedPaths.forEach(ContentPolicy::validatePath)
        val user = buildUser(branch, baseRef, release, evidence)
        ContentPolicy.validateTransportText(SYSTEM, "system prompt", 8 * 1024)
        ContentPolicy.validateTransportText(user, "user prompt", Limits.PROMPT_BYTES)
        val bytes = utf8Bytes(SYSTEM) + utf8Bytes(user)
        val context = bytes + Limits.FRAMING_TOKENS + Limits.OUTPUT_TOKENS
        require(bytes <= Limits.PROMPT_BYTES && context <= Limits.CONTEXT_TOKENS) {
            "Whole typed release evidence does not fit model context"
        }
        return PromptBundle(SYSTEM, user, evidence, bytes, context, sha256(SYSTEM, user))
    }
    private fun buildUser(
        branch: String,
        baseRef: String,
        release: InspectedRelease,
        evidence: ReleaseEvidence,
    ): String {
        val manifestJson = AppJson.strict.encodeToString(ReleaseManifest.serializer(), release.manifest)
        val evidenceJson = AppJson.strict.encodeToString(ListSerializer(EvidenceItem.serializer()), evidence.items)
        return """
            RELEASE_IDENTITY (authoritative server facts)
            branch=$branch
            baseRef=$baseRef
            baseSha=${release.baseSha}
            mergeBaseSha=${release.mergeBaseSha}
            headSha=${release.headSha}
            module=${release.module}
            MANIFEST_INVENTORY_ONLY
            $manifestJson
            TYPED_RELEASE_EVIDENCE_ITEMS
            $evidenceJson
            OUTPUT_SCHEMA
            {"summary":[{"text":"...","evidencePaths":["..."]}],"releaseNotes":[{"text":"...","evidencePaths":["..."]}],"risks":[{"severity":"LOW|MEDIUM|HIGH","text":"...","mitigation":"...","evidencePaths":["..."]}],"videoSteps":[{"text":"...","evidencePaths":["..."]}],"recommendation":"PROCEED|HOLD"}
        """.trimIndent()
    }
}
