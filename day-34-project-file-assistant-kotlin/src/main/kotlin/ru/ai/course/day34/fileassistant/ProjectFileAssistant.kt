package ru.ai.course.day34.fileassistant

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class ProjectFileAssistant(
    private val gateway: FileToolGateway,
    private val planner: AgentPlanner,
) {
    suspend fun run(goalValue: String): AgentRunResult {
        val goal = goalValue.trim()
        require(goal.length in 8..1_000) { "Goal must contain 8..1000 characters." }
        val tools = gateway.tools()
        require(tools == FileTool.expected) { "Unexpected MCP tool registry." }

        val observations = mutableListOf<ToolObservation>()
        val callSignatures = linkedSetOf<String>()
        var observationChars = 0
        var finishSummary: String? = null

        for (step in 1..MAX_STEPS) {
            val action = nextValidAction(goal, step, tools, observations)
            if (action.type == "finish") {
                val snapshot = gateway.snapshot()
                if (snapshot.changedPaths.isNotEmpty() && observations.none { it.tool == FileTool.UNIFIED_DIFF }) {
                    throw IllegalStateException("Planner tried to finish changed files before project_unified_diff.")
                }
                finishSummary = requireNotNull(action.summary)
                break
            }

            val tool = requireNotNull(action.tool)
            val signature = "$tool:${canonicalJson(action.arguments)}"
            require(callSignatures.add(signature)) { "Repeated no-op/tool call rejected: $tool." }
            val result = gateway.call(tool, action.arguments)
            observationChars += result.toString().length
            require(observationChars <= MAX_CUMULATIVE_OBSERVATION_CHARS) {
                "Cumulative observation budget exhausted."
            }
            observations += ToolObservation(
                step = step,
                tool = tool,
                arguments = action.arguments,
                result = result,
                argumentsSummary = summarizeArguments(tool, action.arguments),
                observationSummary = summarizeResult(tool, result),
            )
        }

        require(finishSummary != null) { "Agent exceeded the $MAX_STEPS-step limit." }
        val session = gateway.snapshot()
        val diffObservation = observations.lastOrNull { it.tool == FileTool.UNIFIED_DIFF }
            ?.result?.let { AppJson.strict.decodeFromJsonElement(DiffResult.serializer(), it) }
        if (diffObservation != null) {
            require(diffObservation.changedPaths == session.changedPaths) {
                "MCP diff and server-owned session state disagree."
            }
            require(diffObservation.sha256 == session.diffSha256) {
                "MCP diff fingerprint and server-owned session state disagree."
            }
        }
        return AgentRunResult(
            goal = goal,
            plannerMode = planner.mode,
            trace = observations,
            finishSummary = finishSummary,
            session = session,
            checks = listOf(
                "exact five-tool MCP registry accepted",
                "bounded loop: ${observations.size}/$MAX_STEPS tool calls",
                "server-owned paths and diff verified",
                "write mode: ${session.filesWritten.size} file(s) handled by workspace policy",
            ),
            llmCalls = planner.llmCalls,
        )
    }

    private suspend fun nextValidAction(
        goal: String,
        step: Int,
        tools: Set<String>,
        observations: List<ToolObservation>,
    ): PlanAction {
        var feedback: String? = null
        repeat(2) { attempt ->
            try {
                val action = planner.next(
                    PlannerContext(
                        goal = goal,
                        step = step,
                        availableTools = tools,
                        observations = observations.toList(),
                        invalidPlanFeedback = feedback,
                    ),
                )
                PlanValidator.validate(action, tools)
                return action
            } catch (error: IllegalArgumentException) {
                feedback = boundedText(error.message ?: "Invalid plan.", 500)
                if (attempt == 1) throw IllegalStateException("Planner returned two invalid plans: $feedback")
            }
        }
        error("Unreachable planner retry state.")
    }

    private fun summarizeArguments(tool: String, arguments: JsonObject): String = when (tool) {
        FileTool.LIST_FILES -> listOfNotNull(
            arguments["prefix"]?.jsonPrimitive?.contentOrNull?.let { "prefix=$it" },
            arguments["limit"]?.jsonPrimitive?.intOrNull?.let { "limit=$it" },
        ).joinToString().ifBlank { "(defaults)" }
        FileTool.SEARCH_TEXT -> listOfNotNull(
            arguments["query"]?.jsonPrimitive?.contentOrNull?.let { "query=${boundedText(it, 80)}" },
            arguments["prefix"]?.jsonPrimitive?.contentOrNull?.let { "prefix=$it" },
            arguments["limit"]?.jsonPrimitive?.intOrNull?.let { "limit=$it" },
        ).joinToString()
        FileTool.READ_FILES -> arguments["paths"]?.jsonArray
            ?.joinToString(prefix = "paths=[", postfix = "]") { it.jsonPrimitive.content }
            ?: "paths=[]"
        FileTool.WRITE_FILE -> {
            val content = arguments["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val sha = arguments["expectedSha256"]?.jsonPrimitive?.contentOrNull
            "path=${arguments["path"]?.jsonPrimitive?.contentOrNull}, bytes=${
                content.toByteArray(StandardCharsets.UTF_8).size
            }${sha?.let { ", expectedSha256=${it.take(12)}…" } ?: ""}"
        }
        FileTool.UNIFIED_DIFF -> "(none)"
        else -> "(unknown)"
    }

    private fun summarizeResult(tool: String, result: JsonObject): String = when (tool) {
        FileTool.LIST_FILES -> {
            val value = AppJson.strict.decodeFromJsonElement<FileListResult>(result)
            "${value.files.size} file(s): ${value.files.take(8).joinToString()}${if (value.truncated) " (truncated)" else ""}"
        }
        FileTool.SEARCH_TEXT -> {
            val value = AppJson.strict.decodeFromJsonElement<SearchResult>(result)
            "${value.hits.size} hit(s) across ${value.searchedFiles} file(s): ${
                value.hits.map(SearchHit::path).distinct().joinToString()
            }"
        }
        FileTool.READ_FILES -> {
            val value = AppJson.strict.decodeFromJsonElement<ReadFilesResult>(result)
            value.files.joinToString { "${it.path} (${it.bytes} B, ${it.sha256.take(12)}…)" }
        }
        FileTool.WRITE_FILE -> {
            val value = AppJson.strict.decodeFromJsonElement<WriteFileResult>(result)
            "${value.path}: changed=${value.changed}, mode=${value.mode}, sha256=${value.sha256.take(12)}…"
        }
        FileTool.UNIFIED_DIFF -> {
            val value = AppJson.strict.decodeFromJsonElement<DiffResult>(result)
            "${value.changedPaths.size} changed path(s), diff sha256=${value.sha256.take(12)}…"
        }
        else -> boundedText(result.toString(), 500)
    }

    companion object {
        const val MAX_STEPS = 12
        private const val MAX_CUMULATIVE_OBSERVATION_CHARS = 4_000_000
    }
}
