import kotlinx.serialization.encodeToString

class MemoryManager(config: AppConfig) {
    private val shortStore = JsonMemoryStore(
        config.memoryRoot.resolve("short_term/current_chat.json"),
        ShortTermMemory.serializer(),
        ShortTermMemory(),
    )
    private val workingStore = JsonMemoryStore(
        config.memoryRoot.resolve("working/task_context.json"),
        WorkingMemory.serializer(),
        WorkingMemory(),
    )
    private val longStore = JsonMemoryStore(
        config.memoryRoot.resolve("long_term/user_profile.json"),
        LongTermMemory.serializer(),
        LongTermMemory(),
    )

    fun snapshot(): MemorySnapshot = MemorySnapshot(
        shortTerm = shortStore.load(),
        working = workingStore.load(),
        longTerm = longStore.load(),
    )

    fun appendShortMessage(message: ChatMessage) {
        val current = shortStore.load()
        shortStore.save(current.copy(messages = current.messages + message))
    }

    fun saveShortText(text: String) {
        appendShortMessage(ChatMessage("user", "[short-term memory] $text"))
    }

    fun saveWorking(key: String, value: String): Boolean {
        val current = workingStore.load()
        val next = when (key) {
            "task_name" -> current.copy(taskName = value)
            "goal" -> current.copy(goal = value)
            "status" -> current.copy(status = value)
            "stage" -> current.copy(stage = value)
            "constraint" -> current.copy(constraints = current.constraints.appendUnique(value))
            "decision" -> current.copy(decisions = current.decisions.appendUnique(value))
            "open_question" -> current.copy(openQuestions = current.openQuestions.appendUnique(value))
            "next_step" -> current.copy(nextSteps = current.nextSteps.appendUnique(value))
            else -> return false
        }
        workingStore.save(next)
        return true
    }

    fun saveLong(key: String, value: String): Boolean {
        val current = longStore.load()
        val next = when (key) {
            "user_name" -> current.copy(userName = value)
            "preference" -> current.copy(preferences = current.preferences.appendUnique(value))
            "global_rule" -> current.copy(globalRules = current.globalRules.appendUnique(value))
            "stable_decision" -> current.copy(stableDecisions = current.stableDecisions.appendUnique(value))
            "knowledge" -> current.copy(knowledge = current.knowledge.appendUnique(value))
            else -> return false
        }
        longStore.save(next)
        return true
    }

    fun clearShort() = shortStore.clear()

    fun clearWorking() = workingStore.clear()

    fun clearAllForDemo() {
        shortStore.clear()
        workingStore.clear()
        longStore.clear()
    }

    fun show(layer: MemoryLayer?): String {
        val snapshot = snapshot()
        return when (layer) {
            MemoryLayer.SHORT -> appJson.encodeToString(snapshot.shortTerm)
            MemoryLayer.WORKING -> appJson.encodeToString(snapshot.working)
            MemoryLayer.LONG -> appJson.encodeToString(snapshot.longTerm)
            null -> """
                |SHORT-TERM MEMORY
                |${appJson.encodeToString(snapshot.shortTerm)}

                |WORKING MEMORY
                |${appJson.encodeToString(snapshot.working)}

                |LONG-TERM MEMORY
                |${appJson.encodeToString(snapshot.longTerm)}
            """.trimMargin()
        }
    }

    private fun List<String>.appendUnique(value: String): List<String> {
        val normalized = value.trim()
        if (normalized.isBlank()) return this
        if (any { it.equals(normalized, ignoreCase = true) }) return this
        return this + normalized
    }
}
