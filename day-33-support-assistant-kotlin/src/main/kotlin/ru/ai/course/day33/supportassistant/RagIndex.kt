package ru.ai.course.day33.supportassistant

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

class RagIndex(
    private val loader: KnowledgeDocumentLoader,
    private val chunker: StructuredChunker,
    private val embeddings: EmbeddingClient,
    private val dimensions: Int,
    private val repositoryRoot: Path,
    private val indexPath: Path,
) {
    fun ensure(): RagIndexFile {
        val cacheLocation = prepareCacheLocation()
        val sourceChunks = loader.load().flatMap(chunker::chunk)
        require(sourceChunks.map(KnowledgeChunk::sourceId).toSet().size == sourceChunks.size) {
            "Fresh knowledge chunks contain duplicate source IDs."
        }
        loadCompatible(sourceChunks, cacheLocation.target)?.let { return it }
        val indexed = sourceChunks.map { it.copy(embedding = embeddings.embed(it.text)) }
        require(indexed.all { it.embedding.size == dimensions }) { "Embedding dimensions are inconsistent." }
        val index = RagIndexFile(
            schemaVersion = 1,
            embeddingModel = "hash-v1",
            dimensions = dimensions,
            chunks = indexed,
        )
        persist(index, cacheLocation)
        return index
    }

    private fun loadCompatible(sourceChunks: List<KnowledgeChunk>, target: Path): RagIndexFile? {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            return null
        }
        val raw = runCatching {
            TextTools.readUtf8Bounded(target, 5_000_000, "RAG index cache")
        }.getOrNull() ?: return null
        val index = runCatching { SupportJson.strict.decodeFromString<RagIndexFile>(raw) }.getOrNull()
            ?: return null
        if (index.schemaVersion != 1 || index.embeddingModel != "hash-v1" || index.dimensions != dimensions) {
            return null
        }
        if (index.chunks.size != sourceChunks.size) return null
        val cachedIds = index.chunks.map(KnowledgeChunk::sourceId)
        if (cachedIds.toSet().size != cachedIds.size) return null
        val cachedById = index.chunks.associateBy(KnowledgeChunk::sourceId)
        if (cachedById.keys != sourceChunks.map(KnowledgeChunk::sourceId).toSet()) return null

        val chunkIndexes = mutableMapOf<Pair<String, String>, Int>()
        val validatedChunks = sourceChunks.map { fresh ->
            val sectionKey = fresh.documentPath to fresh.heading
            val chunkIndex = chunkIndexes.getOrDefault(sectionKey, 0)
            chunkIndexes[sectionKey] = chunkIndex + 1
            val expectedFingerprint = StructuredChunker.fingerprint(
                fresh.documentPath,
                fresh.heading,
                chunkIndex,
                fresh.text,
            )
            if (fresh.fingerprint != expectedFingerprint) return null

            val cached = cachedById.getValue(fresh.sourceId)
            if (
                cached.documentPath != fresh.documentPath ||
                cached.heading != fresh.heading ||
                cached.text != fresh.text ||
                cached.fingerprint != expectedFingerprint ||
                cached.embedding.size != dimensions
            ) {
                return null
            }
            val expectedEmbedding = embeddings.embed(fresh.text)
            if (expectedEmbedding.size != dimensions || cached.embedding != expectedEmbedding) return null
            fresh.copy(embedding = cached.embedding)
        }
        return index.copy(chunks = validatedChunks)
    }

    private fun prepareCacheLocation(): CacheLocation {
        val configuredRoot = repositoryRoot.toAbsolutePath().normalize()
        val realRoot = configuredRoot.toRealPath()
        require(Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(realRoot)) {
            "REPOSITORY_ROOT must resolve to a real directory: $realRoot"
        }
        val configuredTarget = indexPath.toAbsolutePath().normalize()
        require(configuredTarget.startsWith(realRoot) && configuredTarget != realRoot) {
            "RAG index cache must remain inside the canonical repository root: $configuredTarget"
        }
        val relativeTarget = realRoot.relativize(configuredTarget)
        val fileName = requireNotNull(relativeTarget.fileName) { "RAG index cache must name a file." }

        var directory = realRoot
        relativeTarget.parent?.forEach { component ->
            val next = directory.resolve(component.toString())
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next)
                } catch (_: FileAlreadyExistsException) {
                    // A concurrent creator still has to pass the same no-follow validation below.
                }
            }
            require(Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(next)) {
                "RAG index cache path component must be a real non-symlink directory: $next"
            }
            directory = next
        }

        val realDirectory = directory.toRealPath()
        require(realDirectory.startsWith(realRoot)) {
            "RAG index cache directory escapes the canonical repository root: $realDirectory"
        }
        return CacheLocation(realDirectory, realDirectory.resolve(fileName.toString()))
    }

    private fun persist(index: RagIndexFile, location: CacheLocation) {
        var temp: Path? = null
        try {
            val options = setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            val bytes = SupportJson.strict.encodeToString(index).toByteArray(StandardCharsets.UTF_8)
            while (temp == null) {
                val candidate = location.directory.resolve(".${location.target.fileName}.${UUID.randomUUID()}.tmp")
                val channel = try {
                    FileChannel.open(candidate, options)
                } catch (_: FileAlreadyExistsException) {
                    continue
                }
                temp = candidate
                channel.use {
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) it.write(buffer)
                    it.force(true)
                }
            }
            require(Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(temp)) {
                "RAG index temp cache must remain a regular non-symlink file."
            }
            try {
                Files.move(
                    temp,
                    location.target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, location.target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp?.let(Files::deleteIfExists)
        }
    }

    private data class CacheLocation(
        val directory: Path,
        val target: Path,
    )
}
