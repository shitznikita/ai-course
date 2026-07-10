import kotlinx.serialization.decodeFromString

object GroundedAnswerParser {
    fun parse(raw: String): GroundedAnswer {
        val normalized = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val firstBrace = normalized.indexOf('{')
        val lastBrace = normalized.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw OllamaProtocolException("Model response does not contain a JSON object.")
        }
        return try {
            AppJson.compact.decodeFromString(normalized.substring(firstBrace, lastBrace + 1))
        } catch (error: Exception) {
            throw OllamaProtocolException("Model response does not match the grounded answer schema.")
        }
    }
}

class GroundingValidator {
    fun validate(
        answer: GroundedAnswer,
        retrieval: RetrievalPackage,
        expectedAnswerPoints: List<String> = emptyList(),
        expectedSources: List<String> = emptyList(),
    ): GroundingValidation {
        val errors = mutableListOf<String>()
        val statusValid = answer.status in setOf("answered", "unknown")
        val answerPresent = answer.answer.trim().isNotEmpty()
        val selected = retrieval.selected
        val answered = answer.status == "answered"
        val unknown = answer.status == "unknown"
        val sourcesPresent = !answered || answer.sources.isNotEmpty()
        val quotesPresent = !answered || answer.quotes.isNotEmpty()

        fun sourceMatches(source: CitationSource): Boolean = selected.any { result ->
            val metadata = result.chunk.metadata
            metadata.source == source.source &&
                metadata.section == source.section &&
                metadata.chunkId == source.chunkId
        }

        val sourcesMatch = answer.sources.all(::sourceMatches)
        val quotesMatch = answer.quotes.all { quote ->
            selected.any { result ->
                val metadata = result.chunk.metadata
                metadata.source == quote.source &&
                    metadata.section == quote.section &&
                    metadata.chunkId == quote.chunkId &&
                    normalizedContains(result.chunk.text, quote.text)
            }
        }

        val expectedSourcesCovered = expectedSources.count { expected ->
            answer.sources.any { it.source == expected }
        }
        val expectedPointsCovered = expectedAnswerPoints.count { point ->
            expectedPointCovered(point, answer)
        }
        val unknownBehaviorValid = !unknown || (
            answer.sources.isEmpty() &&
                answer.quotes.isEmpty() &&
                answer.answer.lowercase().contains("не знаю") &&
                !answer.clarifyingQuestion.isNullOrBlank()
            )

        if (!statusValid) errors += "status must be answered or unknown"
        if (!answerPresent) errors += "answer is blank"
        if (!sourcesPresent) errors += "answered response has no sources"
        if (!quotesPresent) errors += "answered response has no quotes"
        if (!sourcesMatch) errors += "at least one source was not retrieved"
        if (!quotesMatch) errors += "at least one quote is not a verbatim retrieved fragment"
        if (!unknownBehaviorValid) errors += "unknown response must say 'не знаю', have no citations, and ask a clarifying question"

        return GroundingValidation(
            validJsonContract = true,
            statusValid = statusValid,
            answerPresent = answerPresent,
            sourcesPresentWhenAnswered = sourcesPresent,
            quotesPresentWhenAnswered = quotesPresent,
            sourcesMatchRetrieved = sourcesMatch,
            quotesMatchChunks = quotesMatch,
            expectedSourcesCovered = expectedSourcesCovered,
            expectedSourcesTotal = expectedSources.size,
            expectedPointsCovered = expectedPointsCovered,
            expectedPointsTotal = expectedAnswerPoints.size,
            unknownBehaviorValid = unknownBehaviorValid,
            errors = errors,
        )
    }

    private fun expectedPointCovered(point: String, answer: GroundedAnswer): Boolean {
        val pointTokens = Tokenizer.words(point).filter { it.length > 2 }.distinct()
        if (pointTokens.isEmpty()) return true
        val answerText = buildString {
            append(answer.answer)
            append(' ')
            append(answer.quotes.joinToString(" ") { it.text })
        }.lowercase()
        val hits = pointTokens.count { answerText.contains(it) }
        return hits.toDouble() / pointTokens.size >= 0.5
    }

    private fun normalizedContains(text: String, quote: String): Boolean {
        val normalizedQuote = quote.replace(Regex("\\s+"), " ").trim()
        val normalizedText = text.replace(Regex("\\s+"), " ").trim()
        return normalizedQuote.isNotBlank() && normalizedText.contains(normalizedQuote)
    }
}
