class PromptBuilder(private val config: AppConfig) {
    fun groundedMessages(
        question: String,
        retrieval: RerankedRetrieval,
        quoteCandidates: List<QuoteCandidate>,
    ): List<ChatMessage> =
        listOf(
            ChatMessage(
                role = "system",
                content = buildString {
                    appendLine("Ты grounded RAG-ассистент по локальной базе знаний проекта ai-course.")
                    appendLine("Отвечай только на основе CONTEXT и QUOTE_CANDIDATES.")
                    appendLine("Верни только валидный JSON без Markdown и без пояснений вокруг.")
                    appendLine("Схема JSON:")
                    appendLine("""{"status":"answered|unknown","answer":"...","sources":[{"source":"...","section":"...","chunk_id":"..."}],"quotes":[{"quote_id":"q1.1","source":"...","section":"...","chunk_id":"...","text":"точная цитата"}],"clarifyingQuestion":null}""")
                    appendLine("Если контекста недостаточно, верни status=\"unknown\", answer=\"не знаю\", пустые sources/quotes и clarifyingQuestion.")
                    appendLine("Каждая quote.text должна быть дословно скопирована из QUOTE_CANDIDATES.")
                    appendLine("Каждый source должен соответствовать использованной цитате.")
                }.trim(),
            ),
            ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("QUESTION:")
                    appendLine(question)
                    appendLine()
                    appendLine("RELEVANCE:")
                    appendLine("max_score=${retrieval.selected.maxOfOrNull { it.rerankScore }?.formatDigits() ?: "0.000"}; min_required=${config.answerMinRelevance.formatDigits()}")
                    appendLine()
                    appendLine("QUOTE_CANDIDATES:")
                    appendLine(renderQuoteCandidates(quoteCandidates))
                    appendLine()
                    appendLine("CONTEXT:")
                    appendLine(renderContext(retrieval.selected.map { it.toSearchResult() }))
                    appendLine()
                    appendLine("Ответь по-русски. Не используй знания вне CONTEXT.")
                },
            ),
        )

    fun renderGroundedPromptPreview(
        question: String,
        retrieval: RerankedRetrieval,
        quoteCandidates: List<QuoteCandidate>,
    ): String =
        groundedMessages(question, retrieval, quoteCandidates).joinToString("\n\n---\n\n") { message ->
            "${message.role.uppercase()}:\n${message.content}"
        }

    private fun renderQuoteCandidates(quoteCandidates: List<QuoteCandidate>): String =
        quoteCandidates.joinToString("\n") { quote ->
            "[${quote.id}] source=${quote.source}; section=${quote.section}; chunk_id=${quote.chunkId}; score=${quote.score.formatDigits()}\n${quote.text}"
        }.ifBlank { "NO_QUOTES_FOUND" }

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
