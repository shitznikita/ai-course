import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ToolPrinter {
    fun print(config: AppConfig, tools: List<Tool>) {
        println("CONNECTED")
        println("TOOLS RETURNED: ${tools.size}")
        println()

        tools.forEachIndexed { index, tool ->
            println("${index + 1}. ${tool.name}")
            println("   description: ${tool.description?.singleLine() ?: "(none)"}")
            println("   required: ${tool.requiredArguments().ifEmpty { "(none)" }}")
            println("   input schema: ${tool.inputSchema.summary()}")
            println()
        }

        println("CHECK: connection ok, tools/list ok")
        println("NOTE: no MCP tools were called; this demo only discovers schemas.")
        println("SERVER USED: ${config.serverUrl}")
    }

    private fun Tool.requiredArguments(): String {
        val required = inputSchema.required ?: emptyList()
        return required.joinToString(", ")
    }

    private fun ToolSchema.summary(): String {
        val properties = properties
            ?.mapValues { (_, value) -> value.describeProperty() }
            ?: emptyMap()

        if (properties.isEmpty()) return "type=object"

        return "type=object, properties=" + properties.entries.joinToString(", ") { (name, value) ->
            "$name:$value"
        }
    }

    private fun JsonElement.describeProperty(): String = when (this) {
        is JsonObject -> {
            val type = this["type"]?.jsonPrimitive?.contentOrNull
            val anyOf = this["anyOf"]?.jsonArray?.mapNotNull {
                it.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            }
            when {
                type != null -> type
                !anyOf.isNullOrEmpty() -> anyOf.joinToString("|")
                else -> "object"
            }
        }
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }

    private fun String.singleLine(): String = lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
}
