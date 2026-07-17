package ru.ai.course.day31.developerassistant

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RagIndexManager(
    private val config: AppConfig,
    private val loader: ProjectDocumentLoader = ProjectDocumentLoader(config),
    private val chunker: StructuredChunker = StructuredChunker(config.chunkMaxTokens),
    private val embeddings: EmbeddingClient = EmbeddingFactory.create(config),
) {
    fun ensureIndex(): RagIndex {
        val corpus = loader.load()
        val existing = loadExisting()
        if (existing != null && isCompatible(existing, corpus)) return existing
        return rebuild(corpus)
    }

    fun corpusFingerprint(manifest: List<CorpusManifestEntry>): String =
        TextTools.sha256(
            manifest
                .sortedBy { it.source }
                .joinToString("\n") { "${it.source}\u0000${it.contentSha256}\u0000${it.bytes}" },
        )

    private fun loadExisting(): RagIndex? {
        if (!config.indexFile.exists()) return null
        return runCatching {
            AppJson.tolerant.decodeFromString<RagIndex>(config.indexFile.readText())
        }.getOrNull()
    }

    private fun isCompatible(index: RagIndex, corpus: LoadedProjectCorpus): Boolean {
        if (index.embeddingBackend != embeddings.backend ||
            index.embeddingModel != embeddings.model ||
            index.chunkMaxTokens != config.chunkMaxTokens ||
            index.embeddingDimensions <= 0 ||
            index.chunks.isEmpty()
        ) {
            return false
        }
        if (corpusFingerprint(index.manifest) != corpusFingerprint(corpus.manifest)) return false
        if (index.manifest != corpus.manifest) return false
        val expectedDrafts = corpus.documents.flatMap(chunker::chunk)
        if (index.chunks.size != expectedDrafts.size) return false
        return index.chunks.zip(expectedDrafts).all { (chunk, draft) ->
            chunk.embedding.size == index.embeddingDimensions &&
                chunk.metadata == draft.metadata &&
                chunk.text == draft.text &&
                chunk.metadata.source in config.allowedDocuments
        }
    }

    private fun rebuild(corpus: LoadedProjectCorpus): RagIndex {
        val drafts = corpus.documents.flatMap(chunker::chunk)
        require(drafts.isNotEmpty()) { "No chunks were produced from the approved corpus." }
        val vectors = embeddings.embed(drafts.map { it.text })
        require(vectors.size == drafts.size) {
            "Embedding provider returned ${vectors.size} vectors for ${drafts.size} chunks."
        }
        val dimensions = vectors.firstOrNull()?.size ?: 0
        require(dimensions > 0 && vectors.all { it.size == dimensions }) {
            "Embedding provider returned empty or inconsistent dimensions."
        }
        val index = RagIndex(
            generatedAtIso = Instant.now().toString(),
            embeddingBackend = embeddings.backend,
            embeddingModel = embeddings.model,
            chunkMaxTokens = config.chunkMaxTokens,
            embeddingDimensions = dimensions,
            manifest = corpus.manifest,
            chunks = drafts.zip(vectors) { draft, vector -> IndexedChunk(draft.metadata, draft.text, vector) },
        )
        save(index)
        return index
    }

    private fun save(index: RagIndex) {
        config.indexFile.parent?.createDirectories()
        val temporary = config.indexFile.resolveSibling("${config.indexFile.fileName}.tmp")
        temporary.writeText(AppJson.pretty.encodeToString(index))
        try {
            Files.move(temporary, config.indexFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, config.indexFile, REPLACE_EXISTING)
        }
    }
}
