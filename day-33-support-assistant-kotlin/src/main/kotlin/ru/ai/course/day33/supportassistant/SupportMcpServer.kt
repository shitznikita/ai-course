package ru.ai.course.day33.supportassistant

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object SupportTool {
    const val GET_TICKET = "support_get_ticket"
    const val GET_USER = "support_get_user"
    val REQUIRED: Set<String> = setOf(GET_TICKET, GET_USER)
}

class SupportMcpServerFactory(private val repository: SupportDataRepository) {
    fun create(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "ai-course-day-33-synthetic-support-context",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )
        server.addTool(
            name = SupportTool.GET_TICKET,
            description = "Read one synthetic support ticket by exact ticketId. Returns allowlisted fields only.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("ticketId", stringProperty("Exact synthetic ticket ID such as TCK-1001."))
                },
                required = listOf("ticketId"),
            ),
        ) { request ->
            toolResult {
                val ticketId = requiredString(request.arguments, "ticketId")
                    .let(SupportDataPolicy::requireTicketId)
                val ticket = repository.ticket(ticketId)
                TicketToolResponse(found = ticket != null, ticket = ticket)
            }
        }
        server.addTool(
            name = SupportTool.GET_USER,
            description = "Read one synthetic support user by exact userId. Returns allowlisted non-PII fields only.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("userId", stringProperty("Exact synthetic linked user ID such as USR-1001."))
                },
                required = listOf("userId"),
            ),
        ) { request ->
            toolResult {
                val userId = requiredString(request.arguments, "userId")
                    .let(SupportDataPolicy::requireUserId)
                val user = repository.user(userId)
                UserToolResponse(found = user != null, user = user)
            }
        }
        return server
    }

    private fun stringProperty(description: String) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
        put("minLength", JsonPrimitive(8))
        put("maxLength", JsonPrimitive(12))
    }

    private fun requiredString(arguments: JsonObject?, name: String): String =
        arguments?.get(name)?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)
            ?: error("$name is required.")

    private inline fun <reified T> toolResult(block: () -> T): CallToolResult = try {
        val json = SupportJson.compact.encodeToString(block())
        CallToolResult(
            content = listOf(TextContent(text = json)),
            isError = false,
            structuredContent = SupportJson.compact.parseToJsonElement(json) as JsonObject,
        )
    } catch (error: Exception) {
        CallToolResult(
            content = listOf(TextContent(text = "Tool call failed: ${error.message ?: error::class.simpleName}")),
            isError = true,
        )
    }
}

@Suppress("TooGenericExceptionCaught")
suspend fun startSupportMcpServer(
    config: AppConfig,
    repository: SupportDataRepository,
): ApplicationEngine {
    val server = embeddedServer(CIO, host = config.mcpHost, port = config.mcpPort) {
        install(CORS) {
            allowHost("127.0.0.1:${config.mcpPort}", schemes = listOf("http"))
            allowHost("localhost:${config.mcpPort}", schemes = listOf("http"))
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowNonSimpleContentTypes = true
            allowHeader("Mcp-Session-Id")
            allowHeader("Mcp-Protocol-Version")
            exposeHeader("Mcp-Session-Id")
            exposeHeader("Mcp-Protocol-Version")
            exposeHeader(HttpHeaders.Location)
        }
        mcpStreamableHttp(path = "/mcp") {
            SupportMcpServerFactory(repository).create()
        }
    }
    try {
        server.startSuspend(wait = false)
        val connectors = server.engine.resolvedConnectors()
        require(connectors.size == 1) { "Embedded MCP must expose exactly one connector." }
        val connector = connectors.single()
        require(connector.port == config.mcpPort && connector.host == config.mcpHost) {
            "Embedded MCP bound an unexpected connector ${connector.host}:${connector.port}."
        }
        return server.engine
    } catch (error: Exception) {
        server.stop(0, 500)
        throw error
    }
}
