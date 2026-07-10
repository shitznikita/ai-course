interface AnswerGenerator {
    val kind: String
    val configuredModel: String

    fun generate(messages: List<PromptMessage>): ChatReply
}

fun ChatReply.toMetrics(kind: String): GenerationMetrics = GenerationMetrics(
    model = model,
    kind = kind,
    latencyMs = clientElapsedNanos / 1_000_000,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    outputTokensPerSecond = outputTokensPerSecond(),
)

fun ChatReply.outputTokensPerSecond(): Double? {
    val tokens = completionTokens ?: return null
    val duration = evalDurationNanos?.takeIf { it > 0 } ?: clientElapsedNanos.takeIf { it > 0 } ?: return null
    return tokens * 1_000_000_000.0 / duration
}
