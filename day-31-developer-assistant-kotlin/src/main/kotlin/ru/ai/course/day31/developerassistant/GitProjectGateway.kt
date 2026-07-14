package ru.ai.course.day31.developerassistant

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class GitProjectGateway(
    projectRoot: Path,
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    private val root: Path = projectRoot.toRealPath()

    init {
        val gitRoot = Path(runGit(listOf("rev-parse", "--show-toplevel"), maxChars = 8_192).singleLine())
            .toRealPath()
        require(gitRoot == root) {
            "PROJECT_ROOT must be the git top-level directory. Configured: $root; git reports: $gitRoot."
        }
    }

    fun currentBranch(): GitBranchInfo {
        val branch = runGit(listOf("branch", "--show-current"), maxChars = 1_024).trim()
        if (branch.isNotBlank()) {
            require('\n' !in branch && '\r' !in branch) { "Git returned an invalid branch name." }
            return GitBranchInfo(displayName = branch, detached = false)
        }
        val shortSha = runGit(listOf("rev-parse", "--short=12", "HEAD"), maxChars = 1_024).trim()
        require(Regex("[0-9a-fA-F]{4,40}").matches(shortSha)) { "Git returned an invalid detached HEAD SHA." }
        return GitBranchInfo(displayName = "detached@$shortSha", detached = true, shortSha = shortSha)
    }

    fun listTrackedFiles(prefix: String?, limit: Int): GitFileList {
        require(limit in 1..200) { "limit must be between 1 and 200." }
        val normalizedPrefix = normalizePrefix(prefix)
        val arguments = buildList {
            add("ls-files")
            if (normalizedPrefix != null) {
                add("--")
                add(normalizedPrefix)
            }
        }
        val lines = runGit(arguments, maxChars = 2_000_000)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(limit + 1)
            .toList()
        return GitFileList(
            prefix = normalizedPrefix,
            files = lines.take(limit),
            truncated = lines.size > limit,
        )
    }

    private fun normalizePrefix(prefix: String?): String? {
        val value = prefix?.trim()?.replace('\\', '/')?.trim('/')?.takeIf(String::isNotEmpty) ?: return null
        require(value.length <= 256) { "prefix is too long." }
        val path = Path(value)
        require(!path.isAbsolute()) { "prefix must be relative to PROJECT_ROOT." }
        require(path.none { it.toString() == ".." }) { "prefix must not contain '..'." }
        require('\n' !in value && '\r' !in value && '\u0000' !in value) { "prefix contains invalid characters." }
        return value
    }

    private fun runGit(arguments: List<String>, maxChars: Int): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder(minOf(maxChars, 16_384))
        var overflow = false
        val reader = Thread.ofVirtual().start {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length + line.length + 1 <= maxChars) {
                        output.appendLine(line)
                    } else {
                        overflow = true
                    }
                }
            }
        }

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(1_000)
            throw GitCommandException("git ${arguments.joinToString(" ")} timed out after ${timeout.seconds}s.")
        }
        reader.join(1_000)
        if (reader.isAlive) throw GitCommandException("Could not finish reading git output.")
        if (process.exitValue() != 0) {
            throw GitCommandException(
                "git ${arguments.joinToString(" ")} failed with exit ${process.exitValue()}: ${output.toString().singleLine()}",
            )
        }
        if (overflow) throw GitCommandException("git ${arguments.firstOrNull()} output exceeded the safe limit.")
        return output.toString()
    }
}

class GitCommandException(message: String) : IllegalStateException(message)
