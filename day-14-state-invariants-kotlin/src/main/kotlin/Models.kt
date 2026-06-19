import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
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

data class AgentResult(
    val response: String,
    val requestValidation: ValidationResult,
    val responseValidation: ValidationResult?,
    val activeInvariants: List<Invariant>,
    val llmCalled: Boolean,
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
