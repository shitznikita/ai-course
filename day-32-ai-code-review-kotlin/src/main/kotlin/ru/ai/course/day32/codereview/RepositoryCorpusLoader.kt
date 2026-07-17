package ru.ai.course.day32.codereview

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

fun interface TrackedFileProvider {
    fun trackedFiles(root: Path): List<String>
}

class GitTrackedFileProvider : TrackedFileProvider {
    override fun trackedFiles(root: Path): List<String> {
        val process = ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
            .redirectErrorStream(true)
            .start()
        val bytes = process.inputStream.readAllBytes()
        val exit = process.waitFor()
        require(exit == 0) { "git ls-files failed with exit code $exit." }
        return String(bytes, StandardCharsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotBlank)
    }
}

class RepositoryCorpusLoader(
    private val root: Path,
    private val limits: ReviewLimits,
    private val trackedFileProvider: TrackedFileProvider = GitTrackedFileProvider(),
    private val cloudContentPolicy: CloudContentPolicy = CloudContentPolicy(),
) {
    fun load(changedPaths: Collection<String>): LoadedCorpus {
        val tracked = trackedFileProvider.trackedFiles(root)
            .distinct()
            .onEach(AppConfig::requireSafePath)
        val changedRoots = changedPaths.map { it.substringBefore('/') }.toSet()
        val skipped = linkedMapOf<String, Int>()
        fun skip(reason: String) {
            skipped[reason] = skipped.getOrDefault(reason, 0) + 1
        }

        val candidates = tracked.mapNotNull { relative ->
            if (!isAllowed(relative)) {
                skip("excluded")
                return@mapNotNull null
            }
            cloudContentPolicy.requireSafePath(relative)
            val path = root.resolve(relative).normalize()
            if (!path.startsWith(root) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                skip("not-regular-or-symlink")
                return@mapNotNull null
            }
            val size = runCatching { Files.size(path) }.getOrElse {
                skip("unreadable")
                return@mapNotNull null
            }
            if (size > limits.maxCorpusFileBytes) {
                skip("oversized")
                return@mapNotNull null
            }
            Candidate(relative, path, size, priority(relative, changedRoots))
        }.sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.relative })

        val documents = mutableListOf<CorpusDocument>()
        var bytes = 0L
        var truncated = false
        for (candidate in candidates) {
            if (documents.size >= limits.maxCorpusFiles || bytes + candidate.size > limits.maxCorpusBytes) {
                skip("corpus-limit")
                truncated = true
                continue
            }
            val raw = try {
                Files.readAllBytes(candidate.path)
            } catch (_: Exception) {
                skip("unreadable")
                continue
            }
            val content = decodeText(raw)
            if (content == null || '\u0000' in content) {
                skip("binary-or-invalid-utf8")
                continue
            }
            cloudContentPolicy.requireSafeContent(content)
            documents += CorpusDocument(
                path = candidate.relative,
                content = content,
                category = category(candidate.relative),
                priority = candidate.priority,
            )
            bytes += raw.size
        }
        return LoadedCorpus(
            documents = documents,
            metrics = CorpusMetrics(
                trackedFiles = tracked.size,
                includedFiles = documents.size,
                includedBytes = bytes,
                skippedFiles = tracked.size - documents.size,
                truncated = truncated,
                skippedReasons = skipped.toMap(),
            ),
        )
    }

    private fun isAllowed(path: String): Boolean {
        val lower = path.lowercase()
        val segments = lower.split('/')
        val name = segments.last()
        if (segments.any { it in excludedSegments }) return false
        if (segments.zipWithNext().any { (left, right) -> left == "src" && right == "test" }) return false
        if (path in setOf("README.md", "AGENTS.md", "settings.gradle.kts", "build.gradle.kts")) return true
        if (name in setOf("readme.md", "agents.md")) return true
        if (lower.startsWith("docs/") && documentationExtensions.any(lower::endsWith)) return true
        if (lower.startsWith(".github/workflows/") && workflowExtensions.any(lower::endsWith)) return true
        if ("src" in segments && sourceExtensions.any(lower::endsWith)) return true
        if (name in setOf("build.gradle.kts", "settings.gradle.kts", "dockerfile", "makefile")) return true
        if (lower == "gradle/libs.versions.toml") return true
        return false
    }

    private fun priority(path: String, changedRoots: Set<String>): Int = when {
        path == "AGENTS.md" || path == "README.md" -> 0
        path.startsWith("docs/") || path.endsWith("/README.md") -> 1
        path.substringBefore('/') in changedRoots -> 2
        else -> 3
    }

    private fun category(path: String): CorpusCategory {
        val lower = path.lowercase()
        return if (
            lower.endsWith(".md") ||
            lower.startsWith("docs/") ||
            lower.endsWith(".yaml") && "api" in lower ||
            lower.endsWith(".yml") && "api" in lower
        ) {
            CorpusCategory.DOCUMENTATION
        } else {
            CorpusCategory.CODE
        }
    }

    private fun decodeText(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private data class Candidate(
        val relative: String,
        val path: Path,
        val size: Long,
        val priority: Int,
    )

    companion object {
        private val excludedSegments = setOf(
            ".git", ".github-cache", ".gradle", ".kotlin", ".idea", ".certs", "build", "out",
            "runtime", "reports", "index", "state", "node_modules", "generated", "target",
        )
        private val documentationExtensions = setOf(".md", ".yaml", ".yml", ".json")
        private val workflowExtensions = setOf(".yaml", ".yml")
        private val sourceExtensions = setOf(".kt", ".kts", ".java")
    }
}
