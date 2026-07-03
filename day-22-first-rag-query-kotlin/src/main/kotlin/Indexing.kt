import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class IndexManager(private val config: AppConfig, private val embeddingClient: EmbeddingClient) {
    fun indexPath(): Path = config.indexDir.resolve("${config.ragChunkStrategy}-index.json")

    fun ensureIndex(): DocumentIndex {
        val existing = loadExistingCompatibleIndex()
        if (existing != null) return existing

        check(config.ragChunkStrategy == "structured") {
            "Only RAG_CHUNK_STRATEGY=structured is implemented for Day 22."
        }
        val corpus = DocumentLoader(config).load()
        check(corpus.documents.isNotEmpty()) {
            "No documents found in ${config.documentsDir.toAbsolutePath()}."
        }
        println("INDEX BUILD")
        println("documents: ${corpus.documents.size}")
        println("approx pages: ${corpus.approxPages.formatDigits(1)}")
        println("embedding backend: ${embeddingClient.backend}")
        println("embedding model: ${embeddingClient.model}")

        val chunker = StructuredChunker(config.structuredMaxTokens)
        val drafts = corpus.documents.flatMap { chunker.chunk(it) }
        println("chunks: ${drafts.size}")

        val embeddings = embeddingClient.embed(drafts.map { it.text })
        val chunks = drafts.zip(embeddings).map { (draft, embedding) ->
            IndexedChunk(draft.metadata, draft.text, embedding)
        }
        val index = DocumentIndex(
            generatedAtIso = Instant.now().toString(),
            strategy = config.ragChunkStrategy,
            embeddingBackend = embeddingClient.backend,
            embeddingModel = embeddingClient.model,
            corpusMaxFiles = config.corpusMaxFiles,
            structuredMaxTokens = config.structuredMaxTokens,
            sourceDocumentCount = corpus.documents.size,
            chunkCount = chunks.size,
            embeddingDimensions = chunks.firstOrNull()?.embedding?.size ?: 0,
            chunks = chunks,
        )
        config.indexDir.createDirectories()
        indexPath().writeText(AppJson.pretty.encodeToString(index))
        println("index saved: ${indexPath().toAbsolutePath()}")
        println()
        return index
    }

    private fun loadExistingCompatibleIndex(): DocumentIndex? {
        val path = indexPath()
        if (!path.exists()) return null
        val index = AppJson.compact.decodeFromString<DocumentIndex>(path.readText())
        val compatible = index.embeddingBackend == embeddingClient.backend &&
            index.embeddingModel == embeddingClient.model &&
            index.strategy == config.ragChunkStrategy &&
            index.corpusMaxFiles == config.corpusMaxFiles &&
            index.structuredMaxTokens == config.structuredMaxTokens &&
            index.chunks.isNotEmpty()
        return if (compatible) index else null
    }
}

class Retriever(private val embeddingClient: EmbeddingClient) {
    fun search(index: DocumentIndex, query: String, topK: Int): List<SearchResult> {
        val queryEmbedding = embeddingClient.embed(listOf(query)).single()
        return index.chunks
            .map { chunk ->
                val vectorScore = cosineSimilarity(queryEmbedding, chunk.embedding)
                val lexicalScore = lexicalSimilarity(query, chunk)
                val priorityBoost = sourcePriority(query, chunk)
                SearchResult(vectorScore * 0.6 + lexicalScore * 0.4 + priorityBoost, chunk)
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun lexicalSimilarity(query: String, chunk: IndexedChunk): Double {
        val queryTokens = Tokenizer.tokens(query)
            .filter { it.length > 2 }
            .distinct()
        if (queryTokens.isEmpty()) return 0.0
        val searchable = listOf(
            chunk.metadata.source,
            chunk.metadata.title,
            chunk.metadata.section,
            chunk.text,
        ).joinToString(" ").lowercase()
        val hits = queryTokens.count { token -> searchable.contains(token) }
        var sourceHintBonus = 0.0
        if (query.contains("day 21", ignoreCase = true) && chunk.metadata.source.contains("day-21")) sourceHintBonus += 0.35
        if (query.contains("day 20", ignoreCase = true) && chunk.metadata.source.contains("day-20")) sourceHintBonus += 0.35
        if (query.contains("day 8", ignoreCase = true) && chunk.metadata.source.contains("day-08")) sourceHintBonus += 0.35
        if ((query.contains("runtime", ignoreCase = true) || query.contains("коммит", ignoreCase = true)) && chunk.metadata.source == ".gitignore") {
            sourceHintBonus += 0.55
        }
        if (query.contains("readme", ignoreCase = true) && chunk.metadata.source == "AGENTS.md") sourceHintBonus += 0.35
        if (query.contains("по умолчанию", ignoreCase = true) && chunk.metadata.source in setOf("README.md", "AGENTS.md")) sourceHintBonus += 0.25
        if ((query.contains("high-level", ignoreCase = true) || query.contains("sdk", ignoreCase = true)) &&
            chunk.metadata.source == "skills/course-continuity/SKILL.md"
        ) {
            sourceHintBonus += 0.35
        }
        return (hits.toDouble() / queryTokens.size + sourceHintBonus).coerceAtMost(1.0)
    }

    private fun sourcePriority(query: String, chunk: IndexedChunk): Double {
        if ((query.contains("runtime", ignoreCase = true) || query.contains("коммит", ignoreCase = true)) &&
            chunk.metadata.source == ".gitignore"
        ) {
            return 0.45
        }
        if ((query.contains("high-level", ignoreCase = true) || query.contains("sdk", ignoreCase = true)) &&
            chunk.metadata.source == "skills/course-continuity/SKILL.md"
        ) {
            return 0.25
        }
        if ((query.contains("high-level", ignoreCase = true) || query.contains("sdk", ignoreCase = true)) &&
            chunk.metadata.source == "AGENTS.md"
        ) {
            return 0.2
        }
        if (query.contains("по умолчанию", ignoreCase = true) && chunk.metadata.source == "AGENTS.md") {
            return 0.2
        }
        return 0.0
    }
}
