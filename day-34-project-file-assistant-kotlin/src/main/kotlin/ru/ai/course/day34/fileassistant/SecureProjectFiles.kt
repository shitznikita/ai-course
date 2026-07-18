package ru.ai.course.day34.fileassistant

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Uses directory-relative handles when the provider supports them. Otherwise
 * every path operation is wrapped in NOFOLLOW_LINKS plus parent/file identity
 * snapshots before and after I/O.
 */
class SecureProjectFiles(private val policy: ProjectFilePolicy) {
    private val rootFileKey = Files.readAttributes(
        policy.root,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    ).fileKey()
    private val secureDirectoryStreamsAvailable = Files.newDirectoryStream(policy.root).use {
        it is SecureDirectoryStream<*>
    }

    fun exists(relativePath: String): Boolean =
        if (secureDirectoryStreamsAvailable) {
            secureExists(relativePath)
        } else {
            verifiedPathExists(relativePath)
        }

    fun read(relativePath: String): String =
        if (secureDirectoryStreamsAvailable) {
            secureRead(relativePath)
        } else {
            verifiedPathRead(relativePath)
        }

    fun write(relativePath: String, bytes: ByteArray, expectedDiskSha256: String?) {
        if (secureDirectoryStreamsAvailable) {
            secureWrite(relativePath, bytes, expectedDiskSha256)
        } else {
            verifiedPathWrite(relativePath, bytes, expectedDiskSha256)
        }
    }

    private fun secureExists(relativePath: String): Boolean =
        withParent(relativePath) { parent, name -> state(parent, name) != null }

    private fun secureRead(relativePath: String): String =
        withParent(relativePath) { parent, name ->
            val fileState = state(parent, name) ?: throw NoSuchFileException(relativePath)
            require(fileState.attributes.isRegularFile) { "Project path is not a regular file: $relativePath" }
            require(fileState.attributes.size() <= ProjectFilePolicy.MAX_READ_BYTES) {
                "Project file exceeds read limit: $relativePath"
            }
            readUtf8(parent, name)
        }

