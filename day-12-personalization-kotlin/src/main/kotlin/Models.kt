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
) {
    fun toPromptBlock(): String = buildList {
        if (taskName.isNotBlank()) add("Task name: $taskName")
        if (goal.isNotBlank()) add("Goal: $goal")
        if (status.isNotBlank()) add("Status: $status")
        if (stage.isNotBlank()) add("Stage: $stage")
        addList("Constraints", constraints)
        addList("Decisions", decisions)
        addList("Open questions", openQuestions)
    }.joinToString("\n").ifBlank { "No working memory yet." }
}

@Serializable
data class LongTermMemory(
    val userName: String = "",
    val globalRules: List<String> = emptyList(),
    val stableKnowledge: List<String> = emptyList(),
) {
    fun toPromptBlock(): String = buildList {
        if (userName.isNotBlank()) add("User name: $userName")
        addList("Global rules", globalRules)
        addList("Stable knowledge", stableKnowledge)
    }.joinToString("\n").ifBlank { "No long-term memory yet." }
}

@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val role: String,
    val experienceLevel: String,
    val style: String,
    val format: String,
    val domainOrStack: String,
    val constraints: List<String> = emptyList(),
    val habits: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val language: String = "ru",
    val doExamples: List<String> = emptyList(),
    val avoidExamples: List<String> = emptyList(),
) {
    fun toPromptBlock(): String = buildList {
        add("Profile id: $id")
        add("Name: $name")
        add("Role: $role")
        add("Experience level: $experienceLevel")
        add("Style: $style")
        add("Format: $format")
        add("Domain or stack: $domainOrStack")
        add("Language: $language")
        addList("Constraints", constraints)
        addList("Habits", habits)
        addList("Preferences", preferences)
        addList("Do examples", doExamples)
        addList("Avoid examples", avoidExamples)
    }.joinToString("\n")
}

@Serializable
data class ProfilesFile(
    val profiles: List<UserProfile> = emptyList(),
)

@Serializable
data class ActiveProfileFile(
    val activeProfileId: String = "beginner",
)

data class MemorySnapshot(
    val shortTerm: ShortTermMemory,
    val working: WorkingMemory,
    val longTerm: LongTermMemory,
)

data class PromptAudit(
    val layer: String,
    val included: Boolean,
    val tokens: Int,
    val details: String,
)

data class PromptBuildResult(
    val messagesForApi: List<ChatMessage>,
    val audits: List<PromptAudit>,
    val promptTokens: Int,
    val activeProfileId: String,
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

private fun MutableList<String>.addList(title: String, values: List<String>) {
    if (values.isNotEmpty()) add("$title: ${values.joinToString("; ")}")
}
