import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

object Tokenizer {
    private val tokenRegex = Regex("""[\p{L}\p{N}_#+./:-]+""")

    fun tokens(text: String): List<String> =
        tokenRegex.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    fun approxTokens(text: String): Int = tokens(text).size

    fun tokenWindows(text: String, size: Int): List<String> {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return emptyList()
        val windows = mutableListOf<String>()
        var start = 0
        while (start < tokens.size) {
            val end = (start + size).coerceAtMost(tokens.size)
            windows += tokens.subList(start, end).joinToString(" ")
            if (end == tokens.size) break
            start = end
        }
        return windows
    }
}

fun normalizeL2(values: List<Double>): List<Double> {
    val norm = sqrt(values.sumOf { it * it })
    if (norm == 0.0) return values
    return values.map { it / norm }
}

fun cosineSimilarity(left: List<Double>, right: List<Double>): Double {
    val size = minOf(left.size, right.size)
    if (size == 0) return 0.0
    return (0 until size).sumOf { left[it] * right[it] }
}

fun stableId(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
    parts.forEach { part ->
        digest.update(part.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
}

fun String.shortPreview(limit: Int = 240): String =
    replace(Regex("\\s+"), " ").trim().let {
        if (it.length <= limit) it else it.take(limit - 1) + "..."
    }

fun Double.formatDigits(count: Int = 3): String = "%.${count}f".format(Locale.US, this)
