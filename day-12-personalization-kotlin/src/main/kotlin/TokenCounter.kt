class ApproximateTokenCounter {
    fun countMessages(messages: List<ChatMessage>): Int = messages.sumOf { countMessage(it) }

    fun countMessage(message: ChatMessage): Int = countText(message.role) + countText(message.content) + 4

    fun countText(text: String): Int {
        if (text.isBlank()) return 0
        val words = text.trim().split(Regex("\\s+")).size
        val chars = text.length / 4
        return maxOf(1, maxOf(words, chars))
    }
}

class CostEstimator(
    private val prompt1kCostUsd: Double,
    private val completion1kCostUsd: Double,
) {
    fun estimate(promptTokens: Int?, completionTokens: Int?): Double? {
        if (promptTokens == null || completionTokens == null) return null
        if (prompt1kCostUsd == 0.0 && completion1kCostUsd == 0.0) return null
        return promptTokens / 1000.0 * prompt1kCostUsd + completionTokens / 1000.0 * completion1kCostUsd
    }
}
