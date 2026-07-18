package ru.ai.course.day35.releaseprep
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
data class ProcessResult(val exitCode: Int, val output: ByteArray, val durationMillis: Long) {
    fun text(): String = decodeUtf8(output)
}
class BoundedCommandRunner(
    private val root: Path,
    private val parentEnvironment: Map<String, String> = System.getenv(),
) {
    fun run(
        argv: List<String>,
        timeout: Duration = Duration.ofMinutes(2),
        cap: Int = Limits.MANIFEST_STREAM_BYTES,
        scrubEnvironment: Boolean = true,
    ): ProcessResult {
        require(argv.isNotEmpty() && cap > 0)
        val processBuilder = ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true)
        if (scrubEnvironment) {
            processBuilder.environment().clear()
            processBuilder.environment().putAll(scrubbedEnvironment(parentEnvironment))
        }
        val started = System.nanoTime()
        val process = processBuilder.start()
        val executor = Executors.newSingleThreadExecutor()
        val outputFuture = executor.submit<ByteArray> {
            try {
                process.inputStream.use { input ->
                    val output = ByteArrayOutputStream(minOf(cap, 8192))
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= cap) { "Command output exceeds $cap bytes" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (failure: Throwable) {
                process.destroyForcibly()
                throw failure
            }
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                error("Command timed out after ${timeout.toSeconds()}s: ${argv.first()}")
            }
            val output = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (failure: ExecutionException) {
                throw (failure.cause as? RuntimeException ?: RuntimeException(failure.cause))
            }
            return ProcessResult(process.exitValue(), output, (System.nanoTime() - started) / 1_000_000)
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }
    companion object {
        private val allowed = setOf("HOME", "PATH", "TERM", "LANG", "LC_ALL", "TMPDIR")
        fun scrubbedEnvironment(source: Map<String, String>): Map<String, String> =
            source.filterKeys(allowed::contains)
    }
}
data class InspectedRelease(
    val baseRef: String,
    val baseSha: String,
    val mergeBaseSha: String,
    val headSha: String,
    val snapshot: SnapshotFingerprint,
    val manifest: ReleaseManifest,
    val module: String,
) {
    val buildTask: String get() = ":$module:build"
}
class GitReleaseInspector(
    rootInput: Path,
    private val runner: BoundedCommandRunner = BoundedCommandRunner(rootInput.toRealPath()),
) {
    val root: Path = rootInput.toRealPath()
    fun inspect(baseRef: String): InspectedRelease {
        validateRef(baseRef)
        require(git("rev-parse", "--show-toplevel").trim() == root.toString()) { "Wrong Git worktree" }
        val snapshot = snapshot()
        val baseSha = git("rev-parse", "--verify", "$baseRef^{commit}").trim().also(::requireSha)
        val headSha = snapshot.headSha
        val mergeBase = git("merge-base", baseSha, headSha).trim().also(::requireSha)
        val manifest = manifest(baseSha, mergeBase, headSha)
        val settings = git("show", "$headSha:settings.gradle.kts", cap = 128 * 1024)
        val module = ReleaseShapePolicy.validate(manifest.items.map { it.path }, settings)
        ReleaseShapePolicy.requireRegularModuleFiles(root, module)
        return InspectedRelease(baseRef, baseSha, mergeBase, headSha, snapshot, manifest, module)
    }
    fun snapshot(): SnapshotFingerprint {
        val branch = git("symbolic-ref", "--quiet", "--short", "HEAD").trim()
        ContentPolicy.validateText(branch, "branch", 200)
        require(branch.isNotEmpty()) { "Detached HEAD is unsupported" }
        val head = git("rev-parse", "HEAD").trim().also(::requireSha)
        val headTree = git("rev-parse", "HEAD^{tree}").trim().also(::requireSha)
        val indexTree = git("write-tree").trim().also(::requireSha)
        val statusResult = runner.run(
            listOf("git", "status", "--porcelain=v2", "-z", "--untracked-files=normal"),
            cap = Limits.MANIFEST_STREAM_BYTES,
        )
        require(statusResult.exitCode == 0) { "git status failed" }
        require(statusResult.output.isEmpty()) { "Tracked, staged or untracked-unignored changes are forbidden" }
        require(headTree == indexTree) { "HEAD tree differs from index tree" }
        return SnapshotFingerprint(root.toString(), branch, head, headTree, indexTree, sha256Bytes(statusResult.output))
    }
    fun runChecks(release: InspectedRelease): List<CheckResult> {
        SnapshotGate.requireStable(release.snapshot, snapshot())
        val diff = checked(
            listOf("git", "diff", "--check", "${release.mergeBaseSha}..${release.headSha}"),
            Duration.ofMinutes(1),
            128 * 1024,
        )
        SnapshotGate.requireStable(release.snapshot, snapshot())
        val build = checked(
            listOf("./gradlew", "--no-daemon", "--console=plain", release.buildTask),
            Duration.ofMinutes(10),
            256 * 1024,
        )
        return listOf(
            CheckResult("git-diff-check", "git diff --check <merge-base>..<head>", diff.exitCode, diff.durationMillis),
            CheckResult(release.buildTask, "./gradlew --no-daemon --console=plain ${release.buildTask}", build.exitCode, build.durationMillis),
        ).also { require(it.all(CheckResult::passed)) { "Deterministic release checks failed" } }
    }
    internal fun manifest(baseSha: String, mergeBase: String, headSha: String): ReleaseManifest {
        val range = "$mergeBase..$headSha"
        val raw = checked(listOf("git", "diff", "--no-renames", "--abbrev=40", "--raw", "-z", range)).output
        val names = checked(listOf("git", "diff", "--no-renames", "--name-status", "-z", range)).output
        val stats = checked(listOf("git", "diff", "--no-renames", "--numstat", "-z", range)).output
        val rawMap = parseRaw(raw)
        val nameMap = parseNames(names)
        val statMap = parseStats(stats)
        require(rawMap.keys == nameMap.keys && rawMap.keys == statMap.keys) { "Git manifest streams disagree" }
        require(rawMap.size in 1..Limits.MAX_FILES) { "Manifest must contain 1..${Limits.MAX_FILES} files" }
        val items = rawMap.keys.sorted().map { path ->
            ContentPolicy.validatePath(path)
            val rawItem = rawMap.getValue(path)
            val status = nameMap.getValue(path)
            require(status == rawItem.status) { "Git status mismatch for $path" }
            val stat = statMap.getValue(path)
            val itemFingerprint = sha256(
                path, status, stat.first.toString(), stat.second.toString(), stat.third.toString(),
                rawItem.oldObjectId, rawItem.newObjectId, rawItem.oldMode, rawItem.newMode,
            )
            ManifestItem(
                path, status, stat.first, stat.second, stat.third,
                rawItem.oldObjectId.takeUnless(::isZeroSha), rawItem.newObjectId.takeUnless(::isZeroSha),
                rawItem.oldMode, rawItem.newMode, itemFingerprint,
            )
        }
        return ReleaseManifest(baseSha, mergeBase, headSha, items, sha256(baseSha, mergeBase, headSha, *items.map { it.fingerprint }.toTypedArray()))
    }
    private fun checked(argv: List<String>, timeout: Duration = Duration.ofMinutes(2), cap: Int = Limits.MANIFEST_STREAM_BYTES): ProcessResult =
        runner.run(argv, timeout, cap).also {
            require(it.exitCode == 0) { "Command failed (${it.exitCode}): ${argv.take(3).joinToString(" ")}" }
        }
    private fun git(vararg args: String, cap: Int = Limits.MANIFEST_STREAM_BYTES): String =
        checked(listOf("git", *args), cap = cap).text()
    companion object {
        fun validateRef(ref: String) {
            ContentPolicy.validateText(ref, "base ref", 200)
            require(ref.matches(Regex("[A-Za-z0-9._/-]+")) && !ref.startsWith("-") && ".." !in ref) {
                "Invalid base ref"
            }
        }
    }
}
object ReleaseShapePolicy {
    private val rootPaths = setOf(".gitignore", "AGENTS.md", "README.md", "settings.gradle.kts", "skills/course-continuity/SKILL.md")
    private val modulePattern = Regex("day-[0-9]{2}-[a-z0-9-]+-kotlin")
    fun validate(paths: List<String>, headSettings: String): String {
        require(paths.isNotEmpty())
        paths.forEach(ContentPolicy::validatePath)
        val modules = paths.mapNotNull { path ->
            path.substringBefore('/').takeIf(modulePattern::matches)
        }.toSet()
        require(modules.size == 1) { "Exactly one changed day module is required" }
        val module = modules.single()
        require(paths.all { it in rootPaths || it.startsWith("$module/") }) { "Path outside release allowlist" }
        require("$module/README.md" in paths) { "Changed module README is required" }
        require(paths.none(::isSensitiveGenerated)) { "Sensitive/generated path is forbidden" }
        val includes = headSettings.lineSequence().mapNotNull {
            Regex("""^\s*include\("([^"]+)"\)\s*$""").matchEntire(it)?.groupValues?.get(1)
        }.toList()
        require(includes.count { it == module } == 1) { "Module must have one exact HEAD settings include" }
        return module
    }
    fun requireRegularModuleFiles(root: Path, module: String) {
        listOf(
            root.resolve("settings.gradle.kts"),
            root.resolve(module),
            root.resolve("$module/build.gradle.kts"),
            root.resolve("$module/README.md"),
            root.resolve("$module/release-brief.json"),
        )
            .forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Symlink is forbidden: $path" }
                require(
                    if (path == root.resolve(module)) Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    else Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                ) { "Required regular path is missing: $path" }
            }
    }
    private fun isSensitiveGenerated(path: String): Boolean {
        if (path.endsWith("/.env.example")) return false
        val parts = path.split('/')
        return parts.any { it in setOf(".env", ".certs", "build", "reports", "runtime", ".gradle", ".git") } ||
            path.endsWith(".tmp")
    }
}
internal data class RawItem(
    val oldMode: String,
    val newMode: String,
    val oldObjectId: String,
    val newObjectId: String,
    val status: String,
)
internal fun parseRaw(bytes: ByteArray): Map<String, RawItem> {
    val tokens = splitNul(bytes)
    require(tokens.size % 2 == 0) { "Malformed raw Git stream" }
    return tokens.chunked(2).associate { (metadata, path) ->
        val fields = metadata.removePrefix(":").split(' ')
        require(fields.size == 5 && metadata.startsWith(":")) { "Malformed raw Git metadata" }
        require(fields[0].matches(Regex("[0-7]{6}")) && fields[1].matches(Regex("[0-7]{6}"))) { "Malformed Git mode" }
        require(fields[2].matches(Regex("[0-9a-f]{40}")) && fields[3].matches(Regex("[0-9a-f]{40}"))) { "Malformed Git object ID" }
        val status = fields[4]
        require(status in setOf("A", "M", "D", "T")) { "Unsupported Git status: $status" }
        path to RawItem(fields[0], fields[1], fields[2], fields[3], status)
    }.also { require(it.size * 2 == tokens.size) { "Duplicate raw path" } }
}
internal fun parseNames(bytes: ByteArray): Map<String, String> {
    val tokens = splitNul(bytes)
    require(tokens.size % 2 == 0) { "Malformed name-status stream" }
    return tokens.chunked(2).associate { (status, path) ->
        require(status in setOf("A", "M", "D", "T")) { "Unsupported name status" }
        path to status
    }.also { require(it.size * 2 == tokens.size) { "Duplicate name-status path" } }
}
internal fun parseStats(bytes: ByteArray): Map<String, Triple<Int, Int, Boolean>> =
    splitNul(bytes).associate { token ->
        val fields = token.split('\t', limit = 3)
        require(fields.size == 3) { "Malformed numstat stream" }
        val binary = fields[0] == "-" && fields[1] == "-"
        require(binary || (fields[0].all(Char::isDigit) && fields[1].all(Char::isDigit))) { "Malformed numstat counts" }
        fields[2] to Triple(if (binary) 0 else fields[0].toInt(), if (binary) 0 else fields[1].toInt(), binary)
    }.also { require(it.size == splitNul(bytes).size) { "Duplicate numstat path" } }
private fun splitNul(bytes: ByteArray): List<String> {
    if (bytes.isEmpty()) return emptyList()
    require(bytes.last() == 0.toByte()) { "NUL-terminated Git stream required" }
    return bytes.dropLast(1).toByteArray().toList().splitOnZero().map { decodeUtf8(it.toByteArray()) }
}
private fun List<Byte>.splitOnZero(): List<List<Byte>> {
    val result = mutableListOf<List<Byte>>()
    var start = 0
    indices.forEach { index ->
        if (this[index] == 0.toByte()) {
            result += subList(start, index)
            start = index + 1
        }
    }
    result += subList(start, size)
    return result
}
private fun requireSha(value: String) = require(value.matches(Regex("[0-9a-f]{40}"))) { "Expected full Git SHA" }
private fun isZeroSha(value: String) = value.all { it == '0' }
internal fun decodeUtf8(bytes: ByteArray): String =
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
