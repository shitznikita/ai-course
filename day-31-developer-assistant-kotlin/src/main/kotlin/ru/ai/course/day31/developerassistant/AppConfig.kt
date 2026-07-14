package ru.ai.course.day31.developerassistant

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val projectRoot: Path,
    val allowedDocuments: List<String>,
    val mcpHost: String,
    val mcpPort: Int,
    val mcpTimeout: Duration,
    val ollamaBaseUrl: String,
    val ollamaModel: String,
    val ollamaEmbeddingModel: String,
    val ollamaTimeout: Duration,
    val ollamaContextLength: Int,
    val ollamaMaxOutputTokens: Int,
    val promptReserveTokens: Int,
    val embeddingBackend: String,
    val indexFile: Path,
    val topK: Int,
    val candidateCount: Int,
    val minRelevance: Double,
    val chunkMaxTokens: Int,
    val maxDocumentBytes: Long,
    val maxContextTokens: Int,
    val maxFileList: Int,
) {
    val mcpUrl: String
        get() = "http://${if (mcpHost == "::1") "[$mcpHost]" else mcpHost}:$mcpPort/mcp"
    val maxPromptTokens: Int
        get() = ollamaContextLength - ollamaMaxOutputTokens - promptReserveTokens
    val maxPromptBytes: Int
        get() = maxPromptTokens
    val maxQuestionBytes: Int
        get() = maxPromptBytes / 4

    fun ollamaEndpoint(path: String): URI {
        require(path.startsWith('/')) { "Ollama API path must start with '/'." }
        return URI.create("$ollamaBaseUrl$path")
    }

    companion object {
        private val defaultDocuments = listOf(
            "README.md",
            "docs/project-architecture.md",
            "docs/developer-assistant-api.yaml",
            "day-31-developer-assistant-kotlin/README.md",
        )

        fun load(): AppConfig = fromValues(loadEnvFile(Path(".env")) + System.getenv())

        fun fromValues(values: Map<String, String>): AppConfig {
            val projectRootValue = values.optional("PROJECT_ROOT") ?: ".."
            val projectRoot = Path(projectRootValue).toAbsolutePath().normalize().toRealPath()
            require(Files.isDirectory(projectRoot)) { "PROJECT_ROOT is not a directory: $projectRoot" }

            val mcpHost = (values.optional("MCP_HOST") ?: "127.0.0.1").lowercase()
            validateLoopbackHost(mcpHost, "MCP_HOST")
            val ollamaBaseUrl = (values.optional("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434")
                .trim()
                .trimEnd('/')
            validateLoopbackBaseUrl(ollamaBaseUrl)

            val embeddingBackend = (values.optional("EMBEDDING_BACKEND") ?: "hash").lowercase()
            require(embeddingBackend in setOf("hash", "ollama")) {
                "EMBEDDING_BACKEND must be 'hash' or 'ollama', got '$embeddingBackend'."
            }
            val topK = values.int("RAG_TOP_K", 4, 1..12)
            val candidateCount = values.int("RAG_CANDIDATE_COUNT", maxOf(10, topK), topK..50)
            val contextLength = values.int("OLLAMA_CONTEXT_LENGTH", 8192, 1024..32768)
            val maxOutputTokens = values.int("OLLAMA_MAX_OUTPUT_TOKENS", 384, 64..2048)
            val promptReserveTokens = values.int("PROMPT_RESERVE_TOKENS", 256, 128..2048)
            require(contextLength - maxOutputTokens - promptReserveTokens >= 4096) {
                "OLLAMA_CONTEXT_LENGTH must leave at least 4096 conservative prompt bytes after output and reserve budgets."
            }

            return AppConfig(
                projectRoot = projectRoot,
                allowedDocuments = defaultDocuments,
                mcpHost = mcpHost,
                mcpPort = values.int("MCP_PORT", 3031, 1024..65535),
                mcpTimeout = Duration.ofSeconds(values.long("MCP_TIMEOUT_SECONDS", 15, 2L..120L)),
                ollamaBaseUrl = ollamaBaseUrl,
                ollamaModel = values.optional("OLLAMA_MODEL") ?: "qwen3:4b",
                ollamaEmbeddingModel = values.optional("OLLAMA_EMBED_MODEL") ?: "nomic-embed-text",
                ollamaTimeout = Duration.ofSeconds(values.long("OLLAMA_REQUEST_TIMEOUT_SECONDS", 180, 5L..900L)),
                ollamaContextLength = contextLength,
                ollamaMaxOutputTokens = maxOutputTokens,
                promptReserveTokens = promptReserveTokens,
                embeddingBackend = embeddingBackend,
                indexFile = Path(values.optional("RAG_INDEX_FILE") ?: "runtime/rag-index.json")
                    .toAbsolutePath()
                    .normalize(),
                topK = topK,
                candidateCount = candidateCount,
                minRelevance = values.double("RAG_MIN_RELEVANCE", 0.18, 0.0..1.0),
                chunkMaxTokens = values.int("RAG_CHUNK_MAX_TOKENS", 260, 80..800),
                maxDocumentBytes = values.long("MAX_DOCUMENT_BYTES", 524_288, 1_024L..2_097_152L),
                maxContextTokens = values.int("MAX_CONTEXT_TOKENS", 1800, 300..6000),
                maxFileList = values.int("MAX_FILE_LIST", 80, 1..200),
            )
        }

        private fun validateLoopbackHost(value: String, name: String) {
            require(value in setOf("localhost", "127.0.0.1", "::1")) {
                "$name must be localhost, 127.0.0.1, or ::1."
            }
        }

        private fun validateLoopbackBaseUrl(value: String) {
            val uri = try {
                URI.create(value)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("OLLAMA_BASE_URL is not a valid URL: '$value'.")
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            require(
                uri.scheme?.lowercase() == "http" &&
                    host in setOf("localhost", "127.0.0.1", "::1") &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null &&
                    uri.path in setOf("", "/"),
            ) {
                "OLLAMA_BASE_URL must be a plain loopback HTTP URL such as http://127.0.0.1:11434."
            }
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path).mapNotNull { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith('#') || '=' !in line) {
                    null
                } else {
                    val key = line.substringBefore('=').trim().removePrefix("export ").trim()
                    val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                    key.takeIf(String::isNotBlank)?.let { it to value }
                }
            }.toMap()
        }

        private fun Map<String, String>.optional(name: String): String? =
            this[name]?.trim()?.takeIf(String::isNotEmpty)

        private fun Map<String, String>.int(name: String, default: Int, range: IntRange): Int {
            val raw = optional(name) ?: return default
            val value = raw.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer, got '$raw'.")
            require(value in range) { "$name must be between ${range.first} and ${range.last}." }
            return value
        }

        private fun Map<String, String>.long(name: String, default: Long, range: LongRange): Long {
            val raw = optional(name) ?: return default
            val value = raw.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer, got '$raw'.")
            require(value in range) { "$name must be between ${range.first} and ${range.last}." }
            return value
        }

        private fun Map<String, String>.double(name: String, default: Double, range: ClosedRange<Double>): Double {
            val raw = optional(name) ?: return default
            val value = raw.toDoubleOrNull() ?: throw IllegalArgumentException("$name must be a number, got '$raw'.")
            require(value in range) { "$name must be between ${range.start} and ${range.endInclusive}." }
            return value
        }
    }
}
