import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class TransitionRecord(
    val timestamp: String,
    val from: String,
    val to: String,
    val allowed: Boolean,
    val guardsPassed: Boolean,
    val reason: String,
)

@Serializable
data class TaskArtifacts(
    val taskBrief: String = "",
    val finalPlan: String = "",
    val approvalSummary: String = "",
    val executionResult: String = "",
    val validationReport: String = "",
)

@Serializable
data class TaskState(
    val taskId: String = "task_001",
    val title: String = "",
    val currentState: String = "intake",
    val previousState: String? = null,
    val currentStep: String = "receive_task",
    val expectedAction: String = "user_input",
    val approvedPlan: Boolean = false,
    val validationPassed: Boolean = false,
    val pausedReason: String? = null,
    val userRequest: String = "",
    val createdAt: String,
    val updatedAt: String,
    val artifacts: TaskArtifacts = TaskArtifacts(),
    val transitionHistory: List<TransitionRecord> = emptyList(),
)

@Serializable
data class Invariant(
    val id: String,
    val title: String,
    val description: String,
    val severity: String = "high",
    val enabled: Boolean = true,
    val forbiddenKeywords: List<String> = emptyList(),
)

@Serializable
data class InvariantsFile(
    val invariants: List<Invariant> = emptyList(),
)

data class ValidationResult(
    val valid: Boolean,
    val violations: List<String> = emptyList(),
) {
    fun render(): String = if (valid) "Valid: true" else "Valid: false\n" + violations.joinToString("\n")
}

data class TransitionCheck(
    val allowed: Boolean,
    val guardsPassed: Boolean,
    val reason: String,
)

data class TransitionResult(
    val state: TaskState,
    val check: TransitionCheck,
    val from: String,
    val to: String,
)

data class StateAgentResult(
    val agentName: String,
    val ownedState: String,
    val artifactName: String,
    val content: String,
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
