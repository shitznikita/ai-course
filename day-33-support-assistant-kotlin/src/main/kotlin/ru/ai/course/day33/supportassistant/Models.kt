package ru.ai.course.day33.supportassistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object SupportJson {
    val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
        prettyPrint = true
    }
    val compact = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }
    val tolerant = Json { ignoreUnknownKeys = true }
}

@Serializable
data class SupportDataFile(
    val synthetic: Boolean,
    val users: List<SupportUser>,
    val tickets: List<SupportTicket>,
)

@Serializable
data class SupportUser(
    val id: String,
    val displayName: String,
    val plan: String,
    val accountState: String,
    val locale: String,
)

@Serializable
data class SupportTicket(
    val id: String,
    val userId: String,
    val category: String,
    val productArea: String,
    val status: String,
    val priority: String,
    val errorCode: String,
    val summary: String,
    val failedAuthAttempts: Int,
    val deviceClockSkewSeconds: Int,
)

@Serializable
data class SupportContext(
    val ticket: SupportTicket,
    val user: SupportUser,
    val facts: List<ContextFact>,
) {
    init {
        require(ticket.userId == user.id) { "Ticket/user relationship is inconsistent." }
        require(facts.map(ContextFact::id).distinct().size == facts.size) { "Context fact IDs must be unique." }
    }
}

@Serializable
data class TicketToolResponse(
    val found: Boolean,
    val ticket: SupportTicket?,
)

@Serializable
data class UserToolResponse(
    val found: Boolean,
    val user: SupportUser?,
)

data class McpContextFetch(
    val availableTools: List<String>,
    val usedTools: List<String>,
    val context: SupportContext?,
    val failureReason: String?,
)

@Serializable
data class ContextFact(
    val id: String,
    val label: String,
    val value: String,
)

@Serializable
data class KnowledgeDocument(
    val relativePath: String,
    val title: String,
    val markdown: String,
)

@Serializable
data class KnowledgeChunk(
    val sourceId: String,
    val documentPath: String,
    val heading: String,
    val text: String,
    val fingerprint: String,
    val embedding: List<Double>,
)

@Serializable
data class RagIndexFile(
    val schemaVersion: Int,
    val embeddingModel: String,
    val dimensions: Int,
    val chunks: List<KnowledgeChunk>,
)

data class RetrievedKnowledge(
    val chunk: KnowledgeChunk,
    val score: Double,
    val lexicalScore: Double,
    val vectorScore: Double,
    val questionScore: Double,
)

data class KnowledgeEvidencePack(
    val query: String,
    val items: List<RetrievedKnowledge>,
    val allowedSourceIds: Set<String>,
    val renderedBlock: String,
    val totalChars: Int,
)

@Serializable
data class TransmittedSupportInput(
    val question: String,
    val ticketId: String,
    val linkedUserId: String,
    val contextFacts: List<ContextFact>,
    val untrustedTicketSummary: String,
    val evidence: List<TransmittedEvidence>,
    val recentHistory: List<ChatTurn>,
)

@Serializable
data class TransmittedEvidence(
    val sourceId: String,
    val heading: String,
    val text: String,
)

@Serializable
data class ChatTurn(
    val ticketId: String,
    val question: String,
    val answer: String,
)

data class PromptPack(
    val system: String,
    val user: String,
    val transmittedInput: TransmittedSupportInput,
    val allowedSourceIds: Set<String>,
    val allowedFactIds: Set<String>,
)

@Serializable
enum class ModelAnswerStatus {
    @SerialName("answered")
    ANSWERED,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class SupportModelResponse(
    val status: ModelAnswerStatus,
    val answer: String,
    val actionSteps: List<String>,
    val knowledgeSourceIds: List<String>,
    val contextFactIds: List<String>,
    val clarifyingQuestion: String?,
)

data class LlmReply(
    val model: String,
    val content: String,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
)

data class GroundingValidation(
    val valid: Boolean,
    val response: SupportModelResponse?,
    val reasons: List<String>,
)

enum class AssistantOutcome {
    ANSWERED,
    UNKNOWN,
    NOT_FOUND,
}

data class SupportAssistantResult(
    val outcome: AssistantOutcome,
    val question: String,
    val ticketId: String,
    val context: SupportContext?,
    val evidence: KnowledgeEvidencePack?,
    val response: SupportModelResponse,
    val mcpToolsUsed: List<String>,
    val grounded: Boolean,
    val currentTicketIsolationValid: Boolean,
    val llmCalls: Int,
    val failureReason: String? = null,
)

@Serializable
data class EvaluationFile(val scenarios: List<EvaluationScenario>)

@Serializable
data class EvaluationScenario(
    val id: String,
    val ticketId: String,
    val question: String,
    val expectedStatus: String,
    val expectedSourceIds: List<String>,
    val expectedFactIds: List<String>,
)
