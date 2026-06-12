import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class StickyFacts(
    val userName: String = "",
    val goal: String = "",
    val audience: String = "",
    val platform: String = "",
    val constraints: String = "",
    val preferences: String = "",
    val decisions: String = "",
    val risks: String = "",
    val timeline: String = "",
    val monetization: String = "",
) {
    fun toPromptBlock(): String {
        val rows = listOf(
            "userName" to userName,
            "goal" to goal,
            "audience" to audience,
            "platform" to platform,
            "constraints" to constraints,
            "preferences" to preferences,
            "decisions" to decisions,
            "risks" to risks,
            "timeline" to timeline,
            "monetization" to monetization,
        ).filter { (_, value) -> value.isNotBlank() }

        return if (rows.isEmpty()) {
            "No stable facts yet."
        } else {
            rows.joinToString(separator = "\n") { (key, value) -> "$key: $value" }
        }
    }
}

@Serializable
data class BranchState(
    val checkpoint: List<ChatMessage> = emptyList(),
    val branches: Map<String, List<ChatMessage>> = emptyMap(),
    val activeBranch: String = "",
)

@Serializable
data class AgentState(
    val messages: List<ChatMessage> = emptyList(),
    val facts: StickyFacts = StickyFacts(),
    val branchState: BranchState = BranchState(),
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

enum class StrategyName(val cliName: String) {
    SLIDING("sliding"),
    FACTS("facts"),
    BRANCHING("branching");

    companion object {
        fun fromCli(value: String): StrategyName? = entries.firstOrNull { it.cliName == value.lowercase() }
    }
}

data class ContextBuildResult(
    val strategy: StrategyName,
    val messagesForApi: List<ChatMessage>,
    val promptTokens: Int,
    val recentMessagesSent: Int,
    val droppedMessages: Int,
    val factsBlock: String? = null,
    val branchInfo: String? = null,
)

data class AgentAnswer(
    val strategy: StrategyName,
    val answer: String,
    val context: ContextBuildResult,
    val usage: ApiUsage?,
    val warningOrError: String?,
    val factsUpdatePromptTokens: Int = 0,
)

data class FactUpdateStats(
    val promptTokens: Int,
    val mode: String,
    val warning: String?,
)
