package ru.ai.course.day35.releaseprep

import kotlinx.serialization.encodeToString
import ru.ai.course.day32.codereview.CloudContentPolicy
import java.io.ByteArrayInputStream
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderAndContractTest {
    private val readme = "$module/README.md"
    private val briefPath = "$module/release-brief.json"

    @Test
    fun manifestOnlyOrOmittedEvidencePathCannotBeCited() {
        val valid = FixturePlan.generate(briefPath)
        val authorized = authorization()
        ReleasePlanValidator.validate(valid, authorized)
        listOf(
            "$module/src/main/kotlin/Main.kt",
            "$module/build.gradle.kts",
        ).forEach { forbidden ->
            assertFails {
                ReleasePlanValidator.validate(
                    valid.copy(summary = listOf(AiText("Claim", listOf(forbidden)))),
                    authorized,
                )
            }
        }
        listOf(
            valid.copy(summary = listOf(AiText("README-only summary", listOf(readme)))),
            valid.copy(releaseNotes = listOf(AiText("README-only release note", listOf(readme)))),
            valid.copy(risks = listOf(AiRisk("LOW", "README-only risk", "README-only mitigation", listOf(readme)))),
            valid.copy(videoSteps = listOf(AiText("README-only video step", listOf(readme)))),
        ).forEach { assertFails { ReleasePlanValidator.validate(it, authorized) } }
        ReleasePlanValidator.validate(
            valid.copy(summary = listOf(AiText("Mixed cited claim", listOf(readme, briefPath)))),
            authorized,
        )
    }

    @Test
    fun typedEvidenceContainsNoRepositoryPatchAndFitsOnlyAsWholeItems() {
        val paths = buildList {
            add(readme)
            add(briefPath)
            add("$module/build.gradle.kts")
            repeat(77) { add("$module/src/main/kotlin/File${it.toString().padStart(2, '0')}.kt") }
        }
        val release = release(paths)
        val brief = safeBrief("Reviewed release objective.")
        val evidence = EvidenceContract.build(release, brief)
        val prompt = PromptRenderer.render("AICOURSE-5", "origin/main", release, evidence)

        assertEquals(listOf(readme, briefPath), prompt.evidence.items.map(EvidenceItem::path))
        assertEquals(paths.drop(2).toSet(), prompt.evidence.omittedPaths.toSet())
        assertTrue("TYPED_RELEASE_EVIDENCE_ITEMS" in prompt.user)
        assertTrue("Reviewed release objective." in prompt.user)
        assertFalse("diff --git" in prompt.user)
        assertFalse("\"patch\"" in prompt.user)
        assertEquals(
            prompt.utf8Bytes + Limits.FRAMING_TOKENS + Limits.OUTPUT_TOKENS,
            prompt.contextUpperBound,
        )
        assertTrue(prompt.utf8Bytes <= Limits.PROMPT_BYTES)

        val oversized = ReleaseBriefDocument(
            objective = "o".repeat(1_000),
            highlights = List(6) { "h$it-${"x".repeat(995)}" },
            operationalNotes = List(5) { "n$it-${"x".repeat(995)}" },
            videoFocus = List(5) { "v$it-${"x".repeat(995)}" },
        )
        assertFails { EvidenceContract.build(release, oversized) }
    }

    @Test
    fun uriVariantsAndModelOverrideFailBeforeCredentialRead() {
        val urlOnly = RecordingEnvironment(mapOf("LLM_API_URL" to "${FixedProvider.URL}/"))
        assertFails { FixedProvider.preflight(urlOnly) }
        assertEquals(listOf("LLM_API_URL"), urlOnly.reads)
        val model = RecordingEnvironment(
            mapOf("LLM_API_URL" to FixedProvider.URL, "LLM_MODEL" to "other/model"),
        )
        assertFails { FixedProvider.preflight(model) }
        assertEquals(listOf("LLM_API_URL", "LLM_MODEL"), model.reads)
        assertTrue("LLM_API_KEY" !in urlOnly.reads + model.reads)
    }

    @Test
    fun invalidDestinationReadsNoCredentialAndMakesNoHttpCall() {
        var credentials = 0
        var http = 0
        val source = CredentialSource { credentials++; "fixture-credential" }
        assertFails {
            FixedProvider.preflight(RecordingEnvironment(mapOf("LLM_AUTH_SCHEME" to "Bearer")))
            source.load()
            http++
        }
        assertEquals(0, credentials)
        assertEquals(0, http)
    }

    @Test
    fun redirectTimeoutResponseCapAndOneChoiceEnvelopeFailClosed() {
        val prompt = tinyPrompt()
        val provider = FixedProvider.preflight(RecordingEnvironment(emptyMap()))
        assertEquals(java.net.http.HttpClient.Redirect.NEVER, JavaHttpExecutor().client.followRedirects())
        assertFails { ElizaClient(provider, executor(302, "{}")).complete(prompt, "fixture-credential") }
        assertFails {
            ElizaClient(provider, HttpExecutor {
                RawHttpResponse(200, ByteArrayInputStream(ByteArray(Limits.HTTP_RESPONSE_BYTES + 1)))
            }).complete(prompt, "fixture-credential")
        }
        assertFails { ElizaClient(provider, executor(200, """{"choices":[]}""")).complete(prompt, "fixture-credential") }
        assertFails {
            ElizaClient(
                provider,
                executor(200, """{"choices":[{"message":{"content":"{}"}},{"message":{"content":"{}"}}]}"""),
            ).complete(prompt, "fixture-credential")
        }
        var captured: HttpRequest? = null
        val result = ElizaClient(provider, HttpExecutor {
            captured = it
            RawHttpResponse(
                200,
                ByteArrayInputStream("""{"choices":[{"message":{"content":"{}"}}]}""".toByteArray()),
            )
        }).complete(prompt, "fixture-credential")
        assertEquals("{}", result)
        assertEquals(90, captured!!.timeout().orElseThrow().seconds)
    }

    @Test
    fun sharedUnsafeAndBenignMatrixCoversPolicyTypedBriefAndCompletePrompt() {
        val release = release(listOf(readme, briefPath, "$module/build.gradle.kts"))
        unsafeReleaseTextCases().forEach { payload ->
            assertFails("Direct policy accepted: $payload") {
                ContentPolicy.validateText(payload, "reviewed release text", 4_000)
            }
            val unsafeEvidence = evidence(release, safeBrief(payload))
            assertFails("Prompt accepted unsafe typed brief: $payload") {
                PromptRenderer.render("AICOURSE-5", "origin/main", release, unsafeEvidence)
            }
        }
        benignReleaseTextCases().forEach { payload ->
            ContentPolicy.validateText(payload, "benign release text", 4_000)
            val prompt = PromptRenderer.render(
                "AICOURSE-5",
                "origin/main",
                release,
                EvidenceContract.build(release, safeBrief(payload)),
            )
            assertTrue(AppJson.strict.encodeToString(payload) in prompt.user)
        }
        exactPlaceholderCases().forEach { payload ->
            ContentPolicy.validateText(payload, "exact placeholder", 4_000)
            PromptRenderer.render(
                "AICOURSE-5",
                "origin/main",
                release,
                EvidenceContract.build(release, safeBrief(payload)),
            )
        }
        listOf(
            reviewSafeFixture("cGFzc3dvcmQ9"),
            reviewSafeFixture("cGFzc3dvcmQ9PHBhc3N3b3JkPnN1ZmZpeA=="),
            reviewSafeFixture("QXV0aG9yaXphdGlvbjogT0F1dGggPHRva2VuPnN1ZmZpeA=="),
            reviewSafeFixture("U0VDUkVUX0tFWT08YXBpLWtleT4gdHJhaWxpbmc="),
        ).forEach { payload ->
            assertFails("Incomplete placeholder accepted: $payload") {
                ContentPolicy.validateText(payload, "invalid placeholder", 4_000)
            }
        }
    }

    @Test
    fun day35RawCandidateFilesRemainSafeForRepositoryCloudReview() {
        val moduleRoot = courseRoot().resolve(module)
        val reviewedRoots = setOf(
            ".env.example",
            "README.md",
            "build.gradle.kts",
            "fixtures",
            "release-brief.json",
            "scripts",
            "src",
        )
        val policy = CloudContentPolicy()

        Files.walk(moduleRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val relative = moduleRoot.relativize(path).joinToString("/") { it.toString() }
                if (relative.substringBefore('/') !in reviewedRoots) return@forEach
                val candidatePath = "$module/$relative"
                val content = path.toFile().readText()
                policy.requireSafePath(candidatePath)
                policy.requireSafeContent(content)
                policy.requireSafeContent(content.lineSequence().joinToString("\n") { "+$it" })
            }
        }
    }

    @Test
    fun sharedMatrixCoversRawProviderAndEveryModelControlledProseAndPathSink() {
        val provider = FixedProvider.preflight(RecordingEnvironment(emptyMap()))
        val valid = FixturePlan.generate(briefPath)
        val authorized = authorization()
        val reportFacts = reportFacts()

        unsafeReleaseTextCases().forEach { payload ->
            val body = providerBody(payload)
            assertFails("Raw provider content accepted: $payload") {
                ElizaClient(provider, executor(200, body)).complete(tinyPrompt(), "fixture-credential")
            }
            modelVariants(valid, payload).forEach { plan ->
                var renderSinks = 0
                assertFails("Model sink accepted: $payload") {
                    val validated = ReleasePlanValidator.validate(
                        plan,
                        authorized + (payload to EvidenceKind.REVIEWED_RELEASE_BRIEF),
                    )
                    ReleaseReportRenderer.console(validated)
                    ReleaseReportRenderer.render(reportFacts, validated, "READY_FOR_HUMAN_REVIEW")
                    renderSinks++
                }
                assertEquals(0, renderSinks)
            }
        }

        benignReleaseTextCases().forEach { payload ->
            assertEquals(
                payload,
                ElizaClient(provider, executor(200, providerBody(payload)))
                    .complete(tinyPrompt(), "fixture-credential"),
            )
            modelVariants(valid, payload).forEach { plan ->
                val validated = ReleasePlanValidator.validate(
                    plan,
                    authorized + (payload to EvidenceKind.REVIEWED_RELEASE_BRIEF),
                )
                assertTrue(ReleaseReportRenderer.console(validated).isNotBlank())
                assertTrue(
                    ReleaseReportRenderer.render(reportFacts, validated, "READY_FOR_HUMAN_REVIEW").isNotBlank(),
                )
            }
        }

        exactPlaceholderCases().forEach { payload ->
            assertEquals(
                payload,
                ElizaClient(provider, executor(200, providerBody(payload)))
                    .complete(tinyPrompt(), "fixture-credential"),
            )
            modelVariants(valid, payload).forEach { plan ->
                val validated = ReleasePlanValidator.validate(
                    plan,
                    authorized + (payload to EvidenceKind.REVIEWED_RELEASE_BRIEF),
                )
                assertTrue(ReleaseReportRenderer.console(validated).isNotBlank())
                assertTrue(
                    ReleaseReportRenderer.render(reportFacts, validated, "READY_FOR_HUMAN_REVIEW").isNotBlank(),
                )
            }
        }
    }

    @Test
    fun malformedControlAndUnknownEnumsRemainRejected() {
        val valid = FixturePlan.generate(briefPath)
        val authorized = authorization()
        assertFails {
            ReleasePlanValidator.validate(
                valid.copy(summary = listOf(AiText("bad\u0085control", listOf(briefPath)))),
                authorized,
            )
        }
        assertFails {
            ReleasePlanValidator.validate(
                valid.copy(risks = listOf(AiRisk("CRITICAL", "safe", "safe", listOf(briefPath)))),
                authorized,
            )
        }
        assertFails { ReleasePlanValidator.validate(valid.copy(recommendation = "PUBLISH"), authorized) }
    }

    @Test
    fun duplicateJsonKeysAndCanonicalDuplicatesFailBeforeTypedOrRenderSinks() {
        val validBrief =
            """{"objective":"one","highlights":["h"],"operationalNotes":[],"videoFocus":["v"]}"""
        AppJson.decodeStrict(ReleaseBriefDocument.serializer(), validBrief, "brief")
        listOf(
            """{"objective":"one","objective":"two","highlights":["h"],"operationalNotes":[],"videoFocus":["v"]}""",
            """{"objective":"one","\u006fbjective":"two","highlights":["h"],"operationalNotes":[],"videoFocus":["v"]}""",
            """{"objective":"one","highlights":["h"],"operationalNotes":[],"videoFocus":["v"],"extra":{"x":1,"x":2}}""",
        ).forEach { duplicate ->
            assertFails { AppJson.decodeStrict(ReleaseBriefDocument.serializer(), duplicate, "brief") }
        }
        listOf(
            """{"summary":[],"releaseNotes":[],"risks":[],"videoSteps":[],"recommendation":"HOLD","recommendation":"PROCEED"}""",
            """{"summary":[{"text":"one","text":"two","evidencePaths":["$briefPath"]}],"releaseNotes":[],"risks":[],"videoSteps":[],"recommendation":"PROCEED"}""",
        ).forEach { duplicate ->
            val returned = ElizaClient(
                FixedProvider.preflight(RecordingEnvironment(emptyMap())),
                executor(200, providerBody(duplicate)),
            ).complete(tinyPrompt(), "fixture-credential")
            assertFails { AppJson.decodeStrict(AiReleasePlan.serializer(), returned, "model plan") }
        }
        listOf(
            """{"choices":[],"choices":[{"message":{"content":"{}"}}]}""",
            """{"choices":[{"message":{"content":"{}","content":"{}"}}]}""",
        ).forEach { duplicateEnvelope ->
            assertFails {
                ElizaClient(
                    FixedProvider.preflight(RecordingEnvironment(emptyMap())),
                    executor(200, duplicateEnvelope),
                ).complete(tinyPrompt(), "fixture-credential")
            }
        }

        val valid = FixturePlan.generate(briefPath)
        val canonicalDuplicate = valid.copy(
            summary = listOf(
                AiText("A", listOf(briefPath)),
                AiText("Ａ", listOf(briefPath)),
            ),
        )
        var renderSinks = 0
        assertFails {
            val plan = ReleasePlanValidator.validate(canonicalDuplicate, authorization())
            ReleaseReportRenderer.console(plan)
            renderSinks++
        }
        assertEquals(0, renderSinks)
        assertFails {
            EvidenceContract.build(
                release(listOf(readme, briefPath)),
                safeBrief("safe").copy(highlights = listOf("Alpha", "Ａlpha")),
            )
        }
    }

    private fun safeBrief(value: String) = ReleaseBriefDocument(
        objective = value,
        highlights = listOf("Complete manifest and deterministic checks."),
        operationalNotes = listOf("Trusted local release branch only."),
        videoFocus = listOf("Show server-owned readiness."),
    )

    private fun modelVariants(valid: AiReleasePlan, payload: String): List<AiReleasePlan> = listOf(
        valid.copy(summary = listOf(AiText(payload, listOf(briefPath)))),
        valid.copy(releaseNotes = listOf(AiText(payload, listOf(briefPath)))),
        valid.copy(risks = listOf(AiRisk("LOW", payload, "safe", listOf(briefPath)))),
        valid.copy(risks = listOf(AiRisk("LOW", "safe", payload, listOf(briefPath)))),
        valid.copy(videoSteps = listOf(AiText(payload, listOf(briefPath)))),
        valid.copy(summary = listOf(AiText("safe", listOf(payload)))),
    )

    private fun reportFacts(): ReleaseFacts {
        val release = release(listOf(readme, briefPath))
        val prompt = PromptRenderer.render(
            "AICOURSE-5",
            "origin/main",
            release,
            EvidenceContract.build(release, safeBrief("Reviewed release objective.")),
        )
        return ReleaseFacts(
            "test",
            release.snapshot.branch,
            release.baseRef,
            release.mergeBaseSha,
            release.headSha,
            release.snapshot,
            release.manifest,
            release.module,
            listOf(CheckResult("test", "test", 0, 0)),
            prompt,
        )
    }

    private fun authorization(): Map<String, EvidenceKind> = mapOf(
        readme to EvidenceKind.REQUIRED_README_CHANGE,
        briefPath to EvidenceKind.REVIEWED_RELEASE_BRIEF,
    )

    private fun courseRoot(): Path = Path.of("").toRealPath().let {
        if (Files.isRegularFile(it.resolve("fixtures/release-input.json"))) it.parent else it
    }

    private fun evidence(release: InspectedRelease, brief: ReleaseBriefDocument): ReleaseEvidence {
        val manifest = release.manifest.items.associateBy(ManifestItem::path)
        fun item(path: String, kind: EvidenceKind, document: ReleaseBriefDocument? = null): EvidenceItem {
            val source = manifest.getValue(path)
            return EvidenceItem(
                source.path,
                kind,
                source.status,
                source.additions,
                source.deletions,
                source.fingerprint,
                document,
            )
        }
        return ReleaseEvidence(
            listOf(
                item(readme, EvidenceKind.REQUIRED_README_CHANGE),
                item(briefPath, EvidenceKind.REVIEWED_RELEASE_BRIEF, brief),
            ),
            release.manifest.items.map(ManifestItem::path).toSet().minus(setOf(readme, briefPath)).sorted(),
        )
    }

    private fun release(paths: List<String>): InspectedRelease {
        val items = paths.map(::item)
        val base = "1".repeat(40)
        val merge = "2".repeat(40)
        val head = "3".repeat(40)
        val manifest = ReleaseManifest(
            base,
            merge,
            head,
            items,
            sha256(base, merge, head, *items.map(ManifestItem::fingerprint).toTypedArray()),
        )
        return InspectedRelease(
            "origin/main",
            base,
            merge,
            head,
            SnapshotFingerprint("/repo", "AICOURSE-5", head, "4".repeat(40), "4".repeat(40), sha256("")),
            manifest,
            module,
        )
    }

    private fun item(path: String): ManifestItem {
        val old = "1".repeat(40)
        val new = "2".repeat(40)
        return ManifestItem(
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
    }

    private fun tinyPrompt() = PromptBundle(
        "system",
        "user",
        ReleaseEvidence(emptyList(), emptyList()),
        10,
        6_154,
        sha256("prompt"),
    )

    private fun providerBody(content: String): String =
        """{"choices":[{"message":{"content":${AppJson.strict.encodeToString(content)}}}]}"""

    private fun executor(status: Int, body: String) =
        HttpExecutor { RawHttpResponse(status, ByteArrayInputStream(body.toByteArray())) }

    private class RecordingEnvironment(private val values: Map<String, String>) : NamedEnvironment {
        val reads = mutableListOf<String>()
        override fun get(name: String): String? = values[name].also { reads += name }
    }

    private companion object {
        const val module = "day-35-ai-release-prep-kotlin"
    }
}

