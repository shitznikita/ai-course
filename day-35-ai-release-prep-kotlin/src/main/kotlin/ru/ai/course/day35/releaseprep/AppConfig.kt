package ru.ai.course.day35.releaseprep

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer

object FixedProvider {
    const val URL = "https://api.eliza.yandex.net/openrouter/v1/chat/completions"
    const val MODEL = "meta-llama/llama-3.3-70b-instruct"
    const val AUTH = "OAuth"

    fun preflight(environment: NamedEnvironment = SystemNamedEnvironment): ProviderConfig {
        requireExact(environment.get("LLM_API_URL"), URL, "LLM_API_URL")
        requireExact(environment.get("LLM_MODEL"), MODEL, "LLM_MODEL")
        requireExact(environment.get("LLM_AUTH_SCHEME"), AUTH, "LLM_AUTH_SCHEME")
        val uri = URI(URL)
        require(
            uri.scheme == "https" && uri.host == "api.eliza.yandex.net" && uri.port == -1 &&
                uri.rawPath == "/openrouter/v1/chat/completions" && uri.rawQuery == null &&
                uri.rawFragment == null && uri.userInfo == null,
        ) { "Pinned provider URI is invalid" }
        return ProviderConfig(uri, MODEL, AUTH)
    }

    private fun requireExact(actual: String?, expected: String, name: String) {
        require(actual == null || actual == expected) { "$name override is forbidden" }
    }
}

data class ProviderConfig(val uri: URI, val model: String, val authScheme: String)

fun interface NamedEnvironment {
    fun get(name: String): String?
}

object SystemNamedEnvironment : NamedEnvironment {
    override fun get(name: String): String? = System.getenv(name)
}

fun interface CredentialSource {
    fun load(): String
}

class FileCredentialSource(
    private val repository: Path,
    private val environment: NamedEnvironment = SystemNamedEnvironment,
    private val onRead: () -> Unit = {},
) : CredentialSource {
    override fun load(): String {
        onRead()
        environment.get("LLM_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            ContentPolicy.requireSafeSecret(it)
            return it
        }
        val candidates = listOf(
            repository.resolve("day-35-ai-release-prep-kotlin/.env"),
            repository.resolve("day-01-llm-rest-kotlin/.env"),
        )
        for (candidate in candidates) {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue
            val text = SecureFiles.readRegularContained(repository, candidate, 16 * 1024)
            val entries = linkedMapOf<String, String>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val match = Regex("""(?:export\s+)?([A-Z][A-Z0-9_]*)=(.*)""").matchEntire(line)
                    ?: error("Malformed .env line ${index + 1}")
                require(entries.put(match.groupValues[1], match.groupValues[2].trim().trim('"', '\'')) == null) {
                    "Duplicate .env key ${match.groupValues[1]}"
                }
            }
            entries["LLM_API_KEY"]?.takeIf { it.isNotBlank() }?.let {
                ContentPolicy.requireSafeSecret(it)
                return it
            }
        }
        error("LLM_API_KEY is unavailable")
    }
}

/**
 * Disclosure policy for the bounded reviewed brief and model-controlled values.
 *
 * Repository source is outside this boundary. Sink values must already be NFKC, must not contain
 * Unicode FORMAT characters, and are then inspected for explicit credential assignments, call
 * arguments and authorization-scheme values. No source-language callee or wrapper grammar exists.
 */
