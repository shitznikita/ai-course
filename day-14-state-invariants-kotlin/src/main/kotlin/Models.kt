import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class TaskBrief(
    val taskTitle: String = "",
    val goal: String = "",
    val knownInputs: List<String> = emptyList(),
    val missingInputs: List<String> = emptyList(),
    val readyForPlanning: Boolean = false,
)

@Serializable
data class TaskPlan(
    val steps: List<String> = emptyList(),
    val requiresUserApproval: Boolean = true,
    val readyForExecution: Boolean = false,
    val notes: String = "",
)

@Serializable
data class DraftResult(
    val content: String = "",
    val completedSteps: List<String> = emptyList(),
    val readyForValidation: Boolean = false,
)

@Serializable
data class ValidationReport(
    val isValid: Boolean = false,
    val issues: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val readyForDone: Boolean = false,
)

@Serializable
data class TaskArtifacts(
    val taskBrief: TaskBrief? = null,
    val taskPlan: TaskPlan? = null,
    val draftResult: DraftResult? = null,
    val validationReport: ValidationReport? = null,
)

@Serializable
data class TransitionRecord(
    val timestamp: String,
    val from: String,
    val to: String,
    val reason: String,
)

@Serializable
data class TaskState(
    val taskId: String = "task_001",
    val title: String = "",
    val status: String = "intake",
    val currentStep: String = "receive_task",
    val expectedAction: String = "user_input",
    val previousStatus: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val userRequest: String = "",
    val artifacts: TaskArtifacts = TaskArtifacts(),
    val history: List<TransitionRecord> = emptyList(),
    val pausedReason: String? = null,
)

@Serializable
data class Invariant(
    val id: String,
    val title: String,
    val description: String,
    val type: String,
    val severity: String,
    val enabled: Boolean = true,
    val forbiddenKeywords: List<String> = emptyList(),
    val forbiddenPatterns: List<String> = emptyList(),
)

@Serializable
data class InvariantsFile(
    val project: List<Invariant> = emptyList(),
    val security: List<Invariant> = emptyList(),
    val communication: List<Invariant> = emptyList(),
    val userDefined: List<Invariant> = emptyList(),
) {
    fun all(): List<Invariant> = project + security + communication + userDefined
    fun active(): List<Invariant> = all().filter { it.enabled }
}

@Serializable
data class Violation(
    val invariantId: String,
    val severity: String,
    val where: String,
    val message: String,
)

@Serializable
data class ValidationResult(
    val valid: Boolean,
    val violations: List<Violation> = emptyList(),
) {
    fun render(): String = buildString {
        appendLine("Valid: $valid")
        violations.forEach {
            appendLine("Violation: ${it.invariantId}")
            appendLine("Severity: ${it.severity}")
            appendLine("Where: ${it.where}")
            appendLine("Reason: ${it.message}")
        }
    }.trimEnd()
}

data class TransitionResult(
    val allowed: Boolean,
    val state: TaskState,
    val message: String,
)

data class StageResult<T>(
    val artifact: T,
    val llmNotes: String,
    val valid: Boolean,
    val responseValidation: ValidationResult,
    val retryUsed: Boolean,
)

data class ApiUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val costUsd: Double?,
)

data class LlmResponse(
    val content: String,
    val usage: ApiUsage?,
    val warningOrError: String?,
)
