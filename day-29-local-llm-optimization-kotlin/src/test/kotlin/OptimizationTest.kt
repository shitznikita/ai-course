import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OptimizationTest {
    @Test
    fun `validates exact Day 21 contract and rejects external Ollama`() {
        val index = fixtureIndex()
        val path = Files.createTempFile("day29-index", ".json")
        Files.writeString(path, AppJson.compact.encodeToString(index))
        assertEquals("structured-a", Week6IndexReader(config(mapOf("WEEK6_INDEX_FILE" to path.toString()))).load().chunks.single().metadata.chunkId)
        assertFailsWith<ConfigurationException> { config(mapOf("OLLAMA_BASE_URL" to "https://example.com")) }
        assertFailsWith<IndexValidationException> { Week6IndexValidator.validate(index.copy(embeddingBackend = "hash"), "nomic-embed-text") }
    }

    @Test
    fun `retrieval runs once and every profile gets identical retrieved context`() {
        val index = fixtureIndex()
        val embeddings = FakeEmbeddings()
        val generator = FakeGenerator(answerJson())
        val profiles = config().profiles
        val service = OptimizationService(LocalRetriever(embeddings, config()), generator, FakeResources())
        val question = BenchmarkQuestion("q", "How do I run the demo?", emptyList(), emptyList())
        val prepared = service.prepare(index, question, profiles)
        profiles.forEach { service.run(it, prepared, question) }

        assertEquals(1, embeddings.calls)
        assertEquals(3, generator.calls)
        val userContexts = generator.messages.values.map { messages -> messages.last().content }.toSet()
        assertEquals(1, userContexts.size)
        assertTrue(prepared.retrieval.context.contains("structured-a"))
    }

    @Test
    fun `profiles change only documented generation settings`() {
        val profiles = config().profiles.associateBy { it.id }
        val baseline = OllamaPayloads.chat(profiles.getValue("baseline-q4"), listOf(PromptMessage("user", "q")), "5m")
        val optimized = OllamaPayloads.chat(profiles.getValue("optimized-q4"), listOf(PromptMessage("user", "q")), "5m")
        val q8 = OllamaPayloads.chat(profiles.getValue("optimized-q8"), listOf(PromptMessage("user", "q")), "5m")
        fun option(body: kotlinx.serialization.json.JsonObject, name: String) =
            (body["options"]!!.jsonObject[name] as JsonPrimitive).content

        assertEquals("0.6", option(baseline, "temperature"))
        assertEquals("512", option(baseline, "num_predict"))
        assertEquals("32768", option(baseline, "num_ctx"))
        assertEquals("0.0", option(optimized, "temperature"))
        assertEquals("220", option(optimized, "num_predict"))
        assertEquals("8192", option(optimized, "num_ctx"))
        assertEquals(option(optimized, "temperature"), option(q8, "temperature"))
        assertEquals(option(optimized, "num_predict"), option(q8, "num_predict"))
        assertEquals("qwen3:14b", profiles.getValue("optimized-q4").model)
        assertEquals("qwen3:14b-q8_0", profiles.getValue("optimized-q8").model)
    }

    @Test
    fun `grounding aggregate and Q8 recommendation are deterministic`() {
        val index = fixtureIndex()
        val retrieval = RetrievalPackage("q", listOf(SearchResult(1.0, index.chunks.single())), "fixture", 1)
        val valid = GroundedAnswerParser.parse(answerJson())
        val validation = GroundingValidator().validate(valid, retrieval, listOf("fixture-demo"))
        assertTrue(validation.grounded)
        val escapedChunk = index.chunks.single().copy(text = "day-21-document-indexing-kotlin/scripts/run-indexer.sh --args=\\\"fixture-demo\\\"")
        val escapedRetrieval = retrieval.copy(selected = listOf(SearchResult(1.0, escapedChunk)))
        val commandAnswer = valid.copy(quotes = listOf(
            "day-21-document-indexing-kotlin/scripts/run-indexer.sh --args=\"fixture-demo\"",
        ))
        val escapedValidation = GroundingValidator().validate(commandAnswer, escapedRetrieval, emptyList())
        assertTrue(escapedValidation.grounded, escapedValidation.errors.joinToString())
        val profiles = config().profiles.associateBy { it.id }
        fun record(profile: OptimizationProfile, covered: Int) = OptimizationRecord(
            "q", "q", 1, profile.id, profile.label, profile.temperature, profile.numPredict, profile.numCtx,
            listOf(SearchResult(1.0, index.chunks.single()).toSummary()),
            GenerationOutcome(true, valid, validation.copy(expectedPointsCovered = covered, expectedPointsTotal = 1), GenerationMetrics(profile.model, profile.id, 100, completionTokens = 10, outputTokensPerSecond = 10.0)),
            null, ResourceSnapshot("now", listOf(RunningModel(profile.model, size = 10L)), null),
        )
        val q4 = OptimizationMetrics.aggregate(profiles.getValue("optimized-q4"), listOf(record(profiles.getValue("optimized-q4"), 0)))
        val q8 = OptimizationMetrics.aggregate(profiles.getValue("optimized-q8"), listOf(record(profiles.getValue("optimized-q8"), 1)))
        val recommendation = OptimizationMetrics.recommendation(listOf(q4, q8))
        assertNotNull(q4.outputTokensPerSecondMedian)
        assertTrue(recommendation.text.contains("Q8 improved"))
        assertEquals("optimized-q4", recommendation.primaryProfile)

        val profile = profiles.getValue("optimized-q4")
        val staleBefore = ResourceSnapshot("before", listOf(RunningModel(profile.model, contextLength = 32768)), null)
        val ownAfter = ResourceSnapshot("after", listOf(RunningModel(profile.model, contextLength = 8192)), null)
        val attributed = OptimizationMetrics.aggregate(profile, listOf(record(profile, 1).copy(before = staleBefore, after = ownAfter)))
        assertEquals(8192, attributed.maxContextLength)
    }

    private fun config(overrides: Map<String, String> = emptyMap()) = AppConfig.fromValues(
        mapOf("WEEK6_INDEX_FILE" to "missing.json", "OLLAMA_BASE_URL" to "http://127.0.0.1:11434", "OLLAMA_EMBED_MODEL" to "nomic-embed-text") + overrides,
    )

    private fun fixtureIndex() = Week6Index(
        "now", "structured", "ollama", "nomic-embed-text", 1, 1, 2,
        Day21IndexConfig("docs", 120, 20, 120),
        listOf(IndexedChunk(ChunkMetadata("day-21-document-indexing-kotlin/README.md", "Day 21", "Run", "structured-a", "structured", 1, 20), "Run fixture-demo with the Day 21 run-indexer script.", listOf(1.0, 0.0))),
    )

    private fun answerJson() = """{"status":"answered","answer":"Запустите fixture-demo.","sources":["structured-a"],"quotes":["Run fixture-demo with the Day 21 run-indexer script."],"clarifyingQuestion":null}"""
}

private class FakeEmbeddings : EmbeddingGateway {
    var calls = 0
    override fun embed(texts: List<String>) = EmbeddingReply("nomic-embed-text", listOf(listOf(1.0, 0.0)), null, 1).also { calls += 1 }
}

private class FakeGenerator(private val content: String) : OptimizationGenerator {
    var calls = 0
    val messages = mutableMapOf<String, List<PromptMessage>>()
    override fun generate(profile: OptimizationProfile, messages: List<PromptMessage>): ChatReply {
        calls += 1; this.messages[profile.id] = messages
        return ChatReply(profile.model, content, 10, 5, 1_000_000, 500_000)
    }
}

private class FakeResources : ResourceGateway { override fun snapshot() = ResourceSnapshot("now", emptyList(), null) }
