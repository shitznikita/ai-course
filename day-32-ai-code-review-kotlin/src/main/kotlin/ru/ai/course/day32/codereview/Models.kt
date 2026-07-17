package ru.ai.course.day32.codereview

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.sqrt

object ReviewJson {
    val strict: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    val tolerant: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    val pretty: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        prettyPrint = true
    }
}

@Serializable
data class PullRequestMetadata(
    val repository: String,
    val number: Int,
    val title: String,
    val baseSha: String,
    val headSha: String,
    val draft: Boolean,
    val fromFork: Boolean,
    val changedFiles: Int,
)

@Serializable
data class ChangedFile(
    val path: String,
    val previousPath: String? = null,
    val status: String,
    val sha: String? = null,
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val patch: String? = null,
    val content: String? = null,
    val binary: Boolean = false,
    val patchTruncated: Boolean = false,
    val contentTruncated: Boolean = false,
)

@Serializable
enum class DiffLineKind {
    CONTEXT,
    ADDED,
    REMOVED,
}

@Serializable
data class ChangedLine(
    val kind: DiffLineKind,
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val text: String,
) {
    val anchorId: String?
        get() = if (kind == DiffLineKind.ADDED && newLine != null) "L$newLine" else null
}

@Serializable
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val header: String,
    val lines: List<ChangedLine>,
)

@Serializable
data class ParsedFileDiff(
    val path: String,
    val hunks: List<DiffHunk>,
) {
    val addedLines: Set<Int> = hunks
        .flatMap(DiffHunk::lines)
        .filter { it.kind == DiffLineKind.ADDED }
        .mapNotNull(ChangedLine::newLine)
        .toSet()
}

@Serializable
enum class CorpusCategory {
    DOCUMENTATION,
    CODE,
}

@Serializable
data class CorpusDocument(
    val path: String,
    val content: String,
    val category: CorpusCategory,
    val priority: Int,
)

@Serializable
data class CorpusMetrics(
    val trackedFiles: Int,
    val includedFiles: Int,
    val includedBytes: Long,
    val skippedFiles: Int,
    val truncated: Boolean,
    val skippedReasons: Map<String, Int>,
)

data class LoadedCorpus(
    val documents: List<CorpusDocument>,
    val metrics: CorpusMetrics,
)

@Serializable
data class SourceChunk(
    val id: String,
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val category: CorpusCategory,
    val section: String,
    val contentHash: String,
    val content: String,
)

data class RetrievalHit(
    val chunk: SourceChunk,
    val score: Double,
    val lexicalScore: Double,
    val vectorScore: Double,
    val proximityScore: Double,
)

data class EvidenceItem(
    val chunk: SourceChunk,
    val score: Double,
    val rendered: String,
)

data class EvidencePack(
    val items: List<EvidenceItem>,
    val rendered: String,
    val bytes: Int,
    val maxBytes: Int,
    val truncated: Boolean,
) {
    val sourceIds: Set<String> = items.mapTo(linkedSetOf()) { it.chunk.id }
}

data class ReviewBatch(
    val index: Int,
    val files: List<ChangedFile>,
    val parsedDiffs: List<ParsedFileDiff>,
    val evidence: EvidencePack,
)

data class PromptPack(
    val system: String,
    val user: String,
    val preview: String,
    val bytes: Int,
    val maxBytes: Int,
    val truncated: Boolean,
)

enum class TransmittedContentState {
    INCLUDED,
    UNAVAILABLE,
    OMITTED_FOR_BUDGET,
}

data class TransmittedChangedFile(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String,
    val content: String?,
    val contentState: TransmittedContentState,
    val parsedDiff: ParsedFileDiff,
    val rendered: String,
) {
    init {
        require(path == parsedDiff.path) { "Transmitted file path must match its parsed diff." }
        require(patch.isNotBlank() && parsedDiff.addedLines.isNotEmpty()) {
            "Transmitted file must contain a complete reviewable patch."
        }
        val lines = parsedDiff.hunks.flatMap(DiffHunk::lines)
        require(lines.count { it.kind == DiffLineKind.ADDED } == additions) {
            "Transmitted patch additions do not match changed-file metadata."
        }
        require(lines.count { it.kind == DiffLineKind.REMOVED } == deletions) {
            "Transmitted patch deletions do not match changed-file metadata."
        }
        require((contentState == TransmittedContentState.INCLUDED) == (content != null)) {
            "Transmitted content state does not match the transmitted content."
        }
        require(rendered.isNotBlank()) { "Transmitted file rendering must not be blank." }
    }
}

