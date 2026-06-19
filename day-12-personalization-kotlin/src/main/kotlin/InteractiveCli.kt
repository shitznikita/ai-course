class InteractiveCli(
    private val memoryManager: MemoryManager,
    private val profileManager: UserProfileManager,
    private val promptBuilder: PromptBuilder,
    private val agent: PersonalizedAgent,
) {
    fun run() {
        profileManager.ensureSeedProfiles()
        println("Day 12 personalized agent. Type /profile list, /profile compare, /memory show, or /exit.")
        while (true) {
            print("> ")
            val line = readlnOrNull()?.trim() ?: break
            if (line.isBlank()) continue
            when {
                line == "/exit" -> return
                line == "/profile list" -> printProfileList()
                line.startsWith("/profile use ") -> {
                    val id = line.removePrefix("/profile use ").trim()
                    println(if (profileManager.useProfile(id)) "Active profile: $id" else "Unknown profile: $id")
                }
                line == "/profile show" -> println(profileManager.showActive())
                line == "/profile create" -> println("Created and selected profile: ${profileManager.createTemplate().id}")
                line.startsWith("/profile update ") -> updateProfile(line.removePrefix("/profile update ").trim())
                line == "/profile compare" -> compareProfiles()
                line == "/memory show" -> println(memoryManager.show())
                line == "/profile status" -> println(promptBuilder.status(memoryManager.snapshot(), profileManager.activeProfile()))
                else -> {
                    val answer = agent.ask(line)
                    println("=== PROMPT AUDIT ===")
                    println(promptBuilder.status(memoryManager.snapshot(), profileManager.activeProfile()))
                    println()
                    println(answer.warningOrError ?: answer.answer)
                }
            }
        }
    }

    private fun printProfileList() {
        val active = profileManager.activeProfile().id
        profileManager.listProfiles().forEach {
            val marker = if (it.id == active) "*" else "-"
            println("$marker ${it.id}: ${it.role}, ${it.style}")
        }
    }

    private fun updateProfile(args: String) {
        val key = args.substringBefore(" ").trim()
        val value = args.substringAfter(" ", "").trim()
        if (key.isBlank() || value.isBlank()) {
            println("Usage: /profile update <key> <value>")
            return
        }
        println(if (profileManager.updateActive(key, value)) "Updated $key" else "Unsupported key: $key")
    }

    private fun compareProfiles() {
        val question = "Объясни, как мне реализовать сохранение контекста в AI-агенте."
        profileManager.listProfiles().forEach { profile ->
            println("=== PROFILE: ${profile.id} ===")
            val answer = agent.askAs(profile, question)
            println(answer.warningOrError ?: answer.answer)
            println()
        }
    }
}