internal fun unsafeReleaseTextCases(): List<String> =
    encodedUnsafeReleaseTextCases.lineSequence().filter(String::isNotBlank).map(::reviewSafeFixture).toList()

// Keep raw PR patch/blob reviewable; local tests decode the rejection values before exercising Day 35.
private val encodedUnsafeReleaseTextCases = """
    QXV0aG9yaXphdGlvbjogT0F1dGggYWIkMTIzNDU2Nzg5MA==
    QXV0aG9yaXphdGlvbjogQmVhcmVyIGFiY2RlZmdoaWprbG1ub3AxMjM0NTY3ODkw
    cGFzc3dvcmQ6IGNvcnJlY3Rob3JzZWJhdHRlcnkx
    TExNX0FQSV9LRVk9QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=
    YWNjZXNzX3Rva2VuPTEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MA==
    U0VDUkVUX0tFWT1kZWFkYmVlZmRlYWRiZWVmZGVhZGJlZWY=
    dG9rZW49bG93ZXJjYXNlb3BhcXVldmFsdWU=
    c2VjcmV0PTAxMjM0NTY3ODkwMTIzNDU2Nzg5
    UHJveHktQXV0aG9yaXphdGlvbjogQmVhcmVyICJhYiQxMjM0NTY3ODkwIg==
    TExNX0FQSV9LRVk9J2FiJDEyMzQ1Njc4OTAn
    eyJwYXNzd29yZCI6ImFiJDEyMzQ1Njc4OTAifQ==
    cmVxdWVzdC5oZWFkZXIoIkF1dGhvcml6YXRpb24iLCAiT0F1dGggYWIkMTIzNDU2Nzg5MCIp
    cmVxdWVzdC5oZWFkZXI8TWFwPFN0cmluZywgTGlzdDxJbnQ+Pj4oIkF1dGhvcml6YXRpb24iLCAiQmVhcmVyIGFiJDEyMzQ1Njc4OTAiKQ==
    cmVxdWVzdC5zZXRSZXF1ZXN0UHJvcGVydHk8TGlzdDxNYXA8U3RyaW5nLCBJbnQ+Pj4oSHR0cEhlYWRlcnMuQXV0aG9yaXphdGlvbiwgIkFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaIik=
    aGVhZGVycy5wdXQ8TWFwPFN0cmluZywgTGlzdDxJbnQ+Pj4oRW52LkxMTV9BUElfS0VZLCAiMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0Iik=
    aGVhZGVycy5zZXQ8TGlzdDxNYXA8U3RyaW5nLCBJbnQ+Pj4oRW52LkxMTV9BUElfS0VZLCAiZGVhZGJlZWZkZWFkYmVlZmRlYWRiZWVmIik=
    aGVhZGVycy5hZGQ8TWFwPFN0cmluZywgTGlzdDxJbnQ+Pj4oRW52LkxMTV9BUElfS0VZLCAiY29ycmVjdGhvcnNlYmF0dGVyeTEiKQ==
    Y2xpZW50LnRyYW5zcG9ydC5oZWFkZXJzLmhlYWRlcihIdHRwSGVhZGVycy5BdXRob3JpemF0aW9uLCAiT0F1dGggYWIkMTIzNDU2Nzg5MCIp
    d2l0aChoZWFkZXJzKSB7IGhlYWRlcihIdHRwSGVhZGVycy5BdXRob3JpemF0aW9uLCAiQmVhcmVyIGFiJDEyMzQ1Njc4OTAiKSB9
    YXVkaXQocmVxdWVzdC5oZWFkZXIoSHR0cEhlYWRlcnMuQXV0aG9yaXphdGlvbiwgIk9BdXRoIGFiJDEyMzQ1Njc4OTAiKSk=
    aGVhZGVycy5zZXQoKEVudi5MTE1fQVBJX0tFWSBhcyBTdHJpbmcpLCAiYWIkMTIzNDU2Nzg5MCIp
    cmVxdWVzdD8uaGVhZGVyPy5pbnZva2UoSHR0cEhlYWRlcnMuQXV0aG9yaXphdGlvbiwgIkJlYXJlciBhYiQxMjM0NTY3ODkwIik=
    cmVxdWVzdC5oZWFkZXIoSHR0cEhlYWRlcnMuQXV0aG9yaXphdGlvbi5sb3dlcmNhc2UoKSwgIkJlYXJlciBhYiQxMjM0NTY3ODkwIik=
    cmVxdWVzdC5oZWFkZXIoSHR0cEhlYWRlcnMuQXV0aG9yaXphdGlvbi50cmltRW5kKCksICJCZWFyZXIgYWIkMTIzNDU2Nzg5MCIp
    cmVxdWVzdC5oZWFkZXIoSHR0cEhlYWRlcnMuQXV0aG9yaXphdGlvbi5yZXBsYWNlKCJ4IiwgIngiKSwgIkJlYXJlciBhYiQxMjM0NTY3ODkwIik=
    cmVxdWVzdC5oZWFkZXIoSHR0cEhlYWRlcnMuQXV0aG9yaXphdGlvbi5zdWJzdHJpbmcoMCksICJCZWFyZXIgYWIkMTIzNDU2Nzg5MCIp
    aGVhZGVycy5wdXQoRW52LmdldCgiTExNX0FQSV9LRVkiKSwgImFiJDEyMzQ1Njc4OTAiKQ==
    QXV0aG9yaXpcdVpaWlphdGlvbjogT0F1dGggYWIkMTIzNDU2Nzg5MA==
    QXV0aG9yaXpcdVphdGlvbjogT0F1dGggYWIkMTIzNDU2Nzg5MA==
    QXV0aG9yaXpcdVpaWlphdGlvbjogT0F1dGggYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU2Nzg5
    QXV0aG9yaXpcdVphdGlvbjogT0F1dGggYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU2Nzg5
    QXV0aG9yaXpcdVpaWlphdGlvbj1sb3dlcmNhc2VvcGFxdWV2YWx1ZQ==
    QXV0aG9yaXpcdVphdGlvbj1sb3dlcmNhc2VvcGFxdWV2YWx1ZQ==
    QXV0aG9yaXpcdWF0aW9uPWxvd2VyY2FzZW9wYXF1ZXZhbHVl
    QXV0aG9yaXpcVVpaWlpaWlpaYXRpb246IE9BdXRoIGFiJDEyMzQ1Njc4OTA=
    QXV0aG9yaXpcVVpaYXRpb246IE9BdXRoIGFiJDEyMzQ1Njc4OTA=
    QXV0aG9yaXpcVVpaWlpaWlpaYXRpb246IE9BdXRoIEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFla
    QXV0aG9yaXpcVVpaYXRpb246IE9BdXRoIDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNA==
    QXV0aG9yaXpcVVpaWlpaWlpaYXRpb249QUJDREVGMDEyMzQ1Njc4OQ==
    QXV0aG9yaXpcVVpaYXRpb249QUJDREVGMDEyMzQ1Njc4OQ==
    QXV0aG9yaXpcVWF0aW9uPUFCQ0RFRjAxMjM0NTY3ODk=
    QXV0aG9yaXpceFFaYXRpb246IE9BdXRoIGFiJDEyMzQ1Njc4OTA=
    QXV0aG9yaXpceFphdGlvbjogT0F1dGggYWIkMTIzNDU2Nzg5MA==
    QXV0aG9yaXpceFFaYXRpb246IE9BdXRoIGRlYWRiZWVmZGVhZGJlZWZkZWFkYmVlZg==
    QXV0aG9yaXpceFphdGlvbjogT0F1dGggY29ycmVjdGhvcnNlYmF0dGVyeTE=
    QXV0aG9yaXpceFFaYXRpb249ZGVhZGJlZWZkZWFkYmVlZg==
    QXV0aG9yaXpceFphdGlvbj1kZWFkYmVlZmRlYWRiZWVm
    QXV0aG9yaXpceGF0aW9uPWRlYWRiZWVmZGVhZGJlZWY=
    aGVhZGVycy5wdXQoIkF1dGhvcml6XHVaYXRpb24iLCAibG93ZXJjYXNlb3BhcXVldmFsdWUiKQ==
    aGVhZGVycy5wdXQoIkF1dGhvcml6XHVhdGlvbiIsICJsb3dlcmNhc2VvcGFxdWV2YWx1ZSIp
    aGVhZGVycy5zZXQoIkF1dGhvcml6XFVaWmF0aW9uIiwgIkFCQ0RFRjAxMjM0NTY3ODkiKQ==
    aGVhZGVycy5hZGQoIkF1dGhvcml6XHhaYXRpb24iLCAiZGVhZGJlZWZkZWFkYmVlZiIp
    cmVxdWVzdC5zZXRSZXF1ZXN0UHJvcGVydHkoIkF1dGhvcml6XHVaYXRpb24iLCAiMDEyMzQ1Njc4OTAxMjM0NTY3ODkiKQ==
    QXV0aG9yaXpcdTAwNjF0aW9uOiBPQXV0aCBhYiQxMjM0NTY3ODkw
    QXV0aG9yaXphdGlvblx1MDAwQTogT0F1dGggYWIkMTIzNDU2Nzg5MA==
    QXV0aG9yaXphdGlvblx4MEE6IE9BdXRoIGFiJDEyMzQ1Njc4OTA=
    QXV0aG9yaXphdGlvblwwMTI6IE9BdXRoIGFiJDEyMzQ1Njc4OTA=
    TExNX0FQSV9LRVk9XFx1MDA2MVxcdTAwNjJcXHUwMDI0MTIzNDU2Nzg5MA==
    4oCL
    c2FmZeKArnRleHQ=
    77yh
""".trimIndent()

