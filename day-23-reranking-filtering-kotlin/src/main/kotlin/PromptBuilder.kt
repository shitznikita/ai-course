class PromptBuilder(private val config: AppConfig) {
    fun noRagMessages(question: String): List<ChatMessage> =
        listOf(
            ChatMessage(
                role = "system",
                content = "Ты отвечаешь кратко и честно. Если не знаешь точный факт из проекта, скажи, что не уверен.",
            ),
            ChatMessage(role = "user", content = question),
        )

    fun ragMessages(question: String, results: List<SearchResult>): List<ChatMessage> =
        listOf(
            ChatMessage(
                role = "system",
                content = buildString {
                    appendLine("Ты RAG-ассистент по локальной базе знаний проекта ai-course.")
                    appendLine("Отвечай только на основе блока CONTEXT.")
                    appendLine("Если в CONTEXT не хватает данных, явно скажи: данных в найденных источниках недостаточно.")
                    appendLine("В конце обязательно добавь раздел Sources со списком использованных source/section/chunk_id.")
                }.trim(),
            ),
            ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("QUESTION:")
                    appendLine(question)
                    appendLine()
                    appendLine("CONTEXT:")
                    appendLine(renderContext(results))
                    appendLine()
                    appendLine("Ответь по-русски. Сначала дай ответ, затем Sources.")
                },
            ),
        )

    fun renderPromptPreview(question: String, results: List<SearchResult>): String =
        ragMessages(question, results).joinToString("\n\n---\n\n") { message ->
            "${message.role.uppercase()}:\n${message.content}"
        }

    private fun renderContext(results: List<SearchResult>): String {
        val rendered = StringBuilder()
        var tokens = 0
        results.forEachIndexed { index, result ->
            val chunk = result.chunk
            val header = "[${index + 1}] source=${chunk.metadata.source}; title=${chunk.metadata.title}; section=${chunk.metadata.section}; chunk_id=${chunk.metadata.chunkId}; score=${result.score.formatDigits()}"
            val chunkTokens = Tokenizer.approxTokens(header) + chunk.metadata.approxTokens
            if (rendered.isNotBlank() && tokens + chunkTokens > config.ragMaxContextTokens) return@forEachIndexed
            rendered.appendLine(header)
            rendered.appendLine(chunk.text.trim())
            rendered.appendLine()
            tokens += chunkTokens
        }
        return rendered.toString().trim().ifBlank { "NO_CONTEXT_FOUND" }
    }
}