object ContentPolicy {
    private const val NORMALIZATION_PASSES = 6
    private val placeholders = setOf(
        "<token>",
        "<api-key>",
        "<password>",
        "replace-with-oauth-token",
        "YOUR_TOKEN",
        "YOUR_API_KEY",
        "YOUR_PASSWORD",
    )
    private val placeholderPattern = placeholders
        .sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it) }
    private val directSecretPatterns = listOf(
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:sk|ghp|github_pat)_[A-Za-z0-9_$-]{12,}\b""", RegexOption.IGNORE_CASE),
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"""),
    )
    private const val AUTHORIZATION_MARKER_PATTERN =
        """(?:proxy[-_]?|x[-_]?)?(?:authorization|authoriz(?:\\u[^\\\s]{0,4}?|\\U[^\\\s]{0,8}?|\\x[^\\\s]{0,2}?)ation)"""
    private val sensitiveMarker = Regex(
        """(?i)(?<![A-Za-z0-9_])(?:$AUTHORIZATION_MARKER_PATTERN|llm[-_]?api[-_]?key|aws[-_]?secret[-_]?access[-_]?key|secret[-_]?access[-_]?key|secret[-_]?key|private[-_]?key|access[-_]?key[-_]?id|access[-_]?token|oauth[-_]?token|refresh[-_]?token|client[-_]?secret|api[-_]?key|password|passphrase|token|secret)(?![A-Za-z0-9_])""",
    )
    private val authorizationMarker = Regex(
        """(?i)(?<![A-Za-z0-9_])$AUTHORIZATION_MARKER_PATTERN(?![A-Za-z0-9_])""",
    )
    private val authScheme = Regex("""(?i)\b(?:OAuth|Bearer|Basic)\b""")
    private val assignmentPrefix = Regex(
        """(?is)^\s*(?:["'\]\)}]+\s*)*(?:(?:/\*.*?\*/|//[^\r\n]*(?:\r?\n|$)|--[^\r\n]*(?:\r?\n|$)|#[^\r\n]*(?:\r?\n|$))\s*)*(?::|=|\bto\b)""",
    )
    private val quotedValue = Regex("[\"']([^\"'\\t\\r\\n]{1,512})[\"']")
    private val safeCredential = Regex(
        """(?is)^\s*["']?(?:$placeholderPattern)["']*\s*(?:[,;}\])]|$)""",
    )
    private val safeAuthorization = Regex(
        """(?is)^\s*["']?(?:OAuth|Bearer|Basic)\s+(?:$placeholderPattern)["']*\s*(?:[,;}\])]|$)""",
    )

    fun validateTransportText(value: String, label: String, maxBytes: Int) {
        require(utf8Bytes(value) <= maxBytes) { "$label exceeds $maxBytes UTF-8 bytes" }
        require(value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "$label contains control characters"
        }
    }

    fun validateText(value: String, label: String, maxBytes: Int) {
        validateTransportText(value, label, maxBytes)
        requireNoFormat(value, label)
        require(value == canonicalForm(value)) { "$label must be NFKC-canonical" }
        val inspected = inspectionForm(value)
        validateTransportText(inspected, label, maxBytes * 2)
        requireNoFormat(inspected, label)
        require(directSecretPatterns.none { it.containsMatchIn(inspected) } && !containsDisclosure(inspected)) {
            "$label appears to contain a credential value"
        }
    }

    fun canonicalForm(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)

    private fun inspectionForm(value: String): String {
        var current = canonicalForm(value)
        repeat(NORMALIZATION_PASSES) {
            val next = canonicalForm(decodeQuoteEscapes(decodeCompleteEscapes(current)))
            if (next == current) return current
            current = next
        }
        require(canonicalForm(decodeQuoteEscapes(decodeCompleteEscapes(current))) == current) {
            "Too many encoded input layers"
        }
        return current
    }

    private fun decodeQuoteEscapes(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && value.getOrNull(index + 1) in setOf('"', '\'')) {
                append(value[index + 1])
                index += 2
            } else {
                append(value[index++])
            }
        }
    }

    private fun decodeCompleteEscapes(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            if (value[index] != '\\') {
                append(value[index++])
                continue
            }
            val marker = value.getOrNull(index + 1)
            if (marker == '\\' && value.getOrNull(index + 2) in setOf('u', 'U', 'x', 'X')) {
                append('\\')
                index += 2
                continue
            }
            val digits = when (marker) {
                'u' -> 4
                'U' -> 8
                'x', 'X' -> 2
                else -> 0
            }
            if (digits > 0) {
                val raw = value.substringOrNull(index + 2, index + 2 + digits)
                val decoded = raw?.takeIf { it.all(::isHexDigit) }?.toLongOrNull(16)
                if (decoded != null && decoded <= Character.MAX_CODE_POINT &&
                    decoded !in Character.MIN_SURROGATE.code.toLong()..Character.MAX_SURROGATE.code.toLong()
                ) {
                    appendCodePoint(decoded.toInt())
                    index += digits + 2
                    continue
                }
            }
            if (marker in '0'..'7') {
                var end = index + 1
                while (end < value.length && end - index <= 3 && value[end] in '0'..'7') end++
                append(value.substring(index + 1, end).toInt(8).toChar())
                index = end
                continue
            }
            append('\\')
            index++
        }
    }

    private fun containsDisclosure(value: String): Boolean =
        hasUnsafeAssignedValue(value) || hasUnsafeCallValue(value) || hasUnsafeAuthSchemeValue(value)

    private fun hasUnsafeAssignedValue(value: String): Boolean = sensitiveMarker.findAll(value).any { marker ->
        val suffix = value.substring(marker.range.last + 1)
        val assignment = assignmentPrefix.find(suffix) ?: return@any false
        val assigned = suffix.substring(assignment.range.last + 1)
        val authorization = authorizationMarker.matches(marker.value)
        if (authorization) !safeAuthorization.containsMatchIn(assigned) else !safeCredential.containsMatchIn(assigned)
    }

    private fun hasUnsafeCallValue(value: String): Boolean = sensitiveMarker.findAll(value).any { marker ->
        val suffix = value.substring(marker.range.last + 1).take(768)
        val first = suffix.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return@any false
        val structuralStart = suffix[first] in setOf(',', '.', '?', '"', '\'', ']', ')', '}') ||
            suffix.substring(first).startsWith("as ")
        if (!structuralStart) return@any false
        val comma = suffix.indexOf(',')
        if (comma < 0) return@any false
        val afterComma = suffix.substring(comma + 1)
        val terminator = afterComma.indexOfAny(charArrayOf(')', ']', '}', '\n', '\r'))
        val segment = if (terminator < 0) afterComma else afterComma.substring(0, terminator + 1)
        val literals = quotedValue.findAll(segment).map { it.groupValues[1] }.toList()
        if (literals.isEmpty()) return@any false
        val authorization = authorizationMarker.matches(marker.value)
        literals.any { literal ->
            if (authorization) !safeAuthorization.matches(literal) else !safeCredential.matches(literal)
        }
    }

    private fun hasUnsafeAuthSchemeValue(value: String): Boolean = authScheme.findAll(value).any { scheme ->
        val previous = previousNonWhitespace(value, scheme.range.first - 1)
        val explicitContext = previous == null || previous in setOf(':', '=', ',', '(', '[', '{', '"', '\'')
        if (!explicitContext) return@any false
        val remainder = value.substring(scheme.range.first)
        val afterScheme = value.substring(scheme.range.last + 1)
        val next = afterScheme.indexOfFirst { !it.isWhitespace() }
        if (next < 0 || afterScheme[next] in ",;:)}]\"'") return@any false
        !safeAuthorization.containsMatchIn(remainder)
    }

    private fun previousNonWhitespace(value: String, start: Int): Char? {
        for (index in start downTo 0) if (!value[index].isWhitespace()) return value[index]
        return null
    }

    private fun requireNoFormat(value: String, label: String) {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            require(Character.getType(codePoint) != Character.FORMAT.toInt()) {
                "$label contains Unicode FORMAT characters"
            }
            index += Character.charCount(codePoint)
        }
    }

    fun validatePath(path: String) {
        validateText(path, "path", Limits.MAX_PATH_BYTES)
        require(path.isNotBlank()) { "Blank path is forbidden" }
        require(path.none(Char::isISOControl))
        require(!path.startsWith("/") && !path.startsWith("-")) { "Absolute/option path is forbidden" }
        val parts = path.split('/')
        require(parts.isNotEmpty() && parts.none { it.isEmpty() || it == "." || it == ".." }) {
            "Malformed path"
        }
    }

    fun requireSafeSecret(secret: String) {
        require(secret.length in 8..16_384 && secret.none { it.isWhitespace() || it.isISOControl() }) {
            "Credential is malformed"
        }
        require(listOf("replace-with", "placeholder", "your_").none { it in secret.lowercase() }) {
            "Credential placeholder must be replaced"
        }
    }

    private fun String.substringOrNull(start: Int, end: Int): String? =
        takeIf { start >= 0 && end >= start && end <= length }?.substring(start, end)

    private fun isHexDigit(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'
}

object SecureFiles {
    fun readRegularContained(rootInput: Path, fileInput: Path, cap: Int): String {
        val root = rootInput.toRealPath()
        val normalized = fileInput.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Path escapes repository" }
        var current = root
        root.relativize(normalized).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Symlink component is forbidden: $current" }
        }
        require(Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) { "Regular file required: $normalized" }
        val size = Files.size(normalized)
        require(size <= cap) { "File exceeds $cap bytes" }
        val bytes = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes(cap + 1).also { require(it.size <= cap) { "File exceeds $cap bytes" } }
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
