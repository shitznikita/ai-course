package ru.ai.course.day33.supportassistant

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

object TextTools {
    private val tokenRegex = Regex("""[\p{L}\p{N}_-]+""")
    private val unsafeControl = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
    private val commonEndings = listOf(
        "иями", "ями", "ами", "ого", "ему", "ому", "ать", "ять", "ить",
        "ией", "иям", "иях", "иях", "ение", "ений", "ение", "ость",
        "ия", "ию", "ии", "ый", "ий", "ая", "ое", "ые", "ов", "ев",
        "ам", "ям", "ах", "ях", "ом", "ем", "ы", "и", "а", "я", "у", "ю",
    )

    fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(unsafeControl, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun tokens(value: String): List<String> = tokenRegex.findAll(normalize(value))
        .map { stem(it.value) }
        .filter { it.length >= 2 }
        .toList()

    fun stem(token: String): String {
        if (token.length <= 4 || token.any(Char::isDigit) || '_' in token || '-' in token) return token
        val ending = commonEndings.firstOrNull { token.endsWith(it) && token.length - it.length >= 3 }
        return if (ending == null) token else token.dropLast(ending.length)
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun slug(value: String): String = normalize(value)
        .replace(Regex("""[^\p{L}\p{N}]+"""), "-")
        .trim('-')
        .take(80)
        .ifBlank { "section" }

    fun bounded(value: String, maxChars: Int, label: String): String {
        val clean = value.replace(unsafeControl, " ").trim()
        require(clean.isNotBlank()) { "$label must not be blank." }
        require(clean.length <= maxChars) { "$label exceeds $maxChars characters." }
        return clean
    }

    fun cosine(left: List<Double>, right: List<Double>): Double {
        require(left.size == right.size) { "Embedding dimensions differ." }
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }

    fun utf8Bytes(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    fun readUtf8Bounded(path: Path, maxBytes: Int, label: String): String {
        require(maxBytes > 0) { "$label byte limit must be positive." }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "$label does not exist or is a symlink: $path"
        }
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        return Files.newByteChannel(path, options).use { channel ->
            val size = channel.size()
            require(size in 0..maxBytes.toLong()) { "$label exceeds $maxBytes bytes." }
            val output = ByteArrayOutputStream(minOf(size.toInt(), 16_384))
            val buffer = ByteBuffer.allocate(8_192)
            var total = 0
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= maxBytes) { "$label exceeds $maxBytes bytes." }
                output.write(buffer.array(), 0, read)
            }
            output.toString(StandardCharsets.UTF_8)
        }
    }
}
