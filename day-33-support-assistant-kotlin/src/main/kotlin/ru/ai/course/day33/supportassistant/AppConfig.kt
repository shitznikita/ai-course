package ru.ai.course.day33.supportassistant

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class SupportLimits(
    val topK: Int,
    val minRelevance: Double,
    val embeddingDimensions: Int,
    val maxChunkChars: Int,
    val maxEvidenceChars: Int,
    val maxPromptChars: Int,
    val maxQuestionChars: Int,
    val chatHistoryTurns: Int,
)

data class AppConfig(
    val repositoryRoot: Path,
    val fixturePath: Path,
    val knowledgeDirectory: Path,
    val ragIndexPath: Path,
    val evaluationPath: Path,
    val mcpHost: String,
    val mcpPort: Int,
    val mcpConnectTimeout: Duration,
    val mcpRequestTimeout: Duration,
    val llmApiKey: String?,
    val llmAuthScheme: String,
    val llmApiUrl: String,
    val llmModel: String,
    val llmConnectTimeout: Duration,
    val llmRequestTimeout: Duration,
    val llmMaxResponseBytes: Int,
    val llmMaxOutputTokens: Int,
    val limits: SupportLimits,
) {
    fun mcpEndpoint(): URI = URI.create("http://$mcpHost:$mcpPort/mcp")
    fun llmEndpoint(): URI = URI.create(llmApiUrl)

    companion object {
        fun load(): AppConfig = fromValues(loadEnvFile(Path(".env")) + System.getenv())

        fun fromValues(values: Map<String, String>): AppConfig {
            val root = Path(values.optional("REPOSITORY_ROOT") ?: "..")
                .toAbsolutePath()
                .normalize()
                .let { if (it.exists()) it.toRealPath() else it }
            require(Files.isDirectory(root)) { "REPOSITORY_ROOT is not a directory: $root" }

            val mcpHost = values.optional("MCP_HOST") ?: "127.0.0.1"
            require(mcpHost == "127.0.0.1" || mcpHost == "localhost") {
                "Day 33 MCP server must remain loopback-only."
            }
            val llmUrl = values.optional("LLM_API_URL") ?: REVIEWED_LLM_API_URL
            require(llmUrl == REVIEWED_LLM_API_URL) {
                "LLM_API_URL must be the reviewed Eliza endpoint: $REVIEWED_LLM_API_URL"
            }

            return AppConfig(
                repositoryRoot = root,
                fixturePath = root.resolve(
                    values.optional("SUPPORT_FIXTURE_PATH")
                        ?: "day-33-support-assistant-kotlin/fixtures/support-data.json",
                ).normalize(),
                knowledgeDirectory = root.resolve(
                    values.optional("SUPPORT_KNOWLEDGE_DIR")
                        ?: "day-33-support-assistant-kotlin/knowledge",
                ).normalize(),
                ragIndexPath = root.resolve(
                    values.optional("SUPPORT_RAG_INDEX_PATH")
                        ?: "day-33-support-assistant-kotlin/runtime/rag-index.json",
                ).normalize(),
                evaluationPath = root.resolve(
                    values.optional("SUPPORT_EVAL_PATH")
                        ?: "day-33-support-assistant-kotlin/eval/scenarios.json",
                ).normalize(),
                mcpHost = mcpHost,
                mcpPort = values.int("MCP_PORT", 3033, 1..65535),
                mcpConnectTimeout = Duration.ofMillis(
                    values.long("MCP_CONNECT_TIMEOUT_MILLIS", 5_000, 500L..30_000L),
                ),
                mcpRequestTimeout = Duration.ofMillis(
                    values.long("MCP_REQUEST_TIMEOUT_MILLIS", 10_000, 1_000L..60_000L),
                ),
                llmApiKey = values.optional("LLM_API_KEY"),
                llmAuthScheme = values.optional("LLM_AUTH_SCHEME") ?: "OAuth",
                llmApiUrl = llmUrl,
                llmModel = values.optional("LLM_MODEL") ?: "meta-llama/llama-3.3-70b-instruct",
                llmConnectTimeout = Duration.ofSeconds(
                    values.long("LLM_CONNECT_TIMEOUT_SECONDS", 10, 1L..60L),
                ),
                llmRequestTimeout = Duration.ofSeconds(
                    values.long("LLM_REQUEST_TIMEOUT_SECONDS", 45, 5L..180L),
                ),
                llmMaxResponseBytes = values.int(
                    "LLM_MAX_RESPONSE_BYTES",
                    131_072,
                    16_384..2_000_000,
                ),
                llmMaxOutputTokens = values.int("LLM_MAX_OUTPUT_TOKENS", 700, 128..2_048),
                limits = SupportLimits(
                    topK = values.int("RAG_TOP_K", 4, 1..8),
                    minRelevance = values.double("RAG_MIN_RELEVANCE", 0.12, 0.0..1.0),
                    embeddingDimensions = values.int("RAG_EMBEDDING_DIMENSIONS", 192, 64..1_024),
                    maxChunkChars = values.int("RAG_MAX_CHUNK_CHARS", 1_800, 300..6_000),
                    maxEvidenceChars = values.int("RAG_MAX_EVIDENCE_CHARS", 5_200, 1_000..20_000),
                    maxPromptChars = values.int("PROMPT_MAX_CHARS", 12_000, 4_000..50_000),
                    maxQuestionChars = values.int("QUESTION_MAX_CHARS", 500, 50..2_000),
                    chatHistoryTurns = values.int("CHAT_HISTORY_TURNS", 4, 0..10),
                ),
            ).also { config ->
                listOf(config.fixturePath, config.knowledgeDirectory, config.ragIndexPath, config.evaluationPath)
                    .forEach { require(it.startsWith(root)) { "Configured path escapes REPOSITORY_ROOT: $it" } }
            }
        }

        const val REVIEWED_LLM_API_URL =
            "https://api.eliza.yandex.net/openrouter/v1/chat/completions"
        val REVIEWED_LLM_ENDPOINT: URI = URI.create(REVIEWED_LLM_API_URL)

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

        private fun Map<String, String>.double(name: String, default: Double, range: ClosedRange<Double>): Double {
            val raw = optional(name) ?: return default
            val value = raw.toDoubleOrNull() ?: error("$name must be a number.")
            require(value in range) { "$name must be in ${range.start}..${range.endInclusive}." }
            return value
        }
    }
}