data class TransmittedReviewInput(
    val batchIndex: Int,
    val files: List<TransmittedChangedFile>,
    val evidenceItems: List<EvidenceItem>,
    val omittedFileCount: Int,
    val omittedEvidenceCount: Int,
    val contentOmittedFileCount: Int,
    val upstreamEvidenceTruncated: Boolean,
) {
    init {
        require(omittedFileCount >= 0 && omittedEvidenceCount >= 0 && contentOmittedFileCount >= 0)
        require(files.map(TransmittedChangedFile::path).distinct().size == files.size) {
            "Transmitted file paths must be unique."
        }
        require(evidenceItems.map { it.chunk.id }.distinct().size == evidenceItems.size) {
            "Transmitted evidence IDs must be unique."
        }
    }

    val sourceIds: Set<String> = evidenceItems.mapTo(linkedSetOf()) { it.chunk.id }
    val reviewedPaths: Set<String> = files.mapTo(linkedSetOf(), TransmittedChangedFile::path)
}

data class PreparedReviewPrompt(
    val input: TransmittedReviewInput,
    val prompt: PromptPack,
)

@Serializable
enum class FindingCategory {
    BUG,
    ARCHITECTURE,
    RECOMMENDATION,
}

@Serializable
enum class FindingSeverity {
    BLOCKER,
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
data class ModelFinding(
    val category: FindingCategory,
    val severity: FindingSeverity,
    val path: String,
    val line: Int,
    val title: String,
    val detail: String,
    val recommendation: String,
    val evidenceIds: List<String>,
)

@Serializable
data class ModelReview(
    val findings: List<ModelFinding>,
)

data class ValidatedFinding(
    val category: FindingCategory,
    val severity: FindingSeverity,
    val path: String,
    val line: Int,
    val title: String,
    val detail: String,
    val recommendation: String,
    val evidence: List<SourceChunk>,
)

data class ValidationResult(
    val findings: List<ValidatedFinding>,
    val errors: List<String>,
) {
    val valid: Boolean = errors.isEmpty()
}

data class ReviewCoverage(
    val reportedChangedFiles: Int,
    val fetchedChangedFiles: Int,
    val reviewedFiles: Int,
    val binaryFiles: Int,
    val patchTruncatedFiles: Int,
    val contentTruncatedFiles: Int,
    val diffEndpointAvailable: Boolean,
    val corpusMetrics: CorpusMetrics,
    val notes: List<String> = emptyList(),
)

data class ReviewResult(
    val metadata: PullRequestMetadata,
    val findings: List<ValidatedFinding>,
    val coverage: ReviewCoverage,
    val model: String,
    val markdown: String,
)

@Serializable
data class LlmReply(
    val model: String,
    val content: String,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
)

@Serializable
data class EvaluationCase(
    val id: String,
    val description: String,
    val modelOutput: String,
    val expectValid: Boolean,
    val expectedCategories: List<FindingCategory> = emptyList(),
)

@Serializable
data class EvaluationCases(
    val cases: List<EvaluationCase>,
)

object TextTools {
    private val tokenPattern = Regex("""[\p{L}\p{N}_./:-]+""")

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun utf8Bytes(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    fun tokens(value: String): List<String> =
        tokenPattern.findAll(value.lowercase()).map { it.value }.toList()

    fun hashEmbedding(value: String, dimensions: Int = 256): DoubleArray {
        require(dimensions > 0)
        val vector = DoubleArray(dimensions)
        tokens(value).forEach { token ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(StandardCharsets.UTF_8))
            val index = ((digest[0].toInt() and 0xff) shl 8 or (digest[1].toInt() and 0xff)) % dimensions
            val sign = if ((digest[2].toInt() and 1) == 0) 1.0 else -1.0
            vector[index] += sign
        }
        val norm = sqrt(vector.sumOf { it * it })
        if (norm > 0.0) vector.indices.forEach { vector[it] /= norm }
        return vector
    }

    fun cosine(left: DoubleArray, right: DoubleArray): Double {
        require(left.size == right.size)
        return left.indices.sumOf { left[it] * right[it] }
    }

    fun sanitizePromptData(value: String): String =
        value
            .replace("<PR_DIFF_UNTRUSTED>", "<tag-redacted>")
            .replace("</PR_DIFF_UNTRUSTED>", "</tag-redacted>")
            .replace("<CHANGED_CONTENT_UNTRUSTED>", "<tag-redacted>")
            .replace("</CHANGED_CONTENT_UNTRUSTED>", "</tag-redacted>")
            .replace("<RAG_EVIDENCE_UNTRUSTED>", "<tag-redacted>")
            .replace("</RAG_EVIDENCE_UNTRUSTED>", "</tag-redacted>")
            .replace("<RAG_EVIDENCE_TRUSTED>", "<tag-redacted>")
            .replace("</RAG_EVIDENCE_TRUSTED>", "</tag-redacted>")
            .replace("[sourceId=", "[dataSourceId=")
            .replace('<', '‹')
            .replace('>', '›')
}
