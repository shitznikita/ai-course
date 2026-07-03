import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val documentsDir: Path,
    val indexDir: Path,
    val reportsDir: Path,
    val controlQuestionsFile: Path,
    val unknownQuestionsFile: Path,
    val embeddingBackend: String,
    val ollamaBaseUrl: String,
    val ollamaEmbedModel: String,
    val ragChunkStrategy: String,
    val retrievalTopKBefore: Int,
    val rerankTopKAfter: Int,
    val rerankMinScore: Double,
    val answerMinRelevance: Double,
    val minQuotes: Int,
    val maxQuotes: Int,
    val quoteMaxChars: Int,
    val queryRewriteMode: String,
    val rerankMode: String,
    val ragMaxContextTokens: Int,
    val corpusMaxFiles: Int,
    val structuredMaxTokens: Int,
    val llmApiKey: String?,
    val llmAuthScheme: String,
    val llmApiUrl: String,
    val llmModel: String,
) {
    companion object {
        fun load(): AppConfig {
            val env = loadEnvFile(Path(".env")) + System.getenv()
            return AppConfig(
                documentsDir = Path(env["DOCUMENTS_DIR"]?.blankToNull() ?: "..").normalize(),
                indexDir = Path(env["INDEX_DIR"]?.blankToNull() ?: "index").normalize(),
                reportsDir = Path(env["REPORTS_DIR"]?.blankToNull() ?: "reports").normalize(),
                controlQuestionsFile = Path(env["CONTROL_QUESTIONS_FILE"]?.blankToNull() ?: "eval/control-questions.json").normalize(),
                unknownQuestionsFile = Path(env["UNKNOWN_QUESTIONS_FILE"]?.blankToNull() ?: "eval/unknown-questions.json").normalize(),
                embeddingBackend = env["EMBEDDING_BACKEND"]?.blankToNull()?.lowercase() ?: "hash",
                ollamaBaseUrl = env["OLLAMA_BASE_URL"]?.blankToNull()?.trimEnd('/') ?: "http://localhost:11434",
                ollamaEmbedModel = env["OLLAMA_EMBED_MODEL"]?.blankToNull() ?: "nomic-embed-text",
                ragChunkStrategy = env["RAG_CHUNK_STRATEGY"]?.blankToNull()?.lowercase() ?: "structured",
                retrievalTopKBefore = env["RETRIEVAL_TOP_K_BEFORE"]?.toIntOrNull()?.coerceIn(1, 30) ?: 10,
                rerankTopKAfter = env["RERANK_TOP_K_AFTER"]?.toIntOrNull()?.coerceIn(1, 12) ?: 4,
                rerankMinScore = env["RERANK_MIN_SCORE"]?.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.55,
                answerMinRelevance = env["ANSWER_MIN_RELEVANCE"]?.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.55,
                minQuotes = env["MIN_QUOTES"]?.toIntOrNull()?.coerceIn(0, 8) ?: 1,
                maxQuotes = env["MAX_QUOTES"]?.toIntOrNull()?.coerceIn(1, 12) ?: 4,
                quoteMaxChars = env["QUOTE_MAX_CHARS"]?.toIntOrNull()?.coerceIn(80, 800) ?: 260,
                queryRewriteMode = env["QUERY_REWRITE_MODE"]?.blankToNull()?.lowercase() ?: "local",
                rerankMode = env["RERANK_MODE"]?.blankToNull()?.lowercase() ?: "heuristic",
                ragMaxContextTokens = env["RAG_MAX_CONTEXT_TOKENS"]?.toIntOrNull()?.coerceIn(600, 8000) ?: 2200,
                corpusMaxFiles = env["CORPUS_MAX_FILES"]?.toIntOrNull()?.coerceIn(1, 1200) ?: 400,
                structuredMaxTokens = env["STRUCTURED_MAX_TOKENS"]?.toIntOrNull()?.coerceIn(120, 3000) ?: 700,
                llmApiKey = env["LLM_API_KEY"]?.secretValueOrNull(),
                llmAuthScheme = env["LLM_AUTH_SCHEME"] ?: "OAuth",
                llmApiUrl = env["LLM_API_URL"] ?: "https://api.eliza.yandex.net/openrouter/v1/chat/completions",
                llmModel = env["LLM_MODEL"] ?: "meta-llama/llama-3.3-70b-instruct",
            )
        }

        private fun loadEnvFile(path: Path): Map<String, String> {
            if (!path.exists()) return emptyMap()
            return Files.readAllLines(path)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }
                .associate {
                    val key = it.substringBefore("=").trim()
                    val value = it.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                    key to value
                }
        }
    }
}

private fun String.blankToNull(): String? = trim().ifBlank { null }

private fun String.secretValueOrNull(): String? =
    blankToNull()?.takeUnless {
        it.startsWith("replace-with", ignoreCase = true) ||
            it.startsWith("/absolute/path", ignoreCase = true)
    }
