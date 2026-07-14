package ru.ai.course.day31.developerassistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AppJson {
    val strict: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    val tolerant: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }
}

object GeneratedDocumentationAnswerContract {
    val requiredFields: List<String> = listOf(
        "status",
        "answer",
        "sourceIds",
    )
    val systemFieldList: String = requiredFields.joinToString(", ")
}

@Serializable
data class ProjectDocument(
    val source: String,
    val title: String,
    val type: String,
    val text: String,
    val contentSha256: String,
)

@Serializable
data class ChunkMetadata(
    val source: String,
    val section: String,
    val chunkId: String,
    val contentSha256: String,
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
data class CorpusManifestEntry(
    val source: String,
    val contentSha256: String,
    val bytes: Long,
)

@Serializable
data class RagIndex(
    val generatedAtIso: String,
    val embeddingBackend: String,
    val embeddingModel: String,
    val chunkMaxTokens: Int,
    val embeddingDimensions: Int,
    val manifest: List<CorpusManifestEntry>,
    val chunks: List<IndexedChunk>,
)

data class LoadedProjectCorpus(
    val documents: List<ProjectDocument>,
    val manifest: List<CorpusManifestEntry>,
)

data class RetrievalHit(
    val score: Double,
    val vectorScore: Double,
    val lexicalScore: Double,
    val metadataBoost: Double,
    val chunk: IndexedChunk,
)

data class RetrievalResult(
    val question: String,
    val hits: List<RetrievalHit>,
    val lowConfidence: Boolean,
) {
    val sourceIds: Set<String> = hits.mapTo(linkedSetOf()) { it.chunk.metadata.chunkId }
}

data class EvidenceItem(
    val hit: RetrievalHit,
    val text: String,
    val renderedBlock: String,
    val textTruncated: Boolean,
) {
    val sourceId: String = hit.chunk.metadata.chunkId
}

data class EvidencePack(
    val items: List<EvidenceItem>,
    val renderedDocumentation: String,
    val approxTokens: Int,
    val maxTokens: Int,
    val retrievalTruncated: Boolean,
) {
    val hits: List<RetrievalHit> = items.map { it.hit }
    val sourceIds: Set<String> = items.mapTo(linkedSetOf()) { it.sourceId }
}

@Serializable
data class GitBranchInfo(
    val displayName: String,
    val detached: Boolean,
    val shortSha: String? = null,
)

@Serializable
data class GitFileList(
    val prefix: String? = null,
    val files: List<String>,
    val truncated: Boolean,
)

data class McpProjectContext(
    val availableTools: List<String>,
    val branch: GitBranchInfo,
    val files: GitFileList? = null,
    val usedTools: List<String>,
)

data class McpFileEvidence(
    val prefix: String?,
    val files: List<String>,
    val serverReturnedCount: Int,
    val serverTruncated: Boolean,
    val boundedIncludedCount: Int,
    val byteBudgetTruncated: Boolean,
)

data class McpEvidence(
    val availableTools: List<String>,
    val usedTools: List<String>,
    val branch: GitBranchInfo,
    val files: McpFileEvidence?,
)

data class GroundingRequirements(
    val documentationRequired: Boolean,
    val branchRequired: Boolean,
    val filesRequired: Boolean,
    val fetchFiles: Boolean = filesRequired,
)

@Serializable
data class GeneratedDocumentationAnswer(
    val status: String,
    val answer: String,
    val sourceIds: List<String>,
)

@Serializable
data class AssistantAnswer(
    val status: String,
    val answer: String,
    val sourceIds: List<String>,
    val usedProjectContext: Boolean,
    val projectBranch: String?,
    val projectFiles: List<String>,
)

data class GroundingValidation(
    val valid: Boolean,
    val unknownSourceIds: List<String>,
    val errors: List<String>,
)

data class AssistantRun(
    val question: String,
    val retrieval: RetrievalResult,
    val evidence: EvidencePack,
    val mcp: McpEvidence,
    val requirements: GroundingRequirements,
    val answer: AssistantAnswer,
    val validation: GroundingValidation,
    val prompt: PromptPack,
    val fixture: Boolean,
    val preflightRefusalReason: String? = null,
)

data class PromptPack(
    val system: String,
    val user: String,
    val preview: String,
    val approxTokens: Int,
    val maxTokens: Int,
    val utf8Bytes: Int,
    val maxBytes: Int,
)

data class PreparedPrompt(
    val evidence: EvidencePack,
    val mcp: McpEvidence,
    val prompt: PromptPack,
)

@Serializable
data class OllamaReply(
    val model: String,
    val content: String,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val elapsedNanos: Long,
)

@Serializable
data class OllamaModel(
    val name: String,
)

@Serializable
data class OllamaStatus(
    val version: String,
    val models: List<OllamaModel>,
)

@Serializable
data class ControlQuestion(
    val id: String,
    val question: String,
    val expectedSources: List<String>,
    val expectedTerms: List<String>,
    val expectUnknown: Boolean = false,
)

@Serializable
data class EvaluationCaseResult(
    val id: String,
    val passed: Boolean,
    val lowConfidence: Boolean,
    val retrievedSources: List<String>,
    val missingSources: List<String>,
    val missingTerms: List<String>,
)

@Serializable
data class EvaluationReport(
    val total: Int,
    val passed: Int,
    val cases: List<EvaluationCaseResult>,
)

@Serializable
data class ModelListResponse(
    val models: List<ModelListItem> = emptyList(),
)

@Serializable
data class ModelListItem(
    val name: String,
)

@Serializable
data class OllamaChatEnvelope(
    val model: String? = null,
    val message: OllamaChatMessage? = null,
    val done: Boolean = false,
    @SerialName("prompt_eval_count")
    val promptEvalCount: Long? = null,
    @SerialName("eval_count")
    val evalCount: Long? = null,
)

@Serializable
data class OllamaChatMessage(
    val role: String? = null,
    val content: String = "",
)
