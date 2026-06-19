class MemoryManager(config: AppConfig) {
    private val shortStore = JsonStore(
        config.memoryRoot.resolve("short_term/current_chat.json"),
        ShortTermMemory.serializer(),
        ShortTermMemory(),
    )
    private val workingStore = JsonStore(
        config.memoryRoot.resolve("working/task_context.json"),
        WorkingMemory.serializer(),
        WorkingMemory(),
    )
    private val longStore = JsonStore(
        config.memoryRoot.resolve("long_term/user_memory.json"),
        LongTermMemory.serializer(),
        LongTermMemory(),
    )

    fun snapshot(): MemorySnapshot = MemorySnapshot(shortStore.load(), workingStore.load(), longStore.load())

    fun appendShort(message: ChatMessage) {
        val current = shortStore.load()
        shortStore.save(current.copy(messages = current.messages + message))
    }

    fun seedDemoMemory() {
        longStore.save(
            LongTermMemory(
                userName = "Никита",
                globalRules = listOf("API-ключи только в .env или переменных окружения"),
                stableKnowledge = listOf("Курс строится на Kotlin CLI агентах и прямом REST API"),
            ),
        )
        workingStore.save(
            WorkingMemory(
                taskName = "Персонализация AI-агента",
                goal = "Показать, как активный профиль меняет ответы",
                status = "demo",
                stage = "profile_comparison",
                constraints = listOf("не смешивать все профили в prompt", "подключать только активный профиль"),
                decisions = listOf("профили хранятся отдельно от памяти диалога"),
                openQuestions = listOf("какой профиль выбрать по умолчанию для реальной работы"),
            ),
        )
        shortStore.save(
            ShortTermMemory(
                messages = listOf(
                    ChatMessage("user", "Хочу показать персонализацию на одном вопросе."),
                    ChatMessage("assistant", "Сравним ответы для beginner, senior_mobile_dev и product_manager."),
                ),
            ),
        )
    }

    fun show(): String = """
        |SHORT-TERM MEMORY
        |${appJson.encodeToString(ShortTermMemory.serializer(), snapshot().shortTerm)}
        |
        |WORKING MEMORY
        |${appJson.encodeToString(WorkingMemory.serializer(), snapshot().working)}
        |
        |LONG-TERM MEMORY
        |${appJson.encodeToString(LongTermMemory.serializer(), snapshot().longTerm)}
    """.trimMargin()
}
