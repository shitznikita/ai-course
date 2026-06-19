class PromptBuilder(
    private val tokenCounter: ApproximateTokenCounter,
    private val shortTermMessagesLimit: Int,
) {
    private val systemPrompt = """
        Ты MemoryLayersAgent, помощник по сбору технического задания для мобильного приложения.
        Используй только явно предоставленные слои памяти и текущее сообщение пользователя.
        Не выдумывай факты: если данных нет в памяти, напиши, что нужно уточнить.
        Открытые вопросы из working memory считаются нерешенными: не превращай их в решения.
        Если авторизация есть только в open questions, пиши "Авторизация: открытый вопрос", а не "с авторизацией" или "без авторизации".
        Отвечай кратко, структурированно и пригодно для следующего шага по ТЗ.
    """.trimIndent()

    fun build(snapshot: MemorySnapshot, currentUserMessage: String): PromptBuildResult {
        val audits = mutableListOf<PromptLayerAudit>()
        val messages = mutableListOf(ChatMessage("system", systemPrompt))
        audits += auditText("system", included = true, details = "role and behavior rules", text = systemPrompt)

        val longBlock = snapshot.longTerm.toPromptBlock()
        messages += ChatMessage(
            "system",
            """
            LONG-TERM MEMORY
            Stable user profile, global preferences, and rules for all chats.

            $longBlock
            """.trimIndent(),
        )
        audits += auditText("long-term", included = true, details = longBlock.shortDetails(), text = longBlock)

        val workingBlock = snapshot.working.toPromptBlock()
        messages += ChatMessage(
            "system",
            """
            WORKING MEMORY
            Current task context, stage, decisions, constraints, questions, and next steps.
            Open questions are unresolved and must be listed as questions, not decisions.
            Never convert an unresolved question into a yes/no requirement.

            $workingBlock
            """.trimIndent(),
        )
        audits += auditText("working", included = true, details = workingBlock.shortDetails(), text = workingBlock)

        val recent = snapshot.shortTerm.messages.takeLast(shortTermMessagesLimit)
        messages += recent
        audits += PromptLayerAudit(
            layer = "short-term",
            included = true,
            tokens = tokenCounter.countMessages(recent),
            details = "recent messages sent=${recent.size}, stored=${snapshot.shortTerm.messages.size}",
        )

        val currentMessage = ChatMessage("user", currentUserMessage)
        messages += currentMessage
        audits += PromptLayerAudit(
            layer = "current-user-message",
            included = true,
            tokens = tokenCounter.countMessage(currentMessage),
            details = "latest user input",
        )

        return PromptBuildResult(
            messagesForApi = messages,
            audits = audits,
            promptTokens = tokenCounter.countMessages(messages),
            recentMessagesSent = recent.size,
        )
    }

    fun status(snapshot: MemorySnapshot): String {
        val preview = build(snapshot, "[next user message]")
        return buildString {
            appendLine("Layers selected for next prompt: system, long-term, working, short-term, current-user-message")
            appendLine("Short-term limit: $shortTermMessagesLimit messages")
            appendLine("Prompt token estimate with placeholder: ${preview.promptTokens}")
            preview.audits.forEach { audit ->
                appendLine("- ${audit.layer}: included=${audit.included}, tokens=${audit.tokens}, ${audit.details}")
            }
        }.trimEnd()
    }

    private fun auditText(layer: String, included: Boolean, details: String, text: String): PromptLayerAudit {
        return PromptLayerAudit(
            layer = layer,
            included = included,
            tokens = tokenCounter.countText(text),
            details = details,
        )
    }

    private fun String.shortDetails(): String {
        val lines = lines().filter { it.isNotBlank() }
        return if (lines.size <= 2) lines.joinToString("; ") else lines.take(2).joinToString("; ") + "; ..."
    }
}
