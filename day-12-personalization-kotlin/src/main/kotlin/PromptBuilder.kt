class PromptBuilder(
    private val tokenCounter: ApproximateTokenCounter,
    private val shortTermMessagesLimit: Int,
) {
    private val systemPrompt = """
        Ты PersonalizedRequirementsAgent, CLI-помощник по AI-агентам и ТЗ мобильных приложений.
        Учитывай только активный профиль пользователя, а не все сохраненные профили.
        Не выдумывай факты: если данных нет в памяти или профиле, попроси уточнение.
        Секреты, OAuth-токены и API-ключи нельзя хранить в профиле или печатать в ответе.
        Отвечай на языке, заданном активным профилем.
    """.trimIndent()

    fun build(snapshot: MemorySnapshot, profile: UserProfile, currentUserMessage: String): PromptBuildResult {
        val messages = mutableListOf<ChatMessage>()
        val audits = mutableListOf<PromptAudit>()

        messages += ChatMessage("system", systemPrompt)
        audits += audit("system", systemPrompt, "role, safety, personalization rules")

        val longBlock = snapshot.longTerm.toPromptBlock()
        messages += ChatMessage("system", "LONG-TERM MEMORY\n$longBlock")
        audits += audit("long-term", longBlock, longBlock.shortDetails())

        val profileBlock = profile.toPromptBlock()
        messages += ChatMessage("system", "ACTIVE USER PROFILE\n$profileBlock")
        audits += audit("active-profile", profileBlock, "id=${profile.id}, style=${profile.style}, format=${profile.format}")

        val workingBlock = snapshot.working.toPromptBlock()
        messages += ChatMessage("system", "WORKING MEMORY\n$workingBlock")
        audits += audit("working", workingBlock, workingBlock.shortDetails())

        val recent = snapshot.shortTerm.messages.takeLast(shortTermMessagesLimit)
        messages += recent
        audits += PromptAudit("short-term", true, tokenCounter.countMessages(recent), "recent messages sent=${recent.size}")

        val current = ChatMessage("user", currentUserMessage)
        messages += current
        audits += PromptAudit("current-user-message", true, tokenCounter.countMessage(current), "latest user input")

        return PromptBuildResult(
            messagesForApi = messages,
            audits = audits,
            promptTokens = tokenCounter.countMessages(messages),
            activeProfileId = profile.id,
        )
    }

    fun status(snapshot: MemorySnapshot, profile: UserProfile): String {
        val preview = build(snapshot, profile, "[next user message]")
        return buildString {
            appendLine("Layers selected for next prompt: system, long-term, active-profile, working, short-term, current-user-message")
            appendLine("Active profile: ${profile.id}")
            appendLine("Prompt token estimate: ${preview.promptTokens}")
            preview.audits.forEach { appendLine("- ${it.layer}: included=${it.included}, tokens=${it.tokens}, ${it.details}") }
        }.trimEnd()
    }

    private fun audit(layer: String, text: String, details: String): PromptAudit =
        PromptAudit(layer, true, tokenCounter.countText(text), details)

    private fun String.shortDetails(): String {
        val lines = lines().filter { it.isNotBlank() }
        return if (lines.size <= 2) lines.joinToString("; ") else lines.take(2).joinToString("; ") + "; ..."
    }
}
