package ru.ai.course.day34.fileassistant

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class OllamaPlanner(
    private val config: AppConfig,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.ollamaTimeout)
        .build(),
) : AgentPlanner {
    override val mode: String = "ollama-direct-rest"
    private var calls: Int = 0
    override val llmCalls: Int get() = calls

    internal data class ContextAdmission(
        val chatContentUtf8Bytes: Int,
        val framingReserveTokens: Int,
        val outputReserveTokens: Int,
        val contextTokens: Int,
    ) {
        val worstCaseTotalTokens: Int =
            chatContentUtf8Bytes + framingReserveTokens + outputReserveTokens
        val fits: Boolean = worstCaseTotalTokens <= contextTokens
    }

    override suspend fun next(context: PlannerContext): PlanAction {
        val body = buildRequest(context).toString()
        val request = HttpRequest.newBuilder(URI.create(config.ollamaUrl))
            .timeout(config.ollamaTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        calls++
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() in 200..299) {
            "Ollama returned HTTP ${response.statusCode()}: ${boundedText(response.body(), 500)}"
        }
        val envelope = AppJson.strict.parseToJsonElement(response.body()) as? JsonObject
            ?: error("Ollama returned non-object JSON.")
        val message = envelope["message"] as? JsonObject ?: error("Ollama response has no message.")
        val content = (message["content"] as? JsonPrimitive)?.content?.trim()
            ?.takeIf(String::isNotBlank) ?: error("Ollama returned an empty plan.")
        val wire = AppJson.strict.decodeFromString(WirePlanAction.serializer(), content)
        return PlanAction(
            type = wire.type,
            tool = wire.tool.takeUnless { it == NO_TOOL },
            arguments = normalizeOptionalArguments(wire.tool, wire.arguments),
            summary = wire.summary.takeIf(String::isNotBlank),
        )
    }

    internal fun buildRequest(context: PlannerContext): JsonObject {
        val userPrompt = buildUserPrompt(context)
        val admission = contextAdmission(userPrompt)
        require(admission.fits) {
            "Whole-item planner input exceeds conservative context budget: " +
                "${admission.chatContentUtf8Bytes} UTF-8 bytes + " +
                "${admission.framingReserveTokens} framing tokens + " +
                "${admission.outputReserveTokens} output tokens > ${admission.contextTokens} context tokens."
        }
        return buildJsonObject {
        put("model", JsonPrimitive(config.ollamaModel))
        put("stream", JsonPrimitive(false))
        put("think", JsonPrimitive(false))
        put("format", planSchema(context.availableTools))
        put("options", buildJsonObject {
            put("temperature", JsonPrimitive(0))
            put("num_ctx", JsonPrimitive(NUM_CTX_TOKENS))
            put("num_predict", JsonPrimitive(NUM_PREDICT_TOKENS))
        })
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(SYSTEM_PROMPT))
            })
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userPrompt))
            })
        })
        }
    }

    internal fun contextAdmission(context: PlannerContext): ContextAdmission =
        contextAdmission(buildUserPrompt(context))

    private fun contextAdmission(userPrompt: String): ContextAdmission = ContextAdmission(
        chatContentUtf8Bytes =
            SYSTEM_PROMPT.toByteArray(StandardCharsets.UTF_8).size +
                userPrompt.toByteArray(StandardCharsets.UTF_8).size,
        framingReserveTokens = CHAT_TEMPLATE_FRAMING_RESERVE_TOKENS,
        outputReserveTokens = NUM_PREDICT_TOKENS,
        contextTokens = NUM_CTX_TOKENS,
    )

    private fun buildUserPrompt(context: PlannerContext): String {
        val observationBlocks = context.observations.map(::renderObservation)
        val prefix = """
            |GOAL:
            |${context.goal}
            |
            |AVAILABLE TOOLS:
            |- project_list_files(prefix?: string, limit?: 1..200)
            |- project_search_text(query: literal string, prefix?: string, limit?: 1..100)
            |- project_read_files(paths: 1..6 discovered project-relative paths)
            |- project_write_file(path, content, expectedSha256?: required for an existing file)
            |- project_unified_diff()
            |
            |RULES:
            |- Choose exactly one next action. The goal names no required files; discover/search first.
            |- Read an existing file in this session before replacing it and copy its returned SHA-256.
            |- Use complete file content for write_file. Do not invent paths that discovery/search did not justify.
            |- Keep changes minimal and call project_unified_diff after any write before finish.
            |- Never request shell commands, delete, rename, secrets, generated paths, or symlinks.
            |- For tool_call, set tool to one discovered tool and summary to an empty string.
            |- For finish, set tool to "$NO_TOOL", arguments to {}, and summary to a short non-empty result.
            |- Return only one strict JSON object matching the schema. No Markdown.
            |
            |${context.invalidPlanFeedback?.let { "PREVIOUS PLAN ERROR:\n$it\n" } ?: ""}
            |OBSERVATIONS:
            """.trimMargin()
        val observations = observationBlocks.joinToString("\n\n").ifBlank { "(none)" }
        return prefix + observations
    }

    private fun renderObservation(observation: ToolObservation): String = buildString {
        append("STEP ").append(observation.step).append('\n')
        append("TOOL ").append(observation.tool).append('\n')
        append("ARGUMENTS ").append(observation.argumentsSummary).append('\n')
        append("RESULT\n")
        when (observation.tool) {
            FileTool.LIST_FILES -> {
                val value = AppJson.strict.decodeFromJsonElement<FileListResult>(observation.result)
                append("TRUNCATED: ").append(value.truncated).append('\n')
                value.files.forEach { append("FILE: ").append(it).append('\n') }
            }
            FileTool.SEARCH_TEXT -> {
                val value = AppJson.strict.decodeFromJsonElement<SearchResult>(observation.result)
                append("QUERY: ").append(value.query).append('\n')
                append("SEARCHED FILES: ").append(value.searchedFiles).append('\n')
                append("TRUNCATED: ").append(value.truncated).append('\n')
                value.hits.forEach { hit ->
                    append("HIT PATH: ").append(hit.path).append('\n')
                    append("HIT LINE: ").append(hit.line).append('\n')
                    append("HIT TEXT: ").append(hit.text).append('\n')
                }
            }
            FileTool.READ_FILES -> {
                val value = AppJson.strict.decodeFromJsonElement<ReadFilesResult>(observation.result)
                value.files.forEach { file ->
                    append("READ FILE BEGIN\n")
                    append("PATH: ").append(file.path).append('\n')
                    append("BYTES: ").append(file.bytes).append('\n')
                    append("SHA-256: ").append(file.sha256).append('\n')
                    append("CONTENT BEGIN\n")
                    append(file.content)
                    if (!file.content.endsWith('\n')) append('\n')
                    append("CONTENT END\n")
                    append("READ FILE END\n")
                }
            }
            FileTool.WRITE_FILE -> {
                val value = AppJson.strict.decodeFromJsonElement<WriteFileResult>(observation.result)
                append("PATH: ").append(value.path).append('\n')
                append("SHA-256: ").append(value.sha256).append('\n')
                append("MODE: ").append(value.mode).append('\n')
                append("CHANGED: ").append(value.changed).append('\n')
            }
            FileTool.UNIFIED_DIFF -> {
                val value = AppJson.strict.decodeFromJsonElement<DiffResult>(observation.result)
                value.changedPaths.forEach { append("CHANGED PATH: ").append(it).append('\n') }
                append("DIFF SHA-256: ").append(value.sha256).append('\n')
                append("DIFF CONTENT: omitted from planner context; server-owned fingerprint above\n")
            }
            else -> error("Unsupported observation tool: ${observation.tool}")
        }
    }

    private fun planSchema(tools: Set<String>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("additionalProperties", JsonPrimitive(false))
        put("properties", buildJsonObject {
            put("type", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", JsonArray(listOf("tool_call", "finish").map(::JsonPrimitive)))
            })
            put("tool", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", JsonArray((tools.sorted() + NO_TOOL).map(::JsonPrimitive)))
            })
            put("arguments", buildJsonObject { put("type", JsonPrimitive("object")) })
            put("summary", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("maxLength", JsonPrimitive(500))
            })
        })
        put("required", requiredActionFields())
    }

    private fun requiredActionFields(): JsonArray =
        JsonArray(listOf("type", "tool", "arguments", "summary").map(::JsonPrimitive))

    private fun normalizeOptionalArguments(tool: String, arguments: JsonObject): JsonObject {
        val optionalStrings = when (tool) {
            FileTool.LIST_FILES, FileTool.SEARCH_TEXT -> setOf("prefix")
            FileTool.WRITE_FILE -> setOf("expectedSha256")
            else -> emptySet()
        }
        return JsonObject(arguments.filterNot { (name, value) ->
            name in optionalStrings &&
                value is JsonPrimitive &&
                value.isString &&
                value.content.isBlank()
        })
    }

    companion object {
        internal const val NUM_CTX_TOKENS = 8_192
        internal const val NUM_PREDICT_TOKENS = 1_200
        internal const val CHAT_TEMPLATE_FRAMING_RESERVE_TOKENS = 512
        internal const val MAX_CHAT_CONTENT_UTF8_BYTES =
            NUM_CTX_TOKENS - NUM_PREDICT_TOKENS - CHAT_TEMPLATE_FRAMING_RESERVE_TOKENS
        private const val NO_TOOL = "none"
        private const val SYSTEM_PROMPT =
            "You are a private local project-file planning agent. Plan bounded MCP file operations; never answer the goal directly."
    }

    @Serializable
    private data class WirePlanAction(
        val type: String,
        val tool: String,
        val arguments: JsonObject,
        val summary: String,
    )
}
