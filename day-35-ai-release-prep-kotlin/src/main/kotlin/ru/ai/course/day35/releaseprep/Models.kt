package ru.ai.course.day35.releaseprep
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
object AppJson {
    val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    }
    val provider = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun <T> decodeStrict(serializer: DeserializationStrategy<T>, text: String, label: String): T {
        rejectDuplicateKeys(text, label)
        return runCatching { strict.decodeFromString(serializer, text) }
            .getOrElse { error("$label JSON is invalid") }
    }

    fun rejectDuplicateKeys(text: String, label: String) = JsonDuplicateKeyGuard.validate(text, label)
}

private object JsonDuplicateKeyGuard {
    fun validate(text: String, label: String) = Parser(text, label).validate()

    private class Parser(private val text: String, private val label: String) {
        private var index = 0

        fun validate() {
            skipWhitespace()
            parseValue(0)
            skipWhitespace()
            require(index == text.length) { "$label JSON has trailing data" }
        }

        private fun parseValue(depth: Int) {
            require(depth <= 64) { "$label JSON nesting is too deep" }
            skipWhitespace()
            when (text.getOrNull(index)) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> consumeLiteral("true")
                'f' -> consumeLiteral("false")
                'n' -> consumeLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> error("$label JSON value is invalid")
            }
        }

