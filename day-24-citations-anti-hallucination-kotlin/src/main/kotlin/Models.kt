import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

object AppJson {
    val compact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}

@Serializable
data class SourceDocument(
    val source: String,
    val title: String,
    val type: String,
    val text: String,
)

@Serializable
data class ChunkMetadata(
    val source: String,
    val title: String,
    val section: String,
    val chunkId: String,
    val strategy: String,
    val ordinal: Int,
    val approxTokens: Int,
)

@Serializable
data class ChunkDraft(
    val metadata: ChunkMetadata,
    val text: String,
)

@Serializable
data class IndexedChunk(
    val metadata: ChunkMetadata,
    val text: String,
    val embedding: List<Double>,
)

@Serializable
data class DocumentIndex(
    val generatedAtIso: String,
    val strategy: String,
    val embeddingBackend: String,
    val embeddingModel: String,
    val corpusMaxFiles: Int = 0,
    val structuredMaxTokens: Int = 0,
    val sourceDocumentCount: Int,
    val chunkCount: Int,
    val embeddingDimensions: Int,
    val chunks: List<IndexedChunk>,
)

data class LoadedCorpus(
    val documentsDir: String,
    val documents: List<SourceDocument>,
    val skipped: List<String>,
) {
    val approxTokens: Int = documents.sumOf { Tokenizer.approxTokens(it.text) }
    val approxPages: Double = approxTokens / 450.0
}

data class SearchResult(
    val score: Double,
    val chunk: IndexedChunk,
)

data class QueryRewrite(
    val original: String,
    val rewritten: String,
    val addedHints: List<String>,
)

data class RerankedRetrieval(
    val question: String,
    val rewrite: QueryRewrite,
    val before: List<SearchResult>,
    val selected: List<RerankedChunk>,
    val filteredOutCount: Int,
    val lowConfidence: Boolean,
)

data class RerankedChunk(
    val result: SearchResult,
    val rerankScore: Double,
    val lexicalOverlap: Double,
    val metadataBoost: Double,
    val passedThreshold: Boolean,
    val fallback: Boolean = false,
) {
    fun toSearchResult(): SearchResult = SearchResult(rerankScore, result.chunk)
}

@Serializable
data class ControlQuestion(
    val id: String,
    val question: String,
    val expectedAnswerPoints: List<String>,
    val expectedSources: List<String>,
)

@Serializable
data class UnknownQuestion(
    val id: String,
    val question: String,
    val expectedBehavior: String = "unknown",
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ApiUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
)

@Serializable
data class LlmResponse(
    val content: String,
    val usage: ApiUsage? = null,
    val warningOrError: String? = null,
)

@Serializable
data class AnswerRun(
    val mode: String,
    val question: String,
    val answer: String,
    val rewrittenQuery: String? = null,
    val lowConfidence: Boolean = false,
    val sources: List<String> = emptyList(),
    val retrievedChunks: List<RetrievedChunkSummary> = emptyList(),
    val usage: ApiUsage? = null,
    val warningOrError: String? = null,
)

@Serializable
data class RetrievedChunkSummary(
    val score: Double,
    val source: String,
    val title: String,
    val section: String,
    val chunkId: String,
    val approxTokens: Int,
)

@Serializable
data class RerankedChunkSummary(
    val baseScore: Double,
    val rerankScore: Double,
    val lexicalOverlap: Double,
    val metadataBoost: Double,
    val passedThreshold: Boolean,
    val fallback: Boolean,
    val source: String,
    val title: String,
    val section: String,
    val chunkId: String,
    val approxTokens: Int,
)

@Serializable
data class CitationSource(
    val source: String,
    val section: String,
    @SerialName("chunk_id")
    val chunkId: String,
)

@Serializable
data class AnswerQuote(
    @SerialName("quote_id")
    val quoteId: String? = null,
    val source: String,
    val section: String,
    @SerialName("chunk_id")
    val chunkId: String,
    val text: String,
)

