import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ShortTermMemory(
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class WorkingMemory(
    val taskName: String = "",
    val goal: String = "",
    val status: String = "",
    val stage: String = "",
    val constraints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
) {
    fun toPromptBlock(): String {
        val rows = buildList {
            if (taskName.isNotBlank()) add("Task name: $taskName")
            if (goal.isNotBlank()) add("Goal: $goal")
            if (status.isNotBlank()) add("Status: $status")
            if (stage.isNotBlank()) add("Stage: $stage")
            addList("Constraints", constraints)
            addList("Decisions", decisions)
            addList("Unresolved open questions - not decisions", openQuestions)
            addList("Next steps", nextSteps)
        }
        return rows.joinToString("\n").ifBlank { "No working memory yet." }
    }
}

@Serializable
data class LongTermMemory(
    val userName: String = "",
    val preferences: List<String> = emptyList(),
    val globalRules: List<String> = emptyList(),
    val stableDecisions: List<String> = emptyList(),
    val knowledge: List<String> = emptyList(),
) {
    fun toPromptBlock(): String {
        val rows = buildList {
            if (userName.isNotBlank()) add("User name: $userName")
            addList("Preferences", preferences)
            addList("Global rules", globalRules)
            addList("Stable decisions", stableDecisions)
            addList("Knowledge", knowledge)
        }
        return rows.joinToString("\n").ifBlank { "No long-term memory yet." }
    }
}

private fun MutableList<String>.addList(title: String, values: List<String>) {
    if (values.isNotEmpty()) add("$title: ${values.joinToString("; ")}")
}

enum class MemoryLayer(val cliName: String) {
    SHORT("short"),
    WORKING("working"),
    LONG("long");

    companion object {
        fun fromCli(value: String): MemoryLayer? = entries.firstOrNull { it.cliName == value.lowercase() }
    }
}

data class MemorySnapshot(
    val shortTerm: ShortTermMemory,
    val working: WorkingMemory,
    val longTerm: LongTermMemory,
)

data class PromptLayerAudit(
    val layer: String,
    val included: Boolean,
    val tokens: Int,
    val details: String,
)

data class PromptBuildResult(
    val messagesForApi: List<ChatMessage>,
    val audits: List<PromptLayerAudit>,
    val promptTokens: Int,
    val recentMessagesSent: Int,
)

data class AgentAnswer(
    val answer: String,
    val prompt: PromptBuildResult,
    val usage: ApiUsage?,
    val warningOrError: String?,
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
