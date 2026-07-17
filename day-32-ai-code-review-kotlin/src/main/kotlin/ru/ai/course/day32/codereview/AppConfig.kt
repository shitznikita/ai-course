package ru.ai.course.day32.codereview

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class ReviewLimits(
    val maxChangedFiles: Int,
    val maxFileBytes: Int,
    val maxTotalChangedBytes: Int,
    val maxCorpusFiles: Int,
    val maxCorpusBytes: Long,
    val maxCorpusFileBytes: Long,
    val maxChunks: Int,
    val chunkLines: Int,
    val evidenceBytes: Int,
    val promptBytes: Int,
    val maxBatches: Int,
)

data class AppConfig(
    val repositoryRoot: Path,
    val repository: String?,
    val pullRequestNumber: Int?,
    val expectedBaseSha: String?,
    val expectedHeadSha: String?,
    val githubToken: String?,
    val githubApiUrl: String,
    val llmApiUrl: String,
    val llmApiKey: String?,
    val llmAuthScheme: String,
    val llmModel: String,
    val llmTimeout: Duration,
    val llmMaxOutputTokens: Int,
    val limits: ReviewLimits,
) {
    fun requireCiValues(): AppConfig {
        requireValidRepository(requireNotNull(repository) { "GITHUB_REPOSITORY is required." })
        require((pullRequestNumber ?: 0) > 0) { "PR_NUMBER must be a positive integer." }
        requireSha(requireNotNull(expectedBaseSha) { "PR_BASE_SHA is required." }, "PR_BASE_SHA")
        requireSha(requireNotNull(expectedHeadSha) { "PR_HEAD_SHA is required." }, "PR_HEAD_SHA")
        require(!githubToken.isNullOrBlank()) { "GITHUB_TOKEN is required." }
        require(!llmApiKey.isNullOrBlank()) { "LLM_API_KEY is required." }
        return this
    }

    fun llmEndpoint(): URI = URI.create(llmApiUrl)

    companion object {
        fun load(): AppConfig = fromValues(loadEnvFile(Path(".env")) + System.getenv())

        fun fromValues(values: Map<String, String>): AppConfig {
            val root = Path(values.optional("REPOSITORY_ROOT") ?: "..")
                .toAbsolutePath()
                .normalize()
                .let { if (it.exists()) it.toRealPath() else it }
            require(Files.isDirectory(root)) { "REPOSITORY_ROOT is not a directory: $root" }

            val llmApiUrl = values.optional("LLM_API_URL")
                ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions"
            validateHttpsUrl(llmApiUrl, "LLM_API_URL")
            val githubApiUrl = (values.optional("GITHUB_API_URL") ?: "https://api.github.com").trimEnd('/')
            validateHttpsUrl(githubApiUrl, "GITHUB_API_URL")

            val maxBatches = values.int("REVIEW_MAX_BATCHES", 3, 1..3)
            val limits = ReviewLimits(
                maxChangedFiles = values.int("REVIEW_MAX_CHANGED_FILES", 60, 1..200),
                maxFileBytes = values.int("REVIEW_MAX_FILE_BYTES", 120_000, 4_096..500_000),
                maxTotalChangedBytes = values.int(
                    "REVIEW_MAX_TOTAL_CHANGED_BYTES",
                    600_000,
                    16_384..2_000_000,
                ),
                maxCorpusFiles = values.int("RAG_MAX_FILES", 220, 10..1_000),
                maxCorpusBytes = values.long("RAG_MAX_BYTES", 2_500_000, 65_536L..20_000_000L),
                maxCorpusFileBytes = values.long("RAG_MAX_FILE_BYTES", 200_000, 1_024L..1_000_000L),
                maxChunks = values.int("RAG_MAX_CHUNKS", 1_200, 20..5_000),
                chunkLines = values.int("RAG_CHUNK_LINES", 60, 10..160),
                evidenceBytes = values.int("RAG_EVIDENCE_BYTES", 18_000, 2_000..80_000),
                promptBytes = values.int("REVIEW_PROMPT_BYTES", 55_000, 8_000..160_000),
                maxBatches = maxBatches,
            )
            require(limits.promptBytes - limits.evidenceBytes >= 4_000) {
                "REVIEW_PROMPT_BYTES must leave at least 4000 bytes outside the RAG evidence budget."
            }
            return AppConfig(
                repositoryRoot = root,
                repository = values.optional("GITHUB_REPOSITORY"),
                pullRequestNumber = values.optional("PR_NUMBER")?.toIntOrNull(),
                expectedBaseSha = values.optional("PR_BASE_SHA"),
                expectedHeadSha = values.optional("PR_HEAD_SHA"),
                githubToken = values.optional("GITHUB_TOKEN"),
                githubApiUrl = githubApiUrl,
                llmApiUrl = llmApiUrl,
                llmApiKey = values.optional("LLM_API_KEY"),
                llmAuthScheme = values.optional("LLM_AUTH_SCHEME") ?: "OAuth",
                llmModel = values.optional("LLM_MODEL") ?: "meta-llama/llama-3.3-70b-instruct",
                llmTimeout = Duration.ofSeconds(values.long("LLM_TIMEOUT_SECONDS", 120, 10L..300L)),
                llmMaxOutputTokens = values.int("LLM_MAX_OUTPUT_TOKENS", 1800, 256..4096),
                limits = limits,
            )
        }

        fun requireValidRepository(value: String) {
            require(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""").matches(value)) {
                "Repository must have owner/name form."
            }
        }

        fun requireSha(value: String, name: String) {
            require(Regex("""[0-9a-fA-F]{40}""").matches(value)) { "$name must be a 40-hex SHA." }
        }

        fun requireSafePath(value: String): String {
            require(value.isNotBlank() && value.length <= 1_024) { "Changed path is blank or too long." }
            require(!value.startsWith('/') && '\\' !in value && '\u0000' !in value) {
                "Changed path is not repository-relative."
            }
            require(value.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Changed path contains an unsafe segment."
            }
            return value
        }

        private fun validateHttpsUrl(value: String, name: String) {
            val uri = runCatching { URI.create(value) }
                .getOrElse { throw IllegalArgumentException("$name is not a valid URL.") }
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) {
                "$name must be an HTTPS URL without credentials or fragment."
            }
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path).mapNotNull { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith('#') || '=' !in line) null else {
                    val key = line.substringBefore('=').removePrefix("export ").trim()
                    val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                    key.takeIf(String::isNotBlank)?.let { it to value }
                }
            }.toMap()
        }

        private fun Map<String, String>.optional(name: String): String? =
            this[name]?.trim()?.takeIf(String::isNotEmpty)

        private fun Map<String, String>.int(name: String, default: Int, range: IntRange): Int {
            val raw = optional(name) ?: return default
            val value = raw.toIntOrNull() ?: error("$name must be an integer.")
            require(value in range) { "$name must be in ${range.first}..${range.last}." }
            return value
        }

        private fun Map<String, String>.long(name: String, default: Long, range: LongRange): Long {
            val raw = optional(name) ?: return default
            val value = raw.toLongOrNull() ?: error("$name must be an integer.")
            require(value in range) { "$name must be in ${range.first}..${range.last}." }
            return value
        }
    }
}
