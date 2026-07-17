package ru.ai.course.day32.codereview

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

enum class PublishOperation {
    CREATED,
    UPDATED,
}

class GitHubReviewPublisher(
    private val config: AppConfig,
    private val transport: HttpTransport = JavaHttpTransport(),
) {
    private val repository = requireNotNull(config.repository)
    private val pullNumber = requireNotNull(config.pullRequestNumber)
    private val token = requireNotNull(config.githubToken)
    private val baseUrl = "${config.githubApiUrl}/repos/$repository"

    fun publish(markdown: String): PublishOperation {
        require(markdown.startsWith(MarkdownReviewRenderer.MARKER)) { "Published review must contain the marker." }
        val commentId = findMarkerComment()
        val body = ReviewJson.strict.encodeToString(buildJsonObject { put("body", JsonPrimitive(markdown)) })
        val call = if (commentId == null) {
            HttpCall(
                "POST",
                URI.create("$baseUrl/issues/$pullNumber/comments"),
                headers(),
                body,
                Duration.ofSeconds(30),
            )
        } else {
            HttpCall(
                "PATCH",
                URI.create("$baseUrl/issues/comments/$commentId"),
                headers(),
                body,
                Duration.ofSeconds(30),
            )
        }
        execute(call)
        return if (commentId == null) PublishOperation.CREATED else PublishOperation.UPDATED
    }

    fun writeStepSummary(markdown: String, path: Path? = summaryPath()): Boolean {
        if (path == null) return false
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            markdown + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return true
    }

    private fun findMarkerComment(): Long? {
        var page = 1
        while (page <= 20) {
            val result = execute(
                HttpCall(
                    "GET",
                    URI.create("$baseUrl/issues/$pullNumber/comments?per_page=100&page=$page"),
                    headers(),
                    timeout = Duration.ofSeconds(30),
                ),
            )
            val comments = parseArray(result.body)
            val marker = comments.mapNotNull { element ->
                val comment = element as? JsonObject ?: return@mapNotNull null
                val body = (comment["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val user = comment["user"] as? JsonObject
                val type = (user?.get("type") as? JsonPrimitive)?.contentOrNull
                val login = (user?.get("login") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val isBot = type == "Bot" && login == "github-actions[bot]"
                val id = (comment["id"] as? JsonPrimitive)?.longOrNull
                id?.takeIf { isBot && body.startsWith(MarkdownReviewRenderer.MARKER) }
            }.firstOrNull()
            if (marker != null) return marker
            if (comments.size < 100) return null
            page++
        }
        throw GitHubProtocolException("GitHub comment pagination exceeded 20 pages.")
    }

    private fun execute(call: HttpCall): HttpResult {
        val result = transport.execute(call)
        if (result.status !in 200..299) throw GitHubHttpException("GitHub comment API returned HTTP ${result.status}.")
        require(TextTools.utf8Bytes(result.body) <= 2_000_000) { "GitHub comment response exceeded 2 MB." }
        return result
    }

    private fun parseArray(body: String): JsonArray = runCatching {
        ReviewJson.tolerant.parseToJsonElement(body).jsonArray
    }.getOrElse { throw GitHubProtocolException("GitHub comments response is malformed.") }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/vnd.github+json",
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "ai-course-day-32-reviewer",
    )

    private fun summaryPath(): Path? =
        System.getenv("GITHUB_STEP_SUMMARY")?.takeIf(String::isNotBlank)?.let(Path::of)
}
