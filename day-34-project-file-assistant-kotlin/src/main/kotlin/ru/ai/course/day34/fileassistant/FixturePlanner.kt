package ru.ai.course.day34.fileassistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

object DemoGoals {
    const val LEGACY_USAGE =
        "Найди все места, где используется LegacyPaymentsApi, и подготовь отчёт миграции"
    const val DOCS_SYNC =
        "Проверь, соответствует ли документация публичному API проекта, и синхронизируй её"
}

class FixturePlanner : AgentPlanner {
    override val mode: String = "deterministic-fixture"
    override val llmCalls: Int = 0

    override suspend fun next(context: PlannerContext): PlanAction =
        if ("LegacyPaymentsApi" in context.goal) legacyPlan(context) else docsPlan(context)

    private fun legacyPlan(context: PlannerContext): PlanAction {
        if (!context.used(FileTool.LIST_FILES)) {
            return tool(FileTool.LIST_FILES, json("limit" to JsonPrimitive(80)))
        }
        if (!context.used(FileTool.SEARCH_TEXT)) {
            return tool(
                FileTool.SEARCH_TEXT,
                json("query" to JsonPrimitive("LegacyPaymentsApi"), "limit" to JsonPrimitive(40)),
            )
        }
        if (!context.used(FileTool.READ_FILES)) {
            val search = context.result<SearchResult>(FileTool.SEARCH_TEXT)
            val paths = search.hits.map(SearchHit::path).distinct().take(6)
            require(paths.size >= 3) { "Fixture must expose LegacyPaymentsApi in at least three files." }
            return tool(FileTool.READ_FILES, json("paths" to JsonArray(paths.map(::JsonPrimitive))))
        }
        if (!context.used(FileTool.WRITE_FILE)) {
            val search = context.result<SearchResult>(FileTool.SEARCH_TEXT)
            val reads = context.result<ReadFilesResult>(FileTool.READ_FILES)
            val report = buildLegacyReport(search, reads)
            return tool(
                FileTool.WRITE_FILE,
                json(
                    "path" to JsonPrimitive("docs/legacy-payments-usage.md"),
                    "content" to JsonPrimitive(report),
                ),
            )
        }
        if (!context.used(FileTool.UNIFIED_DIFF)) {
            return tool(FileTool.UNIFIED_DIFF)
        }
        return finish("Found ${context.result<SearchResult>(FileTool.SEARCH_TEXT).hits.size} usages and created the migration report.")
    }

    private fun docsPlan(context: PlannerContext): PlanAction {
        if (!context.used(FileTool.LIST_FILES)) {
            return tool(FileTool.LIST_FILES, json("limit" to JsonPrimitive(80)))
        }
        if (!context.used(FileTool.SEARCH_TEXT)) {
            return tool(
                FileTool.SEARCH_TEXT,
                json("query" to JsonPrimitive("refund("), "limit" to JsonPrimitive(40)),
            )
        }
        if (!context.used(FileTool.READ_FILES)) {
            val listed = context.result<FileListResult>(FileTool.LIST_FILES).files
            val searched = context.result<SearchResult>(FileTool.SEARCH_TEXT).hits.map(SearchHit::path)
            val docs = listOf("README.md", "docs/api.md").filter { it in listed }
            val paths = (searched + docs).distinct().take(6)
            require(paths.any { it.endsWith("PaymentClient.kt") }) { "Fixture lacks PaymentClient.kt." }
            require(paths.any { it.endsWith("CheckoutService.kt") }) { "Fixture lacks CheckoutService.kt." }
            require(docs.size == 2) { "Fixture lacks expected documentation files." }
            return tool(FileTool.READ_FILES, json("paths" to JsonArray(paths.map(::JsonPrimitive))))
        }

        val reads = context.result<ReadFilesResult>(FileTool.READ_FILES).files.associateBy(ReadFileEntry::path)
        val writtenPaths = context.observations
            .filter { it.tool == FileTool.WRITE_FILE }
            .map { AppJson.strict.decodeFromJsonElement(WriteFileResult.serializer(), it.result).path }
            .toSet()
        return when {
            "docs/api.md" !in writtenPaths -> {
                val source = reads.getValue("docs/api.md")
                tool(
                    FileTool.WRITE_FILE,
                    writeArgs(
                        source,
                        appendSection(
                            source.content,
                            "## `refund(paymentId, reason)`",
                            """
                            |Creates a refund for a captured payment.
                            |
                            |- `paymentId` — captured payment identifier.
                            |- `reason` — audit-friendly refund reason.
                            |- returns a `RefundReceipt`.
                            """.trimMargin(),
                        ),
                    ),
                )
            }
            "README.md" !in writtenPaths -> {
                val source = reads.getValue("README.md")
                tool(
                    FileTool.WRITE_FILE,
                    writeArgs(
                        source,
                        appendSection(
                            source.content,
                            "## Public API",
                            "The client supports `charge(request)` and `refund(paymentId, reason)`. See `docs/api.md`.",
                        ),
                    ),
                )
            }
            "CHANGELOG.md" !in writtenPaths -> tool(
                FileTool.WRITE_FILE,
                json(
                    "path" to JsonPrimitive("CHANGELOG.md"),
                    "content" to JsonPrimitive(
                        """
                        |# Changelog
                        |
                        |## Unreleased
                        |
                        |- Documented the public `refund(paymentId, reason)` API.
                        """.trimMargin() + "\n",
                    ),
                ),
            )
            !context.used(FileTool.UNIFIED_DIFF) -> tool(FileTool.UNIFIED_DIFF)
            else -> finish("Synchronized README and API docs with refund support and recorded the change.")
        }
    }

