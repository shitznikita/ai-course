import kotlin.math.ceil
import kotlin.math.max

class ApproximateTokenCounter {
    private val tokenLikePattern = Regex("""[\p{L}\p{N}_]+|[^\s]""")

    fun countText(text: String): Int {
        if (text.isBlank()) return 0
        val lexicalEstimate = tokenLikePattern.findAll(text).count()
        val charEstimate = ceil(text.length / 4.0).toInt()
        return max(1, max(lexicalEstimate, charEstimate))
    }

    fun countMessage(message: ChatMessage): Int {
        val chatOverhead = 4
        return chatOverhead + countText(message.role) + countText(message.content)
    }

    fun countMessages(messages: List<ChatMessage>): Int {
        val assistantPrimerOverhead = 3
        return assistantPrimerOverhead + messages.sumOf { countMessage(it) }
    }
}

class CostEstimator(
    private val inputPricePerMillionTokens: Double?,
    private val outputPricePerMillionTokens: Double?,
) {
    fun label(usage: ApiUsage?): String {
        val apiCost = usage?.costUsd
        if (apiCost != null) return "$" + moneyFormat.format(apiCost)

        val inputPrice = inputPricePerMillionTokens ?: return "unknown"
        val outputPrice = outputPricePerMillionTokens ?: return "unknown"
        val prompt = usage?.promptTokens ?: return "unknown"
        val completion = usage.completionTokens ?: return "unknown"
        val cost = prompt / 1_000_000.0 * inputPrice + completion / 1_000_000.0 * outputPrice
        return "$" + moneyFormat.format(cost)
    }
}
