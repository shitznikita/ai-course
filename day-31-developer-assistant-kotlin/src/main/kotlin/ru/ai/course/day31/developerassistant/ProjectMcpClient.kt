package ru.ai.course.day31.developerassistant

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.decodeFromString

interface ProjectContextGateway {
    suspend fun fetchContext(
        includeFiles: Boolean,
        prefix: String? = null,
        fileLimit: Int,
    ): McpProjectContext
}

class ProjectMcpClient(private val config: AppConfig) : ProjectContextGateway {
    override suspend fun fetchContext(
        includeFiles: Boolean,
        prefix: String?,
        fileLimit: Int,
    ): McpProjectContext = withClient { client ->
        val tools = client.listTools().tools.map { it.name }
        require(ProjectTool.CURRENT_BRANCH in tools) {
            "MCP server did not advertise required tool ${ProjectTool.CURRENT_BRANCH}."
        }
        val branch = decodeToolResult<GitBranchInfo>(
            client.callTool(name = ProjectTool.CURRENT_BRANCH, arguments = emptyMap()),
        )
        val files = if (includeFiles) {
            require(ProjectTool.LIST_FILES in tools) {
                "MCP server did not advertise tool ${ProjectTool.LIST_FILES}."
            }
            decodeToolResult<GitFileList>(
                client.callTool(
                    name = ProjectTool.LIST_FILES,
                    arguments = buildMap {
                        if (!prefix.isNullOrBlank()) put("prefix", prefix)
                        put("limit", fileLimit.coerceIn(1, 200))
                    },
                ),
            )
        } else {
            null
        }
        McpProjectContext(
            availableTools = tools,
            branch = branch,
            files = files,
            usedTools = buildList {
                add(ProjectTool.CURRENT_BRANCH)
                if (files != null) add(ProjectTool.LIST_FILES)
            },
        )
    }

    private suspend fun <T> withClient(block: suspend (Client) -> T): T {
        HttpClient(CIO) {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = config.mcpTimeout.toMillis()
                connectTimeoutMillis = config.mcpTimeout.toMillis()
                socketTimeoutMillis = config.mcpTimeout.toMillis()
            }
        }.use { http ->
            val client = Client(
                clientInfo = Implementation(
                    name = "ai-course-day-31-developer-assistant",
                    version = "1.0.0",
                ),
            )
            client.connect(StreamableHttpClientTransport(client = http, url = config.mcpUrl))
            return block(client)
        }
    }

    private inline fun <reified T> decodeToolResult(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): T {
        if (result.isError == true) {
            val errorText = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            throw IllegalStateException(errorText.ifBlank { "MCP tool returned an error." })
        }
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.trim()
        require(text.isNotBlank()) { "MCP tool returned no text content." }
        return AppJson.tolerant.decodeFromString(text)
    }
}