    private fun secureWrite(relativePath: String, bytes: ByteArray, expectedDiskSha256: String?) {
        withParent(relativePath) { parent, name ->
            val initialState = state(parent, name)
            if (expectedDiskSha256 == null) {
                require(initialState == null) { "New file appeared before create: $relativePath" }
            } else {
                require(initialState?.attributes?.isRegularFile == true) {
                    "Existing file disappeared or changed type before replace: $relativePath"
                }
                require(expectedDiskSha256 == sha256(readUtf8(parent, name))) {
                    "File changed on disk before replace: $relativePath"
                }
            }

            val permissions = initialState?.permissions
            val tempName = Path.of(".${name.fileName}.day34-${UUID.randomUUID()}.tmp")
            try {
                parent.newByteChannel(
                    tempName,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { channel ->
                    require(channel is FileChannel) { "Secure file channel cannot be flushed." }
                    var buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                permissions?.let { setPermissions(parent, tempName, it) }

                val latestState = state(parent, name)
                if (expectedDiskSha256 == null) {
                    require(latestState == null) { "New file appeared before atomic create: $relativePath" }
                } else {
                    require(latestState?.attributes?.isRegularFile == true) {
                        "Existing file disappeared or changed type before atomic replace: $relativePath"
                    }
                    require(expectedDiskSha256 == sha256(readUtf8(parent, name))) {
                        "File changed immediately before atomic replace: $relativePath"
                    }
                }

                try {
                    parent.move(tempName, parent, name)
                } catch (error: AtomicMoveNotSupportedException) {
                    throw IllegalStateException(
                        "Filesystem does not support secure atomic replacement; original target was preserved.",
                        error,
                    )
                } catch (error: FileAlreadyExistsException) {
                    throw IllegalStateException(
                        "Filesystem refused secure atomic replacement; original target was preserved.",
                        error,
                    )
                }
                require(sha256(readUtf8(parent, name)) == sha256(String(bytes, StandardCharsets.UTF_8))) {
                    "Saved file verification failed: $relativePath"
                }
            } finally {
                if (state(parent, tempName) != null) parent.deleteFile(tempName)
            }
        }
    }

    private fun readUtf8(parent: SecureDirectoryStream<Path>, name: Path): String {
        val output = ByteArrayOutputStream()
        parent.newByteChannel(
            name,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
                require(output.size() <= ProjectFilePolicy.MAX_READ_BYTES) {
                    "Project file exceeds read limit."
                }
                buffer.clear()
            }
        }
        return decodeProjectText(output.toByteArray())
    }

    private fun verifiedPathExists(relativePath: String): Boolean {
        val relative = policy.normalizeFile(relativePath)
        val identity = captureParentIdentity(relative)
        val attributes = pathAttributes(policy.root.resolve(relative))
        require(identity == captureParentIdentity(relative)) { "Project parent changed during exists check." }
        if (attributes != null) require(!attributes.isSymbolicLink) { "Symlink files are not allowed." }
        return attributes != null
    }

    private fun verifiedPathRead(relativePath: String): String {
        val relative = policy.normalizeFile(relativePath)
        return readVerified(relative, captureParentIdentity(relative))
    }

    private fun verifiedPathWrite(relativePath: String, bytes: ByteArray, expectedDiskSha256: String?) {
        val relative = policy.normalizeFile(relativePath)
        val identity = captureParentIdentity(relative)
        val target = policy.root.resolve(relative)
        val initial = pathAttributes(target)
        if (expectedDiskSha256 == null) {
            require(initial == null) { "New file appeared before create: $relativePath" }
        } else {
            require(initial?.isRegularFile == true && !initial.isSymbolicLink) {
                "Existing file disappeared or changed type before replace: $relativePath"
            }
            require(expectedDiskSha256 == sha256(readVerified(relative, identity))) {
                "File changed on disk before replace: $relativePath"
            }
        }

        val permissions = if (initial != null) {
            runCatching { Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        } else {
            null
        }
        val temp = target.parent.resolve(".${target.fileName}.day34-${UUID.randomUUID()}.tmp")
        try {
            Files.newByteChannel(
                temp,
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                require(channel is FileChannel) { "Temporary file channel cannot be flushed." }
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            permissions?.let { Files.setPosixFilePermissions(temp, it) }
            require(identity == captureParentIdentity(relative)) { "Write parent changed before replace." }
            if (expectedDiskSha256 == null) {
                require(pathAttributes(target) == null) { "New file appeared before atomic create: $relativePath" }
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target)
                }
            } else {
                require(expectedDiskSha256 == sha256(readVerified(relative, identity))) {
                    "File changed immediately before atomic replace: $relativePath"
                }
                try {
                    Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            require(identity == captureParentIdentity(relative)) { "Write parent changed during replace." }
            require(sha256(readVerified(relative, identity)) == expectedContentSha(bytes)) {
                "Saved file verification failed: $relativePath"
            }
        } finally {
            if (runCatching { identity == captureParentIdentity(relative) }.getOrDefault(false) &&
                Files.exists(temp, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(temp)
            ) {
                Files.delete(temp)
            }
        }
    }

    private fun readVerified(relative: Path, identity: List<DirectoryStamp>): String {
        require(identity == captureParentIdentity(relative)) { "Project parent changed before read." }
        val target = policy.root.resolve(relative)
        val before = pathAttributes(target) ?: throw NoSuchFileException(relative.toString())
        require(before.isRegularFile && !before.isSymbolicLink) {
            "Project path is not a regular file: $relative"
        }
        require(before.size() <= ProjectFilePolicy.MAX_READ_BYTES) {
            "Project file exceeds read limit: $relative"
        }
        val output = ByteArrayOutputStream()
        Files.newByteChannel(
            target,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
                require(output.size() <= ProjectFilePolicy.MAX_READ_BYTES) {
                    "Project file exceeds read limit."
                }
                buffer.clear()
            }
        }
        require(identity == captureParentIdentity(relative)) { "Project parent changed during read." }
        val after = pathAttributes(target) ?: throw NoSuchFileException(relative.toString())
        require(!after.isSymbolicLink && fileStamp(before) == fileStamp(after)) {
            "Project file changed identity during read: $relative"
        }
        return decodeProjectText(output.toByteArray())
    }

    private fun captureParentIdentity(relative: Path): List<DirectoryStamp> {
        val result = mutableListOf<DirectoryStamp>()
        var current = policy.root
        fun capture(path: Path) {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            require(attributes.isDirectory && !attributes.isSymbolicLink) {
                "Project parent is not a real directory: $path"
            }
            result += DirectoryStamp(
                path = path,
                fileKey = attributes.fileKey(),
                creationMillis = attributes.creationTime().toMillis(),
            )
        }
        capture(current)
        require(rootFileKey == null || rootFileKey == result.single().fileKey) { "Workspace root changed." }
        relative.parent?.forEach { segment ->
            current = current.resolve(segment)
            capture(current)
        }
        return result
    }

    private fun pathAttributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun fileStamp(attributes: BasicFileAttributes): Triple<Any?, Long, Long> =
        Triple(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())

    private fun expectedContentSha(bytes: ByteArray): String =
        sha256(String(bytes, StandardCharsets.UTF_8).normalizedNewlines())

    private fun state(parent: SecureDirectoryStream<Path>, name: Path): SecureFileState? {
        val view = parent.getFileAttributeView(
            name,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("Basic file attributes are unavailable.")
        val attributes = try {
            view.readAttributes()
        } catch (_: NoSuchFileException) {
            return null
        }
        require(!attributes.isSymbolicLink) { "Symlink files are not allowed: $name" }
        val permissions = runCatching {
            parent.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.readAttributes()?.permissions()
        }.getOrNull()
        return SecureFileState(attributes, permissions)
    }

    private fun setPermissions(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        runCatching {
            parent.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )?.setPermissions(permissions)
        }
    }

    private inline fun <T> withParent(
        relativePath: String,
        block: (SecureDirectoryStream<Path>, Path) -> T,
    ): T {
        val relative = policy.normalizeFile(relativePath)
        val opened = mutableListOf<DirectoryStream<Path>>()
        try {
            val rootStream = Files.newDirectoryStream(policy.root)
            opened += rootStream
            var current = rootStream as? SecureDirectoryStream<Path>
                ?: error("Workspace filesystem does not support secure directory-relative access.")
            val openedRootKey = current.getFileAttributeView(BasicFileAttributeView::class.java)
                ?.readAttributes()?.fileKey()
            require(rootFileKey == null || rootFileKey == openedRootKey) { "Workspace root changed." }
            relative.parent?.forEach { segment ->
                val next = current.newDirectoryStream(Path.of(segment.toString()), LinkOption.NOFOLLOW_LINKS)
                opened += next
                current = next
            }
            return block(current, relative.fileName)
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private data class SecureFileState(
        val attributes: BasicFileAttributes,
        val permissions: Set<PosixFilePermission>?,
    )

    private data class DirectoryStamp(
        val path: Path,
        val fileKey: Any?,
        val creationMillis: Long,
    )
}
