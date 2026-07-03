import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val documentsDir: Path,
    val indexDir: Path,
    val embeddingBackend: String,
    val ollamaBaseUrl: String,
    val ollamaEmbedModel: String,
    val fixedChunkTokens: Int,
    val fixedChunkOverlap: Int,
    val structuredMaxTokens: Int,
    val retrievalTopK: Int,
    val corpusMaxFiles: Int,
) {
    companion object {
        fun load(): AppConfig {
            val env = loadEnvFile(Path(".env")) + System.getenv()
            val fixedSize = env["FIXED_CHUNK_TOKENS"]?.toIntOrNull()?.coerceIn(120, 2000) ?: 450
            val fixedOverlap = env["FIXED_CHUNK_OVERLAP"]?.toIntOrNull()?.coerceIn(0, fixedSize - 1) ?: 75
            return AppConfig(
                documentsDir = Path(env["DOCUMENTS_DIR"]?.blankToNull() ?: "..").normalize(),
                indexDir = Path(env["INDEX_DIR"]?.blankToNull() ?: "index").normalize(),
                embeddingBackend = env["EMBEDDING_BACKEND"]?.blankToNull()?.lowercase() ?: "hash",
                ollamaBaseUrl = env["OLLAMA_BASE_URL"]?.blankToNull()?.trimEnd('/') ?: "http://localhost:11434",
                ollamaEmbedModel = env["OLLAMA_EMBED_MODEL"]?.blankToNull() ?: "nomic-embed-text",
                fixedChunkTokens = fixedSize,
                fixedChunkOverlap = fixedOverlap,
                structuredMaxTokens = env["STRUCTURED_MAX_TOKENS"]?.toIntOrNull()?.coerceIn(120, 3000) ?: 700,
                retrievalTopK = env["RETRIEVAL_TOP_K"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5,
                corpusMaxFiles = env["CORPUS_MAX_FILES"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 140,
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
