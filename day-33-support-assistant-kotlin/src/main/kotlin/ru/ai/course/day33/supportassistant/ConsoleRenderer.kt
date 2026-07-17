package ru.ai.course.day33.supportassistant

object ConsoleRenderer {
    fun render(result: SupportAssistantResult): String = buildString {
        appendLine("QUESTION: ${result.question}")
        appendLine("TICKET:")
        val context = result.context
        if (context == null) {
            appendLine("  id=${result.ticketId} status=NOT_FOUND")
        } else {
            appendLine("  id=${context.ticket.id}")
            appendLine("  linkedUserId=${context.user.id}")
            appendLine("  category=${context.ticket.category}")
            appendLine("  productArea=${context.ticket.productArea}")
            appendLine("  errorCode=${context.ticket.errorCode}")
            appendLine("  accountState=${context.user.accountState}")
        }
        appendLine("MCP TOOLS USED: ${result.mcpToolsUsed.joinToString().ifBlank { "(none)" }}")
        appendLine("CURRENT CONTEXT FACTS:")
        context?.facts?.forEach { appendLine("  ${it.id} = ${it.value}") }
            ?: appendLine("  (none)")
        appendLine("RETRIEVED SOURCES:")
        result.evidence?.items?.forEach {
            appendLine("  ${it.chunk.sourceId} score=${"%.3f".format(it.score)}")
        } ?: appendLine("  (none)")
        if (result.evidence?.items?.isEmpty() == true) appendLine("  (none)")
        appendLine("ANSWER: ${result.response.answer}")
        appendLine("ACTION STEPS:")
        if (result.response.actionSteps.isEmpty()) {
            appendLine("  (none)")
        } else {
            result.response.actionSteps.forEachIndexed { index, step ->
                appendLine("  ${index + 1}. $step")
            }
        }
        appendLine(
            "KNOWLEDGE SOURCES: " +
                result.response.knowledgeSourceIds.joinToString().ifBlank { "(none)" },
        )
        appendLine(
            "CONTEXT FACT IDS: " +
                result.response.contextFactIds.joinToString().ifBlank { "(none)" },
        )
        result.response.clarifyingQuestion?.let { appendLine("CLARIFYING QUESTION: $it") }
        result.failureReason?.let { appendLine("SAFE FAILURE: $it") }
        append(
            "CHECK: grounded=${result.grounded}, " +
                "MCP context valid=${context?.let { it.ticket.userId == it.user.id } ?: true}, " +
                "current-ticket isolation valid=${result.currentTicketIsolationValid}, " +
                "LLM calls=${result.llmCalls}",
        )
    }
}