    private fun buildLegacyReport(search: SearchResult, reads: ReadFilesResult): String {
        val roles = reads.files.associate { file ->
            file.path to when {
                file.path.endsWith("LegacyPaymentsApi.kt") -> "interface definition"
                "/test/" in file.path || file.path.contains("src/test") -> "test double / verification"
                else -> "production dependency"
            }
        }
        return buildString {
            append("# LegacyPaymentsApi migration report\n\n")
            append("Generated from ").append(reads.files.size)
                .append(" project files after a literal cross-file search.\n\n")
            append("## Usages\n\n")
            search.hits.forEach { hit ->
                append("- `").append(hit.path).append(':').append(hit.line).append("` — ")
                    .append(roles[hit.path] ?: "usage").append(": `")
                    .append(hit.text.replace("`", "\\`")).append("`\n")
            }
            append("\n## Analysis\n\n")
            roles.entries.sortedBy { it.key }.forEach { (path, role) ->
                append("- `").append(path).append("`: ").append(role).append(".\n")
            }
            append("\n## Suggested migration\n\n")
            append("1. Introduce the replacement behind the existing payment abstraction.\n")
            append("2. Migrate `CheckoutService` before removing the legacy interface.\n")
            append("3. Port the test double and keep behavior coverage during the transition.\n")
            append("4. Remove `LegacyPaymentsApi` only after a final repository-wide search returns no hits.\n")
        }
    }

    private fun appendSection(content: String, heading: String, body: String): String {
        if (heading in content) return content
        return content.trimEnd() + "\n\n$heading\n\n${body.trim()}\n"
    }

    private fun writeArgs(source: ReadFileEntry, content: String): JsonObject = json(
        "path" to JsonPrimitive(source.path),
        "content" to JsonPrimitive(content),
        "expectedSha256" to JsonPrimitive(source.sha256),
    )

    private fun tool(name: String, arguments: JsonObject = buildJsonObject {}): PlanAction =
        PlanAction(type = "tool_call", tool = name, arguments = arguments)

    private fun finish(summary: String): PlanAction = PlanAction(type = "finish", summary = summary)

    private fun json(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(entries.toMap())

    private fun PlannerContext.used(tool: String): Boolean = observations.any { it.tool == tool }

    private inline fun <reified T> PlannerContext.result(tool: String): T =
        observations.lastOrNull { it.tool == tool }?.result?.let {
            AppJson.strict.decodeFromJsonElement(it)
        }
            ?: error("Missing observation for $tool.")
}
