package ru.ai.course.day33.supportassistant

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.decodeFromString

fun interface SupportContextGateway {
    suspend fun fetch(ticketId: String): McpContextFetch
}

class SupportMcpClient(private val config: AppConfig) : SupportContextGateway {
    override suspend fun fetch(ticketId: String): McpContextFetch = withClient { client ->
        val requestedTicketId = SupportDataPolicy.requireTicketId(ticketId)
        val tools = client.listTools().tools.map { it.name }.sorted()
        require(tools == SupportTool.REQUIRED.sorted()) {
            "MCP tools must be exactly ${SupportTool.REQUIRED.sorted()}, got $tools."
        }
        val ticketResult = decodeToolResult<TicketToolResponse>(
            client.callTool(SupportTool.GET_TICKET, mapOf("ticketId" to requestedTicketId)),
        )
        if (!ticketResult.found || ticketResult.ticket == null) {
            return@withClient McpContextFetch(
                availableTools = tools,
                usedTools = listOf(SupportTool.GET_TICKET),
                context = null,
                failureReason = "Ticket $requestedTicketId was not found.",
            )
        }
        require(ticketResult.ticket.id == requestedTicketId) { "MCP returned a different ticket." }

        val userResult = decodeToolResult<UserToolResponse>(
            client.callTool(SupportTool.GET_USER, mapOf("userId" to ticketResult.ticket.userId)),
        )
        if (!userResult.found || userResult.user == null) {
            return@withClient McpContextFetch(
                availableTools = tools,
                usedTools = listOf(SupportTool.GET_TICKET, SupportTool.GET_USER),
                context = null,
                failureReason = "Linked user ${ticketResult.ticket.userId} was not found.",
            )
        }
        require(userResult.user.id == ticketResult.ticket.userId) {
            "MCP returned a user that is not linked to the ticket."
        }
        McpContextFetch(
            availableTools = tools,
            usedTools = listOf(SupportTool.GET_TICKET, SupportTool.GET_USER),
            context = SupportDataPolicy.sanitized(ticketResult.ticket, userResult.user),
            failureReason = null,
        )
    }

    private suspend fun <T> withClient(block: suspend (Client) -> T): T {
        val http = HttpClient(CIO) {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = config.mcpRequestTimeout.toMillis()
                connectTimeoutMillis = config.mcpConnectTimeout.toMillis()
                socketTimeoutMillis = config.mcpRequestTimeout.toMillis()
            }
        }
        val client = Client(
            clientInfo = Implementation(
                name = "ai-course-day-33-support-assistant",
                version = "1.0.0",
            ),
        )
        val transport = StreamableHttpClientTransport(client = http, url = config.mcpEndpoint().toString())
        try {
            client.connect(transport)
            return block(client)
        } finally {
            try {
                transport.terminateSession()
            } catch (_: Exception) {
                // The server may already have closed the session; resource cleanup still continues.
            }
            try {
                client.close()
            } finally {
                http.close()
            }
        }
    }

    private inline fun <reified T> decodeToolResult(
        result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult,
    ): T {
        if (result.isError == true) {
            val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            error(text.ifBlank { "MCP tool returned an error." })
        }
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.trim()
        require(text.isNotBlank()) { "MCP tool returned no text content." }
        return SupportJson.strict.decodeFromString(text)
    }
}
