package ru.ai.course.day34.fileassistant

import kotlinx.serialization.json.intOrNull

interface AgentPlanner {
    val mode: String
    val llmCalls: Int
    suspend fun next(context: PlannerContext): PlanAction
}

object PlanValidator {
    fun validate(action: PlanAction, availableTools: Set<String>) {
        when (action.type) {
            "finish" -> {
                require(action.tool == null) { "finish must not include tool." }
                require(!action.summary.isNullOrBlank()) { "finish requires summary." }
                require(action.arguments.isEmpty()) { "finish arguments must be empty." }
            }
            "tool_call" -> {
                val tool = action.tool ?: throw IllegalArgumentException("tool_call requires tool.")
                require(tool in availableTools) { "Tool was not discovered: $tool" }
                require(action.summary == null) { "tool_call must not include summary." }
                validateArguments(tool, action.arguments)
            }
            else -> throw IllegalArgumentException("type must be tool_call or finish.")
        }
    }

    private fun validateArguments(tool: String, arguments: kotlinx.serialization.json.JsonObject) {
        val allowed = when (tool) {
            FileTool.LIST_FILES -> setOf("prefix", "limit")
            FileTool.SEARCH_TEXT -> setOf("query", "prefix", "limit")
            FileTool.READ_FILES -> setOf("paths")
            FileTool.WRITE_FILE -> setOf("path", "content", "expectedSha256")
            FileTool.UNIFIED_DIFF -> emptySet()
            else -> error("Unknown tool.")
        }
        require(arguments.keys.all { it in allowed }) { "Unexpected arguments for $tool." }
        when (tool) {
            FileTool.LIST_FILES -> {
                optionalString(arguments, "prefix", 1..240)
                optionalInt(arguments, "limit", 1..200)
            }
            FileTool.SEARCH_TEXT -> {
                requireString(arguments, "query", 1..128)
                optionalString(arguments, "prefix", 1..240)
                optionalInt(arguments, "limit", 1..100)
            }
            FileTool.READ_FILES -> {
                val paths = arguments["paths"] as? kotlinx.serialization.json.JsonArray
                    ?: throw IllegalArgumentException("paths must be an array.")
                require(paths.size in 1..6) { "paths must contain 1..6 values." }
                require(paths.all { it is kotlinx.serialization.json.JsonPrimitive && it.isString }) {
                    "paths must contain strings."
                }
                require(paths.map { it.toString() }.distinct().size == paths.size) {
                    "paths must not contain duplicates."
                }
            }
            FileTool.WRITE_FILE -> {
                requireString(arguments, "path", 1..240)
                val content = arguments["content"] as? kotlinx.serialization.json.JsonPrimitive
                require(content?.isString == true) {
                    "content must be a string."
                }
                require(content.content.toByteArray(Charsets.UTF_8).size <= ProjectFilePolicy.MAX_WRITE_BYTES) {
                    "content exceeds the write budget."
                }
                val expected = arguments["expectedSha256"]
                if (expected != null) {
                    val value = (expected as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                    require(value?.matches(Regex("[a-f0-9]{64}")) == true) {
                        "expectedSha256 must be a lowercase SHA-256."
                    }
                }
            }
        }
    }

    private fun requireString(
        arguments: kotlinx.serialization.json.JsonObject,
        name: String,
        length: IntRange,
    ) {
        val value = (arguments[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content
        require(value != null && value.length in length) { "$name must be a string of length $length." }
    }

    private fun optionalString(
        arguments: kotlinx.serialization.json.JsonObject,
        name: String,
        length: IntRange,
    ) {
        if (name !in arguments) return
        requireString(arguments, name, length)
    }

    private fun optionalInt(
        arguments: kotlinx.serialization.json.JsonObject,
        name: String,
        range: IntRange,
    ) {
        val primitive = arguments[name] ?: return
        val value = (primitive as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
        require(value != null && value in range) { "$name must be an integer in $range." }
    }
}
