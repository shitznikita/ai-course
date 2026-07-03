import kotlinx.serialization.decodeFromString

class QuoteExtractor(private val config: AppConfig) {
    fun extract(
        question: String,
        retrieval: RerankedRetrieval,
        expectedAnswerPoints: List<String> = emptyList(),
    ): List<QuoteCandidate> {
        val queryText = buildString {
            append(question)
            append(' ')
            append(retrieval.rewrite.rewritten)
            append(' ')
            append(expectedAnswerPoints.joinToString(" "))
        }
        val queryTokens = Tokenizer.tokens(queryText)
            .filter { it.length > 2 }
            .distinct()

        val candidates = retrieval.selected.flatMapIndexed { chunkIndex, reranked ->
            val chunk = reranked.result.chunk
            bestFragments(chunk.text, queryTokens)
                .take(2)
                .mapIndexed { quoteIndex, fragment ->
                    val metadata = chunk.metadata
                    QuoteCandidate(
                        id = "q${chunkIndex + 1}.${quoteIndex + 1}",
                        source = metadata.source,
                        title = metadata.title,
                        section = metadata.section,
                        chunkId = metadata.chunkId,
                        text = trimQuote(fragment),
                        score = reranked.rerankScore,
                    )
                }
        }

        return candidates
            .filter { it.text.isNotBlank() }
            .distinctBy { "${it.source}:${it.chunkId}:${it.text}" }
            .sortedByDescending { it.score }
            .take(config.maxQuotes)
    }

    private fun bestFragments(text: String, queryTokens: List<String>): List<String> {
        val fragments = text
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.length >= 24 }
            .ifEmpty { listOf(text.trim()) }

        return fragments
            .map { fragment -> fragment to fragmentScore(fragment, queryTokens) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .map { it.first }
    }

    private fun fragmentScore(fragment: String, queryTokens: List<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val lower = fragment.lowercase()
        return queryTokens.count { lower.contains(it) }
    }

    private fun trimQuote(fragment: String): String {
        val trimmed = fragment.trim()
        if (trimmed.length <= config.quoteMaxChars) return trimmed
        return trimmed.take(config.quoteMaxChars).trimEnd()
    }
}

object GroundedAnswerParser {
    fun parse(raw: String): GroundedAnswer {
        val json = raw.substringAfter('{', missingDelimiterValue = raw)
            .substringBeforeLast('}', missingDelimiterValue = raw)
            .let { "{$it}" }
        return AppJson.compact.decodeFromString(json)
    }
}

class GroundingValidator {
    fun validate(
        answer: GroundedAnswer,
        retrieval: RerankedRetrieval,
        quoteCandidates: List<QuoteCandidate>,
        expectedAnswerPoints: List<String> = emptyList(),
    ): GroundingValidation {
        val errors = mutableListOf<String>()
        val statusAnswered = answer.status == "answered"
        val statusUnknown = answer.status == "unknown"
        val answerPresent = answer.answer.isNotBlank()
        val sourcesPresent = answer.sources.isNotEmpty()
        val quotesPresent = answer.quotes.isNotEmpty()
        val sourcesMatchRetrieved = answer.sources.all { source ->
            retrieval.selected.any { selected ->
                val metadata = selected.result.chunk.metadata
                metadata.source == source.source &&
                    metadata.section == source.section &&
                    metadata.chunkId == source.chunkId
            }
        }
        val quotesMatchChunks = answer.quotes.all { quote ->
            retrieval.selected.any { selected ->
                val chunk = selected.result.chunk
                val metadata = chunk.metadata
                metadata.source == quote.source &&
                    metadata.section == quote.section &&
                    metadata.chunkId == quote.chunkId &&
                    normalizedContains(chunk.text, quote.text)
            } || quoteCandidates.any { candidate ->
                candidate.source == quote.source &&
                    candidate.section == quote.section &&
                    candidate.chunkId == quote.chunkId &&
                    normalizedContains(candidate.text, quote.text)
            }
        }
        val covered = expectedAnswerPoints.count { point ->
            expectedPointCovered(point, answer, quoteCandidates)
        }
        val answerMatchesQuotes = expectedAnswerPoints.isEmpty() || covered == expectedAnswerPoints.size
        val unknownSaysDontKnow = !statusUnknown || answer.answer.lowercase().contains("не знаю")
        val clarifyingQuestionPresent = !statusUnknown || !answer.clarifyingQuestion.isNullOrBlank()

        if (!answerPresent) errors += "answer is blank"
        if (statusAnswered && !sourcesPresent) errors += "answered response has no sources"
        if (statusAnswered && !quotesPresent) errors += "answered response has no quotes"
        if (!sourcesMatchRetrieved) errors += "at least one source was not retrieved"
        if (!quotesMatchChunks) errors += "at least one quote does not match retrieved chunks"
        if (!answerMatchesQuotes) errors += "expected answer points are not covered by answer/quotes"
        if (!unknownSaysDontKnow) errors += "unknown response does not say 'не знаю'"
        if (!clarifyingQuestionPresent) errors += "unknown response has no clarifying question"

        return GroundingValidation(
            statusAnswered = statusAnswered,
            statusUnknown = statusUnknown,
            answerPresent = answerPresent,
            sourcesPresent = sourcesPresent,
            quotesPresent = quotesPresent,
            sourcesMatchRetrieved = sourcesMatchRetrieved,
            quotesMatchChunks = quotesMatchChunks,
            expectedPointsCovered = covered,
            expectedPointsTotal = expectedAnswerPoints.size,
            answerMatchesQuotes = answerMatchesQuotes,
            unknownSaysDontKnow = unknownSaysDontKnow,
            clarifyingQuestionPresent = clarifyingQuestionPresent,
            errors = errors,
        )
    }

    private fun expectedPointCovered(
        point: String,
        answer: GroundedAnswer,
        quoteCandidates: List<QuoteCandidate>,
    ): Boolean {
        val haystack = buildString {
            append(answer.answer)
            append(' ')
            append(answer.quotes.joinToString(" ") { it.text })
            append(' ')
            append(quoteCandidates.joinToString(" ") { it.text })
        }.lowercase()
        val tokens = Tokenizer.tokens(point)
            .filter { it.length > 2 }
            .distinct()
        if (tokens.isEmpty()) return true
        val hits = tokens.count { haystack.contains(it.lowercase()) }
        return hits >= 1 && hits.toDouble() / tokens.size >= 0.34
    }

    private fun normalizedContains(text: String, fragment: String): Boolean {
        val normalizedText = normalizeForMatch(text)
        val normalizedFragment = normalizeForMatch(fragment)
        return normalizedFragment.isNotBlank() && normalizedText.contains(normalizedFragment)
    }

    private fun normalizeForMatch(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()
}
