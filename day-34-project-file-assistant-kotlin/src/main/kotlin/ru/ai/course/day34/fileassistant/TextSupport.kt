package ru.ai.course.day34.fileassistant

import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun boundedText(value: String, limit: Int): String =
    if (value.length <= limit) value else value.take(limit) + "…"

fun String.normalizedNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')

fun decodeProjectText(bytes: ByteArray): String {
    require(bytes.none { it == 0.toByte() }) { "Binary/NUL content is not allowed." }
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().normalizedNewlines()
}

fun canonicalJson(element: JsonElement): String = canonicalElement(element).toString()

private fun canonicalElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap().mapValues { (_, value) -> canonicalElement(value) })
    is JsonArray -> JsonArray(element.map(::canonicalElement))
    else -> element
}
