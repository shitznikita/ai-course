package ru.ai.course.day34.fileassistant

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class ProjectFilePolicy(root: Path) {
    val root: Path

    init {
        val absolute = root.toAbsolutePath().normalize()
        require(Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) { "Workspace does not exist: $absolute" }
        require(!Files.isSymbolicLink(absolute)) { "Workspace root must not be a symlink." }
        require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) { "Workspace root is not a directory." }
        this.root = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
    }

    fun discover(prefix: String?, limit: Int): FileListResult {
        require(limit in 1..MAX_DISCOVERED_FILES) { "limit must be between 1 and $MAX_DISCOVERED_FILES." }
        val normalizedPrefix = prefix?.takeIf { it.isNotBlank() }?.let(::normalizePrefix)
        val found = mutableListOf<String>()
        var truncated = false
        var visitedEntries = 0

        fun visitDirectory(directory: Path, depth: Int): Boolean {
            if (depth > MAX_DISCOVERY_DEPTH) {
                truncated = true
                return true
            }
            val directoryIdentity = directoryIdentity(directory)
            val children = Files.newDirectoryStream(directory).use { stream ->
                stream.asSequence().sortedBy(::relativeString).toList()
            }
            require(directoryIdentity == directoryIdentity(directory)) {
                "Project directory changed during discovery."
            }
            for (child in children) {
                require(directoryIdentity == directoryIdentity(directory)) {
                    "Project directory changed during discovery."
                }
                visitedEntries++
                if (visitedEntries > MAX_SCANNED_ENTRIES) {
                    truncated = true
                    return true
                }
                if (Files.isSymbolicLink(child)) continue
                val attributes = Files.readAttributes(
                    child,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                val relative = relativeString(child)
                if (attributes.isDirectory) {
                    if (!hasDeniedSegment(relative)) {
                        val childIdentity = directoryIdentity(child)
                        val stop = visitDirectory(child, depth + 1)
                        require(childIdentity == directoryIdentity(child)) {
                            "Nested project directory changed during discovery."
                        }
                        require(directoryIdentity == directoryIdentity(directory)) {
                            "Project directory changed during discovery."
                        }
                        if (stop) return true
                    }
                    continue
                }
                if (!attributes.isRegularFile) continue
                if (normalizedPrefix != null && !relative.startsWith(normalizedPrefix)) continue
                if (!isAllowedRelativeFile(relative)) continue
                if (attributes.size() > MAX_READ_BYTES || !hasValidTextPayload(child)) continue
                require(directoryIdentity == directoryIdentity(directory)) {
                    "Project directory changed while inspecting a file."
                }
                found += relative
                if (found.size > limit) {
                    truncated = true
                    return true
                }
            }
            require(directoryIdentity == directoryIdentity(directory)) {
                "Project directory changed during discovery."
            }
            return false
        }

        visitDirectory(root, 0)

        return FileListResult(files = found.take(limit), truncated = truncated)
    }

    fun resolveExisting(relativePath: String): Path {
        val relative = normalizeFile(relativePath)
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root)) { "Path escapes workspace." }
        verifyExistingSegments(resolved)
        require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            "Project file does not exist: ${relativeString(resolved)}"
        }
        require(Files.size(resolved) <= MAX_READ_BYTES) { "Project file exceeds $MAX_READ_BYTES bytes." }
        require(hasValidTextPayload(resolved)) { "Binary/NUL or malformed UTF-8 content is not allowed." }
        return resolved
    }

    fun resolveWriteTarget(relativePath: String): Path {
        val relative = normalizeFile(relativePath)
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root)) { "Path escapes workspace." }
        verifyWriteParent(resolved)
        require(!Files.isSymbolicLink(resolved)) { "Symlink targets are not allowed." }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                "Write target is not a regular file."
            }
        }
        return resolved
    }

    fun verifyWriteParent(target: Path): Path {
        require(target.startsWith(root)) { "Write target escapes workspace." }
        val parent = target.parent ?: error("A project file must have a parent.")
        verifyExistingSegments(parent)
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            "Write parent does not exist or is not a directory: ${relativeString(parent)}"
        }
        val realParent = parent.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(realParent == parent && realParent.startsWith(root)) {
            "Write parent changed or escaped workspace."
        }
        return realParent
    }

    fun relativeString(path: Path): String =
        root.relativize(path.toAbsolutePath().normalize()).joinToString("/") { it.toString() }

    fun normalizeFile(value: String): Path {
        require(value.isNotBlank()) { "Path must not be blank." }
        require(!value.contains('\\')) { "Use '/' in project-relative paths." }
        val candidate = Path.of(value)
        require(!candidate.isAbsolute) { "Absolute paths are not allowed." }
        val normalized = candidate.normalize()
        val portable = normalized.joinToString("/") { it.toString() }
        require(portable.isNotBlank() && portable != ".") { "Path must name a file." }
        require(!portable.startsWith("../") && portable != "..") { "Path traversal is not allowed." }
        require(isAllowedRelativeFile(portable)) { "Path is excluded by project file policy: $portable" }
        return normalized
    }

    private fun normalizePrefix(value: String): String {
        require(!value.contains('\\')) { "Use '/' in project-relative prefixes." }
        val candidate = Path.of(value)
        require(!candidate.isAbsolute) { "Absolute prefixes are not allowed." }
        val normalized = candidate.normalize().joinToString("/") { it.toString() }
        require(normalized != ".." && !normalized.startsWith("../")) { "Prefix traversal is not allowed." }
        require(!hasDeniedSegment(normalized)) { "Prefix is excluded by project file policy." }
        return normalized.trimEnd('/')
    }

    private fun isAllowedRelativeFile(relative: String): Boolean {
        if (hasDeniedSegment(relative)) return false
        val name = relative.substringAfterLast('/')
        val lower = name.lowercase()
        if (lower.startsWith(".env")) return false
        if (SECRET_NAMES.any { lower == it || lower.contains(it) }) return false
        if (lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12") ||
            lower.endsWith(".pfx") || lower.endsWith(".jks") || lower.endsWith(".keystore")
        ) return false
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in ALLOWED_EXTENSIONS || (extension.isEmpty() && name in ALLOWED_EXTENSIONLESS)
    }

    private fun hasDeniedSegment(relative: String): Boolean =
        relative.split('/').filter(String::isNotBlank).any { it.lowercase() in DENIED_SEGMENTS }

    private fun verifyExistingSegments(target: Path) {
        require(target.startsWith(root)) { "Path escapes workspace." }
        var current = root
        root.relativize(target).forEach { segment ->
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalArgumentException("Path segment does not exist: ${relativeString(current)}")
            }
            require(!Files.isSymbolicLink(current)) { "Symlink paths are not allowed: ${relativeString(current)}" }
        }
    }

    private fun hasValidTextPayload(path: Path): Boolean {
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes((MAX_READ_BYTES + 1).toInt())
        }
        if (bytes.size > MAX_READ_BYTES) return false
        return runCatching { decodeProjectText(bytes) }.isSuccess
    }

    private fun directoryIdentity(path: Path): DirectoryIdentity {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "Discovery path is not a real directory: ${relativeString(path)}"
        }
        return DirectoryIdentity(attributes.fileKey(), attributes.creationTime().toMillis())
    }

    companion object {
        const val MAX_DISCOVERED_FILES = 200
        const val MAX_READ_BYTES = 256 * 1024L
        const val MAX_WRITE_BYTES = 128 * 1024
        private const val MAX_SCANNED_ENTRIES = 10_000
        private const val MAX_DISCOVERY_DEPTH = 64

        private val DENIED_SEGMENTS = setOf(
            ".git", ".gradle", ".idea", ".certs", "build", "out", "runtime", "reports",
            "node_modules", "target", ".kotlin",
        )
        private val SECRET_NAMES = setOf(
            "id_rsa", "id_ed25519", "credentials", "credential", "secret", "secrets",
            "private-key", "private_key", "access-token", "access_token",
        )
        private val ALLOWED_EXTENSIONS = setOf(
            "kt", "kts", "java", "md", "txt", "json", "yaml", "yml", "xml", "toml",
            "properties", "gradle", "sh", "sql", "graphql", "proto", "csv", "html", "css",
            "js", "ts", "tsx", "jsx", "py", "go", "rs", "rb", "php", "c", "h", "cpp", "hpp",
        )
        private val ALLOWED_EXTENSIONLESS = setOf(
            "README", "CHANGELOG", "LICENSE", "NOTICE", "Dockerfile", "Makefile",
        )
    }

    private data class DirectoryIdentity(
        val fileKey: Any?,
        val creationMillis: Long,
    )
}
