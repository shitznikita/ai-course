package ru.ai.course.day34.fileassistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

object AppJson {
    val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = false
    }
    val tolerant = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    val pretty = Json(strict) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}

enum class WriteMode {
    PREVIEW,
    APPLY,
}

object FileTool {
    const val LIST_FILES = "project_list_files"
    const val SEARCH_TEXT = "project_search_text"
    const val READ_FILES = "project_read_files"
    const val WRITE_FILE = "project_write_file"
    const val UNIFIED_DIFF = "project_unified_diff"

    val expected = setOf(LIST_FILES, SEARCH_TEXT, READ_FILES, WRITE_FILE, UNIFIED_DIFF)
}

@Serializable
data class FileListResult(
    val files: List<String>,
    val truncated: Boolean,
)

@Serializable
data class SearchHit(
    val path: String,
    val line: Int,
    val text: String,
)

@Serializable
data class SearchResult(
    val query: String,
    val hits: List<SearchHit>,
    val searchedFiles: Int,
    val truncated: Boolean,
)

@Serializable
data class ReadFileEntry(
    val path: String,
    val content: String,
    val sha256: String,
    val bytes: Int,
)

@Serializable
data class ReadFilesResult(val files: List<ReadFileEntry>)

@Serializable
data class WriteFileResult(
    val path: String,
    val sha256: String,
    val mode: String,
    val changed: Boolean,
)

@Serializable
data class DiffResult(
    val changedPaths: List<String>,
    val diff: String,
    val sha256: String,
)

@Serializable
data class SessionSummary(
    val filesDiscovered: List<String>,
    val filesSearched: List<String>,
    val filesRead: List<String>,
    val filesWritten: List<String>,
    val changedPaths: List<String>,
    val diff: String,
    val diffSha256: String,
)

@Serializable
data class PlanAction(
    val type: String,
    val tool: String? = null,
    val arguments: JsonObject = buildJsonObject {},
    val summary: String? = null,
)

data class PlannerContext(
    val goal: String,
    val step: Int,
    val availableTools: Set<String>,
    val observations: List<ToolObservation>,
    val invalidPlanFeedback: String? = null,
)

@Serializable
data class ToolObservation(
    val step: Int,
    val tool: String,
    val arguments: JsonObject,
    val result: JsonObject,
    val argumentsSummary: String,
    val observationSummary: String,
)

data class AgentRunResult(
    val goal: String,
    val plannerMode: String,
    val trace: List<ToolObservation>,
    val finishSummary: String,
    val session: SessionSummary,
    val checks: List<String>,
    val llmCalls: Int,
)
