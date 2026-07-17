package ru.ai.course.day33.supportassistant

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RagIndexBoundedCacheTest {
    @Test
    fun `oversized cache is ignored and rebuilt without decoding`() {
        val config = TestSupport.config()
        Files.createDirectories(config.ragIndexPath.parent)
        Files.newByteChannel(
            config.ragIndexPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.position(5_000_000)
            channel.write(ByteBuffer.wrap(byteArrayOf('{'.code.toByte())))
        }

        val rebuilt = ragIndex(config).ensure()

        assertTrue(rebuilt.chunks.isNotEmpty())
        assertTrue(Files.size(config.ragIndexPath) < 5_000_000)
        assertFalse(Files.isSymbolicLink(config.ragIndexPath))
    }

    @Test
    fun `symlink cache is ignored and replaced without reading target`() {
        val config = TestSupport.config()
        val target = config.ragIndexPath.parent.resolve("foreign-index.json")
        Files.createDirectories(config.ragIndexPath.parent)
        Files.writeString(target, "{foreign-cache}")
        Files.createSymbolicLink(config.ragIndexPath, target)

        val rebuilt = ragIndex(config).ensure()

        assertTrue(rebuilt.chunks.isNotEmpty())
        assertFalse(Files.isSymbolicLink(config.ragIndexPath))
        assertTrue(Files.readString(target) == "{foreign-cache}")
    }

    @Test
    fun `legacy predictable temp symlink is never followed or moved`() {
        val config = TestSupport.config()
        val directory = config.ragIndexPath.parent
        val legacyTemp = config.ragIndexPath.resolveSibling("${config.ragIndexPath.fileName}.tmp")
        val victim = directory.resolve("victim.txt")
        Files.createDirectories(directory)
        Files.writeString(victim, "DO NOT OVERWRITE")
        Files.createSymbolicLink(legacyTemp, victim)

        val rebuilt = ragIndex(config).ensure()

        assertTrue(rebuilt.chunks.isNotEmpty())
        assertEquals("DO NOT OVERWRITE", Files.readString(victim))
        assertTrue(Files.isSymbolicLink(legacyTemp))
        assertTrue(Files.isRegularFile(config.ragIndexPath, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(config.ragIndexPath))
        Files.list(directory).use { entries ->
            assertEquals(
                emptyList(),
                entries
                    .map { it.fileName.toString() }
                    .filter { it.startsWith(".rag-index.json.") && it.endsWith(".tmp") }
                    .toList(),
            )
        }
    }

    @Test
    fun `intermediate cache directory symlink is rejected without writing outside root`() {
        val fakeRoot = TestSupport.tempDirectory("cache-root-")
        val outside = fakeRoot.parent.resolve("${fakeRoot.fileName}-outside")
        val cacheLink = fakeRoot.resolve("cache-link")
        Files.createDirectories(outside)
        Files.createSymbolicLink(cacheLink, outside)
        val config = TestSupport.config(
            values = mapOf(
                "REPOSITORY_ROOT" to fakeRoot.toString(),
                "SUPPORT_RAG_INDEX_PATH" to "cache-link/sub/rag-index.json",
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ragIndex(config).ensure()
        }

        assertTrue(config.ragIndexPath.startsWith(config.repositoryRoot))
        assertTrue("non-symlink directory" in requireNotNull(failure.message))
        assertFalse(Files.exists(outside.resolve("sub"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(outside.resolve("sub/rag-index.json"), LinkOption.NOFOLLOW_LINKS))
        Files.walk(outside).use { entries ->
            assertFalse(
                entries.anyMatch {
                    it.fileName.toString().startsWith(".rag-index.json.") &&
                        it.fileName.toString().endsWith(".tmp")
                },
            )
        }
    }

    @Test
    fun `cache text with an unchanged expected fingerprint is rebuilt`() {
        assertPoisonedCacheRebuilt { baseline ->
            baseline.withFirstChunk { it.copy(text = "${it.text}\n\nPOISONED CACHE TEXT") }
        }
    }

    @Test
    fun `cache with a duplicate source ID is rebuilt`() {
        assertPoisonedCacheRebuilt { baseline ->
            baseline.copy(chunks = baseline.chunks + baseline.chunks.first())
        }
    }

    @Test
    fun `cache with a changed document path is rebuilt`() {
        assertPoisonedCacheRebuilt { baseline ->
            baseline.withFirstChunk { it.copy(documentPath = "knowledge/poisoned.md") }
        }
    }

    @Test
    fun `cache with a changed heading is rebuilt`() {
        assertPoisonedCacheRebuilt { baseline ->
            baseline.withFirstChunk { it.copy(heading = "Poisoned heading") }
        }
    }

    @Test
    fun `cache with a stale deterministic embedding is rebuilt`() {
        assertPoisonedCacheRebuilt { baseline ->
            baseline.withFirstChunk { chunk ->
                val poisonedEmbedding = chunk.embedding.toMutableList()
                poisonedEmbedding[0] += 0.25
                chunk.copy(embedding = poisonedEmbedding)
            }
        }
    }

    private fun assertPoisonedCacheRebuilt(poison: (RagIndexFile) -> RagIndexFile) {
        val config = TestSupport.config()
        val authoritative = ragIndex(config).ensure()
        writeIndex(config, poison(authoritative))

        val rebuilt = ragIndex(config).ensure()

        assertEquals(authoritative, rebuilt)
        assertEquals(authoritative, readIndex(config))
    }

    private fun RagIndexFile.withFirstChunk(transform: (KnowledgeChunk) -> KnowledgeChunk): RagIndexFile =
        copy(chunks = listOf(transform(chunks.first())) + chunks.drop(1))

    private fun writeIndex(config: AppConfig, index: RagIndexFile) {
        Files.writeString(config.ragIndexPath, SupportJson.strict.encodeToString(index))
    }

    private fun readIndex(config: AppConfig): RagIndexFile =
        SupportJson.strict.decodeFromString(Files.readString(config.ragIndexPath))

    private fun ragIndex(config: AppConfig): RagIndex {
        val embeddings = HashEmbeddingClient(config.limits.embeddingDimensions)
        return RagIndex(
            loader = KnowledgeDocumentLoader(config.knowledgeDirectory),
            chunker = StructuredChunker(config.limits.maxChunkChars),
            embeddings = embeddings,
            dimensions = config.limits.embeddingDimensions,
            repositoryRoot = config.repositoryRoot,
            indexPath = config.ragIndexPath,
        )
    }
}
