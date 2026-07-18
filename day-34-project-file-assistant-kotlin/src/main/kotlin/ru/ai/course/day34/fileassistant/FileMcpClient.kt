package ru.ai.course.day34.fileassistant

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

class FileMcpClient(private val config: AppConfig) {
    suspend fun discoverTools(): Set<String> = withClient { client ->
        val names = client.listTools().tools.map { it.name }.toSet()
        require(names == FileTool.expected) {
            "MCP tools must be exactly ${FileTool.expected.sorted()}, got ${names.sorted()}."
        }
        names
    }

    suspend fun call(tool: String, arguments: JsonObject): JsonObject = withClient { client ->
        require(tool in FileTool.expected) { "Unknown MCP tool: $tool" }
        val result = client.callTool(
            name = tool,
            arguments = arguments.mapValues { (_, value) -> value.toKotlinValue() },
        )
        if (result.isError == true) {
            val message = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            error(message.ifBlank { "MCP tool returned an error." })
        }
        result.structuredContent?.let { return@withClient it }
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.trim()
        require(text.isNotBlank()) { "MCP tool returned no JSON content." }
        AppJson.strict.parseToJsonElement(text) as? JsonObject
            ?: error("MCP tool returned non-object JSON.")
    }

    private suspend fun <T> withClient(block: suspend (Client) -> T): T {
        val http = HttpClient(CIO) {
            install(SSE)
            defaultRequest {
                header(FileMcpAuth.HEADER, config.mcpSessionToken)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = config.mcpTimeout.toMillis()
                connectTimeoutMillis = config.mcpTimeout.toMillis()
                socketTimeoutMillis = config.mcpTimeout.toMillis()
            }
        }
        val client = Client(Implementation("ai-course-day-34-file-assistant", "1.0.0"))
        val transport = StreamableHttpClientTransport(http, config.mcpUrl)
        try {
            client.connect(transport)
            return block(client)
        } finally {
            runCatching { transport.terminateSession() }
            try {
                client.close()
            } finally {
                http.close()
            }
        }
    }

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toKotlinValue() }
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive -> when {
            isString -> contentOrNull
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> contentOrNull
        }
    }
}
