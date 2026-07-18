package ru.ai.course.day35.releaseprep

import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.net.http.HttpRequest
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
            "password=",
            "password=<password>suffix",
            "Authorization: OAuth <token>suffix",
            "SECRET_KEY=<api-key> trailing",
        ).forEach { payload ->
            assertFails("Incomplete placeholder accepted: $payload") {
                ContentPolicy.validateText(payload, "invalid placeholder", 4_000)
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

internal fun unsafeReleaseTextCases(): List<String> {
    val secret = "ab\$1234567890"
    return listOf(
        "Authorization: OAuth $secret",
        "Authorization: Bearer abcdefghijklmnop1234567890",
        "password: correcthorsebattery1",
        "LLM_API_KEY=ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "access_token=123456789012345678901234567890",
        "SECRET_KEY=deadbeefdeadbeefdeadbeef",
        "token=lowercaseopaquevalue",
        "secret=01234567890123456789",
        "Proxy-Authorization: Bearer \"$secret\"",
        "LLM_API_KEY='$secret'",
        """{"password":"$secret"}""",
        """request.header("Authorization", "OAuth $secret")""",
        """request.header<Map<String, List<Int>>>("Authorization", "Bearer $secret")""",
        """request.setRequestProperty<List<Map<String, Int>>>(HttpHeaders.Authorization, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")""",
        """headers.put<Map<String, List<Int>>>(Env.LLM_API_KEY, "123456789012345678901234")""",
        """headers.set<List<Map<String, Int>>>(Env.LLM_API_KEY, "deadbeefdeadbeefdeadbeef")""",
        """headers.add<Map<String, List<Int>>>(Env.LLM_API_KEY, "correcthorsebattery1")""",
        """client.transport.headers.header(HttpHeaders.Authorization, "OAuth $secret")""",
        """with(headers) { header(HttpHeaders.Authorization, "Bearer $secret") }""",
        """audit(request.header(HttpHeaders.Authorization, "OAuth $secret"))""",
        """headers.set((Env.LLM_API_KEY as String), "$secret")""",
        """request?.header?.invoke(HttpHeaders.Authorization, "Bearer $secret")""",
        """request.header(HttpHeaders.Authorization.lowercase(), "Bearer $secret")""",
        """request.header(HttpHeaders.Authorization.trimEnd(), "Bearer $secret")""",
        """request.header(HttpHeaders.Authorization.replace("x", "x"), "Bearer $secret")""",
        """request.header(HttpHeaders.Authorization.substring(0), "Bearer $secret")""",
        """headers.put(Env.get("LLM_API_KEY"), "$secret")""",
        """Authoriz\uZZZZation: OAuth $secret""",
        """Authoriz\uZation: OAuth $secret""",
        """Authoriz\uZZZZation: OAuth abcdefghijklmnopqrstuvwxyz0123456789""",
        """Authoriz\uZation: OAuth abcdefghijklmnopqrstuvwxyz0123456789""",
        """Authoriz\uZZZZation=lowercaseopaquevalue""",
        """Authoriz\uZation=lowercaseopaquevalue""",
        """Authoriz\uation=lowercaseopaquevalue""",
        """Authoriz\UZZZZZZZZation: OAuth $secret""",
        """Authoriz\UZZation: OAuth $secret""",
        """Authoriz\UZZZZZZZZation: OAuth ABCDEFGHIJKLMNOPQRSTUVWXYZ""",
        """Authoriz\UZZation: OAuth 123456789012345678901234""",
        """Authoriz\UZZZZZZZZation=ABCDEF0123456789""",
        """Authoriz\UZZation=ABCDEF0123456789""",
        """Authoriz\Uation=ABCDEF0123456789""",
        """Authoriz\xQZation: OAuth $secret""",
        """Authoriz\xZation: OAuth $secret""",
        """Authoriz\xQZation: OAuth deadbeefdeadbeefdeadbeef""",
        """Authoriz\xZation: OAuth correcthorsebattery1""",
        """Authoriz\xQZation=deadbeefdeadbeef""",
        """Authoriz\xZation=deadbeefdeadbeef""",
        """Authoriz\xation=deadbeefdeadbeef""",
        """headers.put("Authoriz\uZation", "lowercaseopaquevalue")""",
        """headers.put("Authoriz\uation", "lowercaseopaquevalue")""",
        """headers.set("Authoriz\UZZation", "ABCDEF0123456789")""",
        """headers.add("Authoriz\xZation", "deadbeefdeadbeef")""",
        """request.setRequestProperty("Authoriz\uZation", "01234567890123456789")""",
        """Authoriz\u0061tion: OAuth $secret""",
        """Authorization\u000A: OAuth $secret""",
        """Authorization\x0A: OAuth $secret""",
        """Authorization\012: OAuth $secret""",
        """LLM_API_KEY=\\u0061\\u0062\\u00241234567890""",
        "\u200B",
        "safe\u202Etext",
        "Ａ",
    )
}

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
    "X-Authorization: Basic YOUR_TOKEN",
    "LLM_API_KEY=\"replace-with-your-Eliza-OAuth-token\"",
    "SECRET_KEY=<api-key>",
    "PRIVATE_KEY='<api-key>'",
    "password=<password>",
    """request.header<Map<String, List<Int>>>("Authorization", "Bearer <token>")""",
    """request?.header?.invoke(HttpHeaders.Authorization, "Bearer <token>")""",
    """headers.put(Env.LLM_API_KEY.trim(), "<api-key>")""",
)