        private fun parseObject(depth: Int) {
            require(text[index++] == '{')
            skipWhitespace()
            if (consumeIf('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                skipWhitespace()
                require(text.getOrNull(index) == '"') { "$label JSON object key is invalid" }
                val key = parseString()
                require(keys.add(key)) { "$label contains a duplicate JSON object key" }
                skipWhitespace()
                require(consumeIf(':')) { "$label JSON object is missing ':'" }
                parseValue(depth)
                skipWhitespace()
                if (consumeIf('}')) return
                require(consumeIf(',')) { "$label JSON object is missing ','" }
            }
        }

        private fun parseArray(depth: Int) {
            require(text[index++] == '[')
            skipWhitespace()
            if (consumeIf(']')) return
            while (true) {
                parseValue(depth)
                skipWhitespace()
                if (consumeIf(']')) return
                require(consumeIf(',')) { "$label JSON array is missing ','" }
            }
        }

        private fun parseString(): String {
            require(text[index++] == '"')
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> appendEscape(result)
                    char.code < 0x20 -> error("$label JSON string contains a control character")
                    else -> result.append(char)
                }
            }
            error("$label JSON string is unterminated")
        }

        private fun appendEscape(result: StringBuilder) {
            val escaped = text.getOrNull(index++) ?: error("$label JSON escape is incomplete")
            when (escaped) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> appendUnicodeEscape(result)
                else -> error("$label JSON escape is invalid")
            }
        }

        private fun appendUnicodeEscape(result: StringBuilder) {
            val first = readHexQuad()
            if (first.code in 0xD800..0xDBFF) {
                require(text.getOrNull(index) == '\\' && text.getOrNull(index + 1) == 'u') {
                    "$label JSON high surrogate is unpaired"
                }
                index += 2
                val second = readHexQuad()
                require(second.code in 0xDC00..0xDFFF) { "$label JSON low surrogate is invalid" }
                result.appendCodePoint(Character.toCodePoint(first, second))
            } else {
                require(first.code !in 0xDC00..0xDFFF) { "$label JSON low surrogate is unpaired" }
                result.append(first)
            }
        }

        private fun readHexQuad(): Char {
            require(index + 4 <= text.length) { "$label JSON Unicode escape is incomplete" }
            val raw = text.substring(index, index + 4)
            require(raw.all(::isHexDigit)) { "$label JSON Unicode escape is invalid" }
            index += 4
            return raw.toInt(16).toChar()
        }

        private fun parseNumber() {
            consumeIf('-')
            when (text.getOrNull(index)) {
                '0' -> index++
                in '1'..'9' -> while (text.getOrNull(index) in '0'..'9') index++
                else -> error("$label JSON number is invalid")
            }
            if (consumeIf('.')) {
                require(text.getOrNull(index) in '0'..'9') { "$label JSON fraction is invalid" }
                while (text.getOrNull(index) in '0'..'9') index++
            }
            if (text.getOrNull(index) in setOf('e', 'E')) {
                index++
                if (text.getOrNull(index) in setOf('+', '-')) index++
                require(text.getOrNull(index) in '0'..'9') { "$label JSON exponent is invalid" }
                while (text.getOrNull(index) in '0'..'9') index++
            }
        }

        private fun consumeLiteral(expected: String) {
            require(text.startsWith(expected, index)) { "$label JSON literal is invalid" }
            index += expected.length
        }

        private fun skipWhitespace() {
            while (text.getOrNull(index) in setOf(' ', '\t', '\r', '\n')) index++
        }

        private fun consumeIf(expected: Char): Boolean {
            if (text.getOrNull(index) != expected) return false
            index++
            return true
        }

        private fun isHexDigit(value: Char): Boolean =
            value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'
    }
}
object Limits {
    const val MAX_FILES = 80; const val MAX_PATH_BYTES = 512
    const val MANIFEST_STREAM_BYTES = 256 * 1024; const val RELEASE_BRIEF_BYTES = 8 * 1024
    const val EVIDENCE_BYTES = 16 * 1024
    const val PROMPT_BYTES = 100_000; const val CONTEXT_TOKENS = 131_072
    const val FRAMING_TOKENS = 4_096; const val OUTPUT_TOKENS = 2_048
    const val HTTP_RESPONSE_BYTES = 256 * 1024; const val FIXTURE_REPORT_BYTES = 128 * 1024
}
@Serializable
data class ManifestItem(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val binary: Boolean,
    val oldObjectId: String? = null,
    val newObjectId: String? = null,
    val oldMode: String,
    val newMode: String,
    val fingerprint: String,
)
@Serializable
data class ReleaseManifest(
    val baseSha: String,
    val mergeBaseSha: String,
    val headSha: String,
    val items: List<ManifestItem>,
    val fingerprint: String,
)
@Serializable
enum class EvidenceKind { REQUIRED_README_CHANGE, REVIEWED_RELEASE_BRIEF }
@Serializable
data class ReleaseBriefDocument(
    val objective: String,
    val highlights: List<String>,
    val operationalNotes: List<String>,
    val videoFocus: List<String>,
)
@Serializable
data class EvidenceItem(
    val path: String,
    val kind: EvidenceKind,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val manifestFingerprint: String,
    val brief: ReleaseBriefDocument? = null,
)
data class ReleaseEvidence(
    val items: List<EvidenceItem>,
    val omittedPaths: List<String>,
)
@Serializable
data class AiText(
    val text: String,
    val evidencePaths: List<String>,
)
@Serializable
data class AiRisk(
    val severity: String,
    val text: String,
    val mitigation: String,
    val evidencePaths: List<String>,
)
@Serializable
data class AiReleasePlan(
    val summary: List<AiText>,
    val releaseNotes: List<AiText>,
    val risks: List<AiRisk>,
    val videoSteps: List<AiText>,
    val recommendation: String,
)
@Serializable
data class ReleaseFixture(
    val source: String,
    val branch: String,
    val base: String,
    val mergeBase: String,
    val head: String,
    val manifest: ReleaseManifest,
    val brief: ReleaseBriefDocument,
    val ai: AiReleasePlan,
)
data class SnapshotFingerprint(
    val repository: String,
    val branch: String,
    val headSha: String,
    val headTreeSha: String,
    val indexTreeSha: String,
    val statusSha256: String,
) {
    val fingerprint: String = sha256(repository, branch, headSha, headTreeSha, indexTreeSha, statusSha256)
}
data class CheckResult(
    val name: String,
    val command: String,
    val exitCode: Int,
    val durationMillis: Long,
) {
    val passed: Boolean get() = exitCode == 0
}
data class PromptBundle(
    val system: String,
    val user: String,
    val evidence: ReleaseEvidence,
    val utf8Bytes: Int,
    val contextUpperBound: Int,
    val fingerprint: String,
)
data class ReleaseFacts(
    val source: String,
    val branch: String,
    val base: String,
    val mergeBase: String,
    val head: String,
    val snapshot: SnapshotFingerprint,
    val manifest: ReleaseManifest,
    val module: String,
    val checks: List<CheckResult>,
    val prompt: PromptBundle,
)
class ValidatedPlan private constructor(val value: AiReleasePlan) {
    companion object {
        internal fun create(value: AiReleasePlan): ValidatedPlan = ValidatedPlan(value)
    }
}
fun sha256(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach {
        digest.update(it.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun sha256Bytes(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
fun utf8Bytes(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size
