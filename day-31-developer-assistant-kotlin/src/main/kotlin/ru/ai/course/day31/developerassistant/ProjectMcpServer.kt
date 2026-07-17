package ru.ai.course.day31.developerassistant

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object ProjectTool {
    const val CURRENT_BRANCH = "git_current_branch"
    const val LIST_FILES = "project_list_files"
}

class ProjectMcpServerFactory(
    private val gateway: GitProjectGateway,
) {
    fun create(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "ai-course-day-31-project-context",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        server.addTool(
            name = ProjectTool.CURRENT_BRANCH,
            description = "Return the current git branch. In detached HEAD, return an explicit marker and short commit SHA.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            toolResult { gateway.currentBranch() }
        }

        server.addTool(
            name = ProjectTool.LIST_FILES,
            description = "List bounded tracked project files through git ls-files. Untracked files and secrets are not exposed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("prefix", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Optional repository-relative path prefix."))
                    })
                    put("limit", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("minimum", JsonPrimitive(1))
                        put("maximum", JsonPrimitive(200))
                        put("description", JsonPrimitive("Maximum tracked files to return. Default 80."))
                    })
                },
            ),
        ) { request ->
            toolResult {
                val prefix = request.arguments?.get("prefix")?.jsonPrimitive?.contentOrNull
                val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 80
                gateway.listTrackedFiles(prefix, limit)
            }
        }
        return server
    }

    private inline fun <reified T> toolResult(block: () -> T): CallToolResult = try {
        val json = AppJson.strict.encodeToString(block())
        CallToolResult(
            content = listOf(TextContent(text = json)),
            isError = false,
            structuredContent = AppJson.strict.parseToJsonElement(json) as JsonObject,
        )
    } catch (error: Exception) {
        CallToolResult(
            content = listOf(TextContent(text = "Tool call failed: ${error.message ?: error::class.simpleName}")),
            isError = true,
        )
    }
}

fun startProjectMcpServer(config: AppConfig, gateway: GitProjectGateway, wait: Boolean) =
    embeddedServer(CIO, host = config.mcpHost, port = config.mcpPort) {
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
            ProjectMcpServerFactory(gateway).create()
        }
    }.start(wait = wait)
