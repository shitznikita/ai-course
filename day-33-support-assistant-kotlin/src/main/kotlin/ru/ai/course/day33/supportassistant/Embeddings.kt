package ru.ai.course.day33.supportassistant

import kotlin.math.sqrt

fun interface EmbeddingClient {
    fun embed(text: String): List<Double>
}

class HashEmbeddingClient(private val dimensions: Int) : EmbeddingClient {
    init {
        require(dimensions >= 64) { "Hash embedding dimensions must be at least 64." }
    }

    override fun embed(text: String): List<Double> {
        val vector = DoubleArray(dimensions)
        val tokens = TextTools.tokens(text)
        if (tokens.isEmpty()) return vector.toList()
        tokens.forEachIndexed { position, token ->
            val hash = TextTools.sha256(token)
            val bucket = hash.take(8).toLong(16).mod(dimensions.toLong()).toInt()
            val sign = if (hash.substring(8, 10).toInt(16) % 2 == 0) 1.0 else -1.0
            val weight = 1.0 + (position.coerceAtMost(20) / 100.0)
            vector[bucket] += sign * weight
        }
        val norm = sqrt(vector.sumOf { it * it })
        if (norm > 0.0) {
            vector.indices.forEach { vector[it] /= norm }
        }
        return vector.toList()
    }
}