@Serializable
data class QuoteCandidate(
    val id: String,
    val source: String,
    val title: String,
    val section: String,
    @SerialName("chunk_id")
    val chunkId: String,
    val text: String,
    val score: Double,
)

@Serializable
data class GroundedAnswer(
    val status: String,
    val answer: String,
    val sources: List<CitationSource> = emptyList(),
    val quotes: List<AnswerQuote> = emptyList(),
    val clarifyingQuestion: String? = null,
)

@Serializable
data class GroundingValidation(
    val statusAnswered: Boolean,
    val statusUnknown: Boolean,
    val answerPresent: Boolean,
    val sourcesPresent: Boolean,
    val quotesPresent: Boolean,
    val sourcesMatchRetrieved: Boolean,
    val quotesMatchChunks: Boolean,
    val expectedPointsCovered: Int,
    val expectedPointsTotal: Int,
    val answerMatchesQuotes: Boolean,
    val unknownSaysDontKnow: Boolean,
    val clarifyingQuestionPresent: Boolean,
    val errors: List<String> = emptyList(),
)

data class GroundedRun(
    val mode: String,
    val question: String,
    val retrieval: RerankedRetrieval,
    val quoteCandidates: List<QuoteCandidate>,
    val answer: GroundedAnswer,
    val validation: GroundingValidation,
    val promptPreview: String,
    val warningOrError: String? = null,
)

@Serializable
data class EvaluationRecord(
    val id: String,
    val question: String,
    val expectedAnswerPoints: List<String>,
    val expectedSources: List<String>,
    val retrievedSources: List<String>,
    val expectedSourceHits: List<String>,
    val noRag: AnswerRun? = null,
    val rag: AnswerRun? = null,
)

@Serializable
data class EvaluationReport(
    val generatedAtIso: String,
    val mode: String,
    val questionCount: Int,
    val expectedSourceHitCount: Int,
    val records: List<EvaluationRecord>,
)

@Serializable
data class RerankEvaluationRecord(
    val id: String,
    val question: String,
    val rewrittenQuery: String,
    val addedRewriteHints: List<String>,
    val expectedAnswerPoints: List<String>,
    val expectedSources: List<String>,
    val baselineSources: List<String>,
    val rerankedSources: List<String>,
    val baselineExpectedSourceHits: List<String>,
    val rerankedExpectedSourceHits: List<String>,
    val baselineFalsePositiveSources: List<String>,
    val rerankedFalsePositiveSources: List<String>,
    val baselineFirstHitRank: Int?,
    val rerankedFirstHitRank: Int?,
    val candidatesBeforeFilter: Int,
    val chunksAfterFilter: Int,
    val filteredOutCount: Int,
    val lowConfidence: Boolean,
    val baseline: AnswerRun? = null,
    val reranked: AnswerRun? = null,
)

@Serializable
data class RerankEvaluationReport(
    val generatedAtIso: String,
    val mode: String,
    val questionCount: Int,
    val expectedSourceCount: Int,
    val baselineExpectedSourceHitCount: Int,
    val rerankedExpectedSourceHitCount: Int,
    val baselineFalsePositiveCount: Int,
    val rerankedFalsePositiveCount: Int,
    val records: List<RerankEvaluationRecord>,
)

@Serializable
data class CitationEvaluationRecord(
    val id: String,
    val question: String,
    val expectedAnswerPoints: List<String> = emptyList(),
    val expectedSources: List<String> = emptyList(),
    val status: String,
    val maxRelevance: Double,
    val sources: List<CitationSource>,
    val quotes: List<AnswerQuote>,
    val validation: GroundingValidation,
)

@Serializable
data class CitationEvaluationReport(
    val generatedAtIso: String,
    val mode: String,
    val supportedQuestionCount: Int,
    val unknownQuestionCount: Int,
    val sourcesPresentCount: Int,
    val quotesPresentCount: Int,
    val quotesMatchChunksCount: Int,
    val answerMatchesQuotesCount: Int,
    val unknownCount: Int,
    val unknownClarifyingQuestionCount: Int,
    val records: List<CitationEvaluationRecord>,
)
