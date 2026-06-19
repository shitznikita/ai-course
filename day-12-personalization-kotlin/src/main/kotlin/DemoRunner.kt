class DemoRunner(
    private val memoryManager: MemoryManager,
    private val profileManager: UserProfileManager,
    private val promptBuilder: PromptBuilder,
    private val agent: PersonalizedAgent,
) {
    private val demoQuestion = "Объясни, как мне реализовать сохранение контекста в AI-агенте."

    fun run() {
        println("Day 12: Персонализация ассистента")
        println()
        profileManager.ensureSeedProfiles(reset = true)
        memoryManager.seedDemoMemory()

        println("=== AVAILABLE PROFILES ===")
        profileManager.listProfiles().forEach { println("- ${it.id}: ${it.role}, ${it.style}") }
        println()
        println("=== MEMORY LAYERS ===")
        println(memoryManager.show())
        println()

        val results = profileManager.listProfiles().map { profile ->
            println("=== PROFILE: ${profile.id} ===")
            println("Style: ${profile.style}")
            println("Format: ${profile.format}")
            val answer = agent.askAs(profile, demoQuestion)
            printAudit(answer)
            println(answer.warningOrError ?: answer.answer)
            println()
            profile to answer
        }

        println("=== COMPARISON ===")
        println("- beginner: должен получить простое объяснение, чек-лист и пояснение терминов.")
        println("- senior_mobile_dev: должен получить архитектуру, trade-offs и Kotlin/Android контекст.")
        println("- product_manager: должен получить цель, риски, MVP и меньше кода.")
        println("- В каждом prompt был добавлен только активный профиль, остальные профили не отправлялись.")
        println("- Profiles answered: ${results.joinToString { it.first.id }}")
    }

    private fun printAudit(answer: AgentAnswer) {
        println("=== PROMPT AUDIT ===")
        println("Active profile: ${answer.prompt.activeProfileId}")
        println("Prompt tokens estimate: ${answer.prompt.promptTokens}")
        answer.prompt.audits.forEach {
            println("- ${it.layer}: included=${it.included}, tokens=${it.tokens}, ${it.details}")
        }
        answer.usage?.let {
            println("API usage: prompt=${it.promptTokens ?: "n/a"}, completion=${it.completionTokens ?: "n/a"}, total=${it.totalTokens ?: "n/a"}, cost=${money(it.costUsd)}")
        }
        println()
    }
}
