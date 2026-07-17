package ru.ai.course.day32.codereview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

data class PullRequestSnapshot(
    val metadata: PullRequestMetadata,
    val files: List<ChangedFile>,
    val fullDiff: String?,
    val fullDiffTruncated: Boolean,
)

class GitHubPullRequestClient(
    private val config: AppConfig,
    private val transport: HttpTransport = JavaHttpTransport(),
    private val cloudContentPolicy: CloudContentPolicy = CloudContentPolicy(),
) {
    private val repository = requireNotNull(config.repository)
    private val pullNumber = requireNotNull(config.pullRequestNumber)
    private val token = requireNotNull(config.githubToken)
    private val baseUrl = "${config.githubApiUrl}/repos/$repository"

    init {
        AppConfig.requireValidRepository(repository)
        require(pullNumber > 0)
        require(token.isNotBlank())
    }

    fun fetch(): PullRequestSnapshot {
        val metadata = fetchMetadata()
        if (metadata.changedFiles > config.limits.maxChangedFiles) {
            throw ReviewInputLimitExceededException()
        }
        val rawFiles = fetchChangedFiles(metadata.changedFiles)
        val fullDiff = fetchFullDiff()
        val fullPatches = fullDiff.first?.let(DiffParser()::patchesByPath).orEmpty()
        var remainingFallbackPatchBytes = (
            config.limits.maxTotalChangedBytes - rawFiles.sumOf { TextTools.utf8Bytes(it.patch.orEmpty()) }
            ).coerceAtLeast(0)
        val patchedFiles = rawFiles.map { file ->
            val fallback = fullPatches[file.path].takeIf { file.patch == null && !file.patchTruncated }
            cloudContentPolicy.requireSafeContent(fallback)
            val boundedFallback = boundedPatch(
                fallback,
                minOf(config.limits.maxFileBytes, remainingFallbackPatchBytes),
            )
            remainingFallbackPatchBytes -= TextTools.utf8Bytes(boundedFallback.value.orEmpty())
            val patch = file.patch ?: boundedFallback.value
            file.copy(
                patch = patch,
                patchTruncated = file.patchTruncated ||
                    boundedFallback.truncated ||
                    (fallback != null && fullDiff.second) ||
                    (file.changes > 0 && patch.isNullOrBlank() && !file.binary),
            )
        }
        var remainingBytes = (
            config.limits.maxTotalChangedBytes - patchedFiles.sumOf { TextTools.utf8Bytes(it.patch.orEmpty()) }
            ).coerceAtLeast(0)
        val files = patchedFiles.map { withPatch ->
            if (withPatch.status == "removed" || withPatch.sha == null || remainingBytes <= 0) {
                withPatch.copy(contentTruncated = withPatch.status != "removed" && remainingBytes <= 0)
            } else {
                val loaded = fetchBlob(withPatch.sha, remainingBytes)
                remainingBytes -= loaded.bytesConsumed
                withPatch.copy(
                    content = loaded.content,
                    binary = loaded.binary,
                    patchTruncated = withPatch.patchTruncated && !loaded.binary,
                    contentTruncated = loaded.truncated,
                )
            }
        }
        val finalMetadata = fetchMetadata()
        require(
            finalMetadata.baseSha == metadata.baseSha &&
                finalMetadata.headSha == metadata.headSha,
        ) {
            "Pull request changed while the review snapshot was being fetched."
        }
        return PullRequestSnapshot(metadata, files, fullDiff.first, fullDiff.second)
    }

    fun fetchMetadata(): PullRequestMetadata {
        val json = getJson("$baseUrl/pulls/$pullNumber", githubHeaders())
        val baseSha = json.objectAt("base").string("sha")
        val head = json.objectAt("head")
        val headSha = head.string("sha")
        val sourceRepository = head["repo"]?.let { it as? JsonObject }?.stringOrNull("full_name")
        config.expectedBaseSha?.let {
            AppConfig.requireSha(it, "PR_BASE_SHA")
            require(baseSha.equals(it, ignoreCase = true)) { "GitHub base SHA does not match PR_BASE_SHA." }
        }
        config.expectedHeadSha?.let {
            AppConfig.requireSha(it, "PR_HEAD_SHA")
            require(headSha.equals(it, ignoreCase = true)) { "GitHub head SHA does not match PR_HEAD_SHA." }
        }
        return PullRequestMetadata(
            repository = repository,
            number = pullNumber,
            title = json.string("title").take(300),
            baseSha = baseSha.lowercase(),
            headSha = headSha.lowercase(),
            draft = json.boolean("draft"),
            fromFork = sourceRepository == null || !sourceRepository.equals(repository, ignoreCase = true),
            changedFiles = json.int("changed_files"),
        ).also {
            AppConfig.requireSha(it.baseSha, "base.sha")
            AppConfig.requireSha(it.headSha, "head.sha")
            require(it.changedFiles in 0..10_000) { "GitHub changed_files is outside the accepted range." }
        }
    }

    fun fetchChangedFiles(reportedCount: Int): List<ChangedFile> {
        val files = mutableListOf<ChangedFile>()
        var remainingPatchBytes = config.limits.maxTotalChangedBytes
        var page = 1
        while (files.size < minOf(reportedCount, config.limits.maxChangedFiles)) {
            require(page <= 100) { "GitHub file pagination exceeded 100 pages." }
            val result = execute(
                HttpCall(
                    method = "GET",
                    uri = URI.create("$baseUrl/pulls/$pullNumber/files?per_page=100&page=$page"),
                    headers = githubHeaders(),
                    timeout = Duration.ofSeconds(30),
                ),
            )
            requireJsonContentType(result)
            val array = parse(result.body).jsonArray
            if (array.isEmpty()) break
            array.forEach { element ->
                if (files.size >= config.limits.maxChangedFiles) return@forEach
                val item = element.jsonObject
                val path = AppConfig.requireSafePath(item.string("filename"))
                val previous = item.stringOrNull("previous_filename")?.let(AppConfig::requireSafePath)
                cloudContentPolicy.requireSafePath(path)
                previous?.let(cloudContentPolicy::requireSafePath)
                val status = item.string("status")
                require(status in allowedStatuses) { "Unknown GitHub file status '$status'." }
                val sha = item.stringOrNull("sha")?.also { AppConfig.requireSha(it, "file.sha") }?.lowercase()
                val changes = item.int("changes")
                val additions = item.int("additions")
                val deletions = item.int("deletions")
                require(listOf(changes, additions, deletions).all { it in 0..10_000_000 }) {
                    "Changed-file counters are outside the accepted range."
                }
                val rawPatch = item.stringOrNull("patch")
                cloudContentPolicy.requireSafeContent(rawPatch)
                val boundedPatch = boundedPatch(
                    rawPatch,
                    minOf(config.limits.maxFileBytes, remainingPatchBytes),
                )
                remainingPatchBytes -= TextTools.utf8Bytes(boundedPatch.value.orEmpty())
                files += ChangedFile(
                    path = path,
                    previousPath = previous,
                    status = status,
                    sha = sha,
                    additions = additions,
                    deletions = deletions,
                    changes = changes,
                    patch = boundedPatch.value,
                    patchTruncated = boundedPatch.truncated,
                )
            }
            if (array.size < 100) break
            page++
        }
        require(files.size <= config.limits.maxChangedFiles)
        return files
    }

    private fun fetchFullDiff(): Pair<String?, Boolean> {
        val result = try {
            transport.execute(
                HttpCall(
                    method = "GET",
                    uri = URI.create("$baseUrl/pulls/$pullNumber"),
                    headers = githubHeaders("application/vnd.github.v3.diff"),
                    timeout = Duration.ofSeconds(30),
                    maxResponseBytes = config.limits.maxTotalChangedBytes,
                ),
            )
        } catch (_: HttpResponseTooLargeException) {
            return null to true
        }
        if (result.status !in 200..299) return null to true
        val contentType = result.header("content-type").orEmpty().lowercase()
        if (contentType.isNotEmpty() && "diff" !in contentType && "text/plain" !in contentType) return null to true
        cloudContentPolicy.requireSafeContent(result.body)
        DiffParser().changedPaths(result.body).forEach(cloudContentPolicy::requireSafePath)
        val max = config.limits.maxTotalChangedBytes
        return if (TextTools.utf8Bytes(result.body) > max) {
            takeUtf8(result.body, max) to true
        } else {
            result.body to false
        }
    }

    private fun fetchBlob(sha: String, remainingBytes: Int): LoadedBlob {
        val result = execute(
            HttpCall(
                method = "GET",
                uri = URI.create("$baseUrl/git/blobs/$sha"),
                headers = githubHeaders(),
                timeout = Duration.ofSeconds(30),
            ),
        )
        requireJsonContentType(result)
        val json = parse(result.body).jsonObject
        val size = json.int("size")
        require(size >= 0) { "GitHub blob size must not be negative." }
        val maxBytes = minOf(config.limits.maxFileBytes, remainingBytes)
        if (size > maxBytes) return LoadedBlob(null, binary = false, truncated = true, bytesConsumed = 0)
        require(json.string("encoding") == "base64") { "GitHub blob encoding must be base64." }
        val decoded = runCatching {
            Base64.getMimeDecoder().decode(json.string("content"))
        }.getOrElse { throw GitHubProtocolException("GitHub blob content is invalid base64.") }
        require(decoded.size == size) { "GitHub blob decoded size does not match metadata." }
        val binary = decoded.any { it == 0.toByte() } ||
            !StandardCharsets.UTF_8.newDecoder().runCatching { decode(java.nio.ByteBuffer.wrap(decoded)) }.isSuccess
        return if (binary) {
            LoadedBlob(null, binary = true, truncated = false, bytesConsumed = decoded.size)
        } else {
            val content = String(decoded, StandardCharsets.UTF_8)
            cloudContentPolicy.requireSafeContent(content)
            LoadedBlob(content, false, false, decoded.size)
        }
    }

    private fun getJson(url: String, headers: Map<String, String>): JsonObject {
        val result = execute(HttpCall("GET", URI.create(url), headers, timeout = Duration.ofSeconds(30)))
        requireJsonContentType(result)
        return parse(result.body).jsonObject
    }

    private fun execute(call: HttpCall): HttpResult {
        val result = transport.execute(call)
        if (result.status !in 200..299) {
            val rateRemaining = result.header("x-ratelimit-remaining")
            val suffix = if (rateRemaining == "0") " GitHub rate limit is exhausted." else ""
            throw GitHubHttpException("GitHub API returned HTTP ${result.status}.$suffix")
        }
        require(TextTools.utf8Bytes(result.body) <= 12_000_000) { "GitHub API response exceeded 12 MB." }
        return result
    }

    private fun requireJsonContentType(result: HttpResult) {
        val value = result.header("content-type")?.lowercase() ?: return
        require("json" in value) { "GitHub API returned unexpected content type." }
    }

    private fun parse(body: String) = runCatching { ReviewJson.tolerant.parseToJsonElement(body) }
        .getOrElse { throw GitHubProtocolException("GitHub API returned malformed JSON.") }

    private fun githubHeaders(accept: String = "application/vnd.github+json"): Map<String, String> = mapOf(
        "Accept" to accept,
        "Authorization" to "Bearer $token",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "ai-course-day-32-reviewer",
    )

    private fun HttpResult.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private fun JsonObject.string(name: String): String =
        (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw GitHubProtocolException("GitHub field '$name' is missing.")

    private fun JsonObject.stringOrNull(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.int(name: String): Int =
        (get(name) as? JsonPrimitive)?.intOrNull
            ?: throw GitHubProtocolException("GitHub integer field '$name' is missing.")

    private fun JsonObject.boolean(name: String): Boolean =
        (get(name) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: throw GitHubProtocolException("GitHub boolean field '$name' is missing.")

    private fun JsonObject.objectAt(name: String): JsonObject =
        get(name) as? JsonObject ?: throw GitHubProtocolException("GitHub object '$name' is missing.")

    private fun takeUtf8(value: String, maxBytes: Int): String {
        if (TextTools.utf8Bytes(value) <= maxBytes) return value
        var low = 0
        var high = value.length
        var best = ""
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = value.take(middle)
            if (TextTools.utf8Bytes(candidate) <= maxBytes) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun boundedPatch(raw: String?, maxBytes: Int): BoundedPatch {
        if (raw == null) return BoundedPatch(null, truncated = false)
        if (maxBytes <= 0) return BoundedPatch(null, truncated = true)
        if (TextTools.utf8Bytes(raw) <= maxBytes) return BoundedPatch(raw, truncated = false)
        val result = StringBuilder()
        for (line in raw.lineSequence()) {
            val candidate = if (result.isEmpty()) line else "\n$line"
            if (TextTools.utf8Bytes(result.toString()) + TextTools.utf8Bytes(candidate) > maxBytes) {
                break
            }
            result.append(candidate)
        }
        return BoundedPatch(result.toString().takeIf(String::isNotBlank), truncated = true)
    }

    private data class LoadedBlob(
        val content: String?,
        val binary: Boolean,
        val truncated: Boolean,
        val bytesConsumed: Int,
    )

    private data class BoundedPatch(
        val value: String?,
        val truncated: Boolean,
    )

    companion object {
        private val allowedStatuses = setOf("added", "modified", "removed", "renamed", "copied", "changed", "unchanged")
    }
}

class GitHubProtocolException(message: String) : IllegalStateException(message)
class GitHubHttpException(message: String) : IllegalStateException(message)
class ReviewInputLimitExceededException :
    IllegalStateException("Pull request exceeds the safe changed-file preflight limit.")
