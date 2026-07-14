package ru.ai.course.day31.developerassistant

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

object TextTools {
    private val tokenPattern = Regex("""[\p{L}\p{N}_./:-]+""")

    fun tokens(text: String): List<String> =
        tokenPattern.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    fun approxTokens(text: String): Int = ((text.length + 3) / 4).coerceAtLeast(1)

    fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun slug(text: String): String =
        tokens(text).take(8).joinToString("-").take(72).ifBlank { "root" }
}

fun cosineSimilarity(left: List<Double>, right: List<Double>): Double {
    if (left.size != right.size || left.isEmpty()) return 0.0
    var dot = 0.0
    var leftLength = 0.0
    var rightLength = 0.0
    for (index in left.indices) {
        dot += left[index] * right[index]
        leftLength += left[index] * left[index]
        rightLength += right[index] * right[index]
    }
    if (leftLength <= 0.0 || rightLength <= 0.0) return 0.0
    return dot / (sqrt(leftLength) * sqrt(rightLength))
}

fun normalizeL2(values: List<Double>): List<Double> {
    val length = sqrt(values.sumOf { it * it })
    return if (length <= 0.0) values else values.map { it / length }
}

fun String.singleLine(limit: Int = 300): String =
    replace('\n', ' ').replace('\r', ' ').trim().take(limit)
