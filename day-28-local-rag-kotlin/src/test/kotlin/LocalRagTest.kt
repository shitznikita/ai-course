import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalRagTest {
    @Test
    fun `loads and validates the exact Day 21 index contract`() {
        val raw = resourceText("day21-index-fixture.json")
        val path = Files.createTempFile("day21-index", ".json")
        Files.writeString(path, raw)

        val index = Week6IndexReader(testConfig(mapOf("WEEK6_INDEX_FILE" to path.toString()))).load()

        assertEquals("structured", index.strategy)
        assertEquals("nomic-embed-text", index.embeddingModel)
        assertEquals(2, index.embeddingDimensions)
        assertEquals("structured-a", index.chunks.first().metadata.chunkId)
        assertFailsWith<IndexValidationException> {
            Week6IndexValidator.validate(index, "another-embedding-model")
        }
        assertFailsWith<IndexValidationException> {
            Week6IndexValidator.validate(index.copy(chunkCount = 3), "nomic-embed-text")
        }
        assertFailsWith<IndexValidationException> {
            Week6IndexValidator.validate(
                index.copy(chunks = index.chunks.mapIndexed { position, chunk ->
                    if (position == 0) chunk.copy(embedding = listOf(0.0, 0.0)) else chunk
                }),
                "nomic-embed-text",
            )
        }
    }

    @Test
    fun `rejects non-loopback Ollama and non-https cloud URLs`() {
        assertFailsWith<ConfigurationException> {
            testConfig(mapOf("OLLAMA_BASE_URL" to "https://example.com"))
        }
        assertFailsWith<ConfigurationException> {
            testConfig(mapOf("LLM_API_URL" to "http://example.com/chat"))
        }
        assertTrue(OllamaStatus("test", listOf(OllamaModel("nomic-embed-text:latest", null))).hasModel("nomic-embed-text"))
    }

    @Test
    fun `retrieves local cosine top k and keeps context within budget`() {
        val index = fixtureIndex()
        val embeddings = FakeEmbeddingGateway(listOf(listOf(1.0, 0.0)))
        val config = testConfig(mapOf("RETRIEVAL_TOP_K" to "2", "RAG_MAX_CONTEXT_TOKENS" to "300"))
        val retrieval = LocalRetriever(embeddings, config).retrieve(index, "How do I run the demo?")

        assertEquals(1, embeddings.calls)
        assertEquals("structured-a", retrieval.selected.first().chunk.metadata.chunkId)
        assertTrue(retrieval.contextTokens <= config.ragMaxContextTokens)
        assertTrue(retrieval.context.contains("chunk_id=structured-a"))

        val oversized = ContextBuilder(3).build("question", retrieval.selected)
        assertTrue(oversized.selected.isEmpty())
        assertEquals(0, oversized.contextTokens)
    }

    @Test
    fun `validates citations and unknown answers`() {
        val retrieval = RetrievalPackage(
            question = "How do I run Day 21?",
            selected = listOf(SearchResult(0.9, fixtureIndex().chunks.first())),
            context = "fixture-demo",
            contextTokens = 4,
        )
        val expected = BenchmarkQuestion(
            id = "q",
            question = retrieval.question,
            expectedAnswerPoints = listOf("fixture-demo"),
            expectedSources = listOf("day-21-document-indexing-kotlin/README.md"),
        )
        val answer = GroundedAnswer(
            status = "answered",
            answer = "Запустите fixture-demo.",
            sources = listOf(CitationSource("day-21-document-indexing-kotlin/README.md", "Run", "structured-a")),
            quotes = listOf(AnswerQuote(null, "day-21-document-indexing-kotlin/README.md", "Run", "structured-a", "Run fixture-demo with the Day 21 run-indexer script.")),
            clarifyingQuestion = null,
        )
        val validation = GroundingValidator().validate(answer, retrieval, expected.expectedAnswerPoints, expected.expectedSources)
        assertTrue(validation.grounded)

        val invalidQuote = answer.copy(quotes = listOf(answer.quotes.single().copy(text = "invented quote")))
        assertTrue(!GroundingValidator().validate(invalidQuote, retrieval).grounded)

        val unknown = GroundedAnswer(
            status = "unknown",
            answer = "не знаю",
            sources = emptyList(),
            quotes = emptyList(),
            clarifyingQuestion = "Уточните документ.",
        )
        assertTrue(GroundingValidator().validate(unknown, retrieval).grounded)
        assertFailsWith<OllamaProtocolException> {
            GroundedAnswerParser.parse("""{"status":"unknown","answer":"не знаю"}""")
        }
    }

    @Test
    fun `benchmark reuses one retrieval and gives both models identical prompt`() {
        val index = fixtureIndex()
        val retrieval = RetrievalPackage(
            question = "How do I run Day 21?",
            selected = listOf(SearchResult(0.9, index.chunks.first())),
            context = "[1] source=day-21-document-indexing-kotlin/README.md; section=Run; chunk_id=structured-a\nRun fixture-demo with the Day 21 run-indexer script.",
            contextTokens = 30,
        )
        val engine = FakeRetrievalEngine(retrieval)
        val answerJson = AppJson.compact.encodeToString(
            GroundedAnswer(
                status = "answered",
                answer = "Запустите fixture-demo.",
                sources = listOf(CitationSource("day-21-document-indexing-kotlin/README.md", "Run", "structured-a")),
                quotes = listOf(AnswerQuote(null, "day-21-document-indexing-kotlin/README.md", "Run", "structured-a", "Run fixture-demo with the Day 21 run-indexer script.")),
                clarifyingQuestion = null,
            ),
        )
        val local = FakeAnswerGenerator("local", "qwen3:14b", answerJson)
        val cloud = FakeAnswerGenerator("cloud", "cloud-model", answerJson)
        val service = RagComparisonService(engine, local, cloud)

        val question = BenchmarkQuestion("q", retrieval.question, emptyList(), emptyList())
        val prepared = service.prepare(index, question)
        val runs = List(3) { service.runComparison(prepared, question) }

        assertEquals(1, engine.calls)
        assertEquals(3, local.calls)
        assertEquals(3, cloud.calls)
        assertEquals(local.receivedMessages, cloud.receivedMessages)
        assertTrue(runs.all { it.local.transportSucceeded && assertNotNull(it.cloud).transportSucceeded })
    }

    @Test
    fun `aggregates quality speed and stability without NaN`() {
        val answer = GroundedAnswer("answered", "answer", listOf(CitationSource("a", "s", "c")), listOf(AnswerQuote(null, "a", "s", "c", "quote")), null)
        val validation = GroundingValidation(true, true, true, true, true, true, true, 1, 1, 1, 1, true)
        val outcomes = listOf(
            GenerationOutcome(true, answer, validation, GenerationMetrics("local", "local", 100, completionTokens = 10, outputTokensPerSecond = 10.0)),
            GenerationOutcome(true, answer, validation, GenerationMetrics("local", "local", 300, completionTokens = 10, outputTokensPerSecond = 5.0)),
            GenerationOutcome(false, metrics = GenerationMetrics("local", "local", 0, error = "failed")),
        )

        val aggregate = BenchmarkMetrics.aggregate("local", "local", outcomes)

        assertEquals(3, aggregate.runs)
        assertEquals(2.0 / 3.0, aggregate.transportSuccessRate)
        assertEquals(1.0, assertNotNull(aggregate.sourceSetStability))
        assertEquals(200, aggregate.latencyMedianMs)
        assertTrue(aggregate.outputTokensPerSecondMedian!!.isFinite())
    }

    @Test
    fun `source stability compares repeats within each question only`() {
        val validation = GroundingValidation(true, true, true, true, true, true, true, 0, 0, 0, 0, true)
        fun outcome(source: String): GenerationOutcome = GenerationOutcome(
            transportSucceeded = true,
            answer = GroundedAnswer(
                "answered",
                "answer",
                listOf(CitationSource(source, "section", "chunk")),
                listOf(AnswerQuote(null, source, "section", "chunk", "quote")),
                null,
            ),
            validation = validation,
            metrics = GenerationMetrics("local", "local", 100),
        )
        val q02 = outcome("q02-source")
        val q04 = outcome("q04-source")

        val aggregate = BenchmarkMetrics.aggregate(
            "local",
            "local",
            outcomes = listOf(q02, q02, q04, q04),
            stabilityOutcomeGroups = listOf(listOf(q02, q02), listOf(q04, q04)),
        )

        assertEquals(1.0, assertNotNull(aggregate.sourceSetStability))
    }

    private fun fixtureIndex(): Week6Index = AppJson.compact.decodeFromString(resourceText("day21-index-fixture.json"))

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    private fun testConfig(overrides: Map<String, String> = emptyMap()): AppConfig = AppConfig.fromValues(
        mapOf(
            "WEEK6_INDEX_FILE" to "missing-for-unit-test.json",
            "OLLAMA_BASE_URL" to "http://127.0.0.1:11434",
            "OLLAMA_MODEL" to "qwen3:14b",
            "OLLAMA_EMBED_MODEL" to "nomic-embed-text",
            "LLM_API_URL" to "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
        ) + overrides,
    )
}

private class FakeEmbeddingGateway(private val embeddings: List<List<Double>>) : EmbeddingGateway {
    var calls: Int = 0

    override fun embed(texts: List<String>): EmbeddingReply {
        calls += 1
        return EmbeddingReply("nomic-embed-text", embeddings, null, 1, null)
    }
}

private class FakeRetrievalEngine(private val result: RetrievalPackage) : RetrievalEngine {
    var calls: Int = 0

    override fun retrieve(index: Week6Index, question: String): RetrievalPackage {
        calls += 1
        return result
    }
}

private class FakeAnswerGenerator(
    override val kind: String,
    override val configuredModel: String,
    private val content: String,
) : AnswerGenerator {
    var calls: Int = 0
    var receivedMessages: List<PromptMessage>? = null

    override fun generate(messages: List<PromptMessage>): ChatReply {
        calls += 1
        receivedMessages = messages
        return ChatReply(configuredModel, content, 10, 5, 1_000_000, 500_000)
    }
}
