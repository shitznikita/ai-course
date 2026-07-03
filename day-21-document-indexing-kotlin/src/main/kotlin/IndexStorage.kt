import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class IndexStorage(private val config: AppConfig) {
    fun indexPath(strategy: String): Path = config.indexDir.resolve("$strategy-index.json")
    fun comparisonPath(): Path = config.indexDir.resolve("chunking-comparison.md")

    fun saveIndex(
        strategy: ChunkingStrategy,
        corpus: LoadedCorpus,
        drafts: List<ChunkDraft>,
        embeddingClient: EmbeddingClient,
    ): IndexBuildResult {
        config.indexDir.createDirectories()
        val embeddings = embeddingClient.embed(drafts.map { it.text })
        val chunks = drafts.zip(embeddings).map { (draft, embedding) ->
            IndexedChunk(draft.metadata, draft.text, embedding)
        }
        val index = DocumentIndex(
            generatedAtIso = Instant.now().toString(),
            strategy = strategy.name,
            embeddingBackend = embeddingClient.backend,
            embeddingModel = embeddingClient.model,
            sourceDocumentCount = corpus.documents.size,
            chunkCount = chunks.size,
            embeddingDimensions = chunks.firstOrNull()?.embedding?.size ?: 0,
            config = IndexConfigSnapshot(
                documentsDir = corpus.documentsDir,
                fixedChunkTokens = config.fixedChunkTokens,
                fixedChunkOverlap = config.fixedChunkOverlap,
                structuredMaxTokens = config.structuredMaxTokens,
            ),
            chunks = chunks,
        )
        val path = indexPath(strategy.name)
        path.writeText(IndexJson.pretty.encodeToString(index))
        return IndexBuildResult(index, path)
    }

    fun loadIndex(strategy: String): DocumentIndex {
        val path = indexPath(strategy)
        check(path.exists()) {
            "Index file not found: ${path.toAbsolutePath()}. Run fixture-demo, ollama-demo, or index first."
        }
        return IndexJson.compact.decodeFromString(path.readText())
    }

    fun saveComparison(markdown: String) {
        config.indexDir.createDirectories()
        comparisonPath().writeText(markdown)
    }
}