internal fun benignReleaseTextCases(): List<String> = listOf(
    """C:\build\output""",
    """C:\new\file""",
    """\\server\share\build""",
    "The README explains that Authorization headers may use Bearer authentication; no credential value is included.",
    "Configuration refers to Env.LLM_API_KEY and Env.LLM_MODEL without including their values.",
    "AuthorizationUtils.CONTENT_TYPE is a benign identifier.",
    "AuthorizationUser.VALUE is a benign identifier.",
    """headers.add(AuthorizationUtils.CONTENT_TYPE, "application/json")""",
    """lookup(AuthorizationUser.VALUE)""",
    "Supported headers (Authorization, Content-Type).",
    "Configuration keys (LLM_API_KEY, TIMEOUT).",
)

internal fun exactPlaceholderCases(): List<String> = listOf(
    "Authorization: OAuth <token>",
    "Proxy-Authorization: Bearer <token>",
    reviewSafeFixture("WC1BdXRob3JpemF0aW9uOiBCYXNpYyBZT1VSX1RPS0VO"),
    "LLM_API_KEY=\"replace-with-oauth-token\"",
    "SECRET_KEY=<api-key>",
    "PRIVATE_KEY='<api-key>'",
    "password=<password>",
    """request.header<Map<String, List<Int>>>("Authorization", "Bearer <token>")""",
    """request?.header?.invoke(HttpHeaders.Authorization, "Bearer <token>")""",
    """headers.put(Env.LLM_API_KEY.trim(), "<api-key>")""",
)

internal fun reviewSafeFixture(base64: String): String =
    Base64.getDecoder().decode(base64).toString(Charsets.UTF_8)
