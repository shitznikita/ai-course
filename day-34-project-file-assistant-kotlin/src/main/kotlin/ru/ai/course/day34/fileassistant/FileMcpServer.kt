package ru.ai.course.day34.fileassistant

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object FileMcpAuth {
    const val HEADER = "X-Day34-Session"
}

class FileMcpServerFactory(private val workspace: ProjectWorkspace) {
    fun create(): Server {
        val server = Server(
            serverInfo = Implementation("ai-course-day-34-project-files", "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        server.addTool(
            name = FileTool.LIST_FILES,
            description = "Discover bounded project text files. Excluded, binary, generated, secret, and symlink paths are omitted.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("prefix", stringProperty("Optional project-relative prefix.", maxLength = 240))
                    put("limit", integerProperty("Maximum files. Default 80.", 1, 200))
                },
            ),
        ) { request ->
            toolResult {
                requireArgumentKeys(request.arguments, setOf("prefix", "limit"))
                workspace.listFiles(
                    prefix = optionalString(request.arguments, "prefix"),
                    limit = optionalInt(request.arguments, "limit", 80),
                )
            }
        }

        server.addTool(
            name = FileTool.SEARCH_TEXT,
            description = "Search a literal string across bounded allowed project files and return path, line, and compact matching text.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("query", stringProperty("Literal text to find.", minLength = 1, maxLength = 128))
                    put("prefix", stringProperty("Optional project-relative prefix.", maxLength = 240))
                    put("limit", integerProperty("Maximum hits. Default 40.", 1, 100))
                },
                required = listOf("query"),
            ),
        ) { request ->
            toolResult {
                requireArgumentKeys(request.arguments, setOf("query", "prefix", "limit"))
                workspace.searchText(
                    query = requiredString(request.arguments, "query"),
                    prefix = optionalString(request.arguments, "prefix"),
                    limit = optionalInt(request.arguments, "limit", 40),
                )
            }
        }

        server.addTool(
            name = FileTool.READ_FILES,
            description = "Read 1-6 allowed files selected after discovery/search. Returns normalized text, byte count, and SHA-256.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("paths", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("description", JsonPrimitive("One to six project-relative paths."))
                        put("minItems", JsonPrimitive(1))
                        put("maxItems", JsonPrimitive(6))
                        put("uniqueItems", JsonPrimitive(true))
                        put("items", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("maxLength", JsonPrimitive(240))
                        })
                    })
                },
                required = listOf("paths"),
            ),
        ) { request ->
            toolResult {
                requireArgumentKeys(request.arguments, setOf("paths"))
                workspace.readFiles(requiredStringList(request.arguments, "paths"))
            }
        }

        server.addTool(
            name = FileTool.WRITE_FILE,
            description = "Create or replace one allowed text file. Existing files require a matching SHA-256 from this session. Preview writes stay in an overlay.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("path", stringProperty("Project-relative target path.", minLength = 1, maxLength = 240))
                    put("content", stringProperty("Complete new UTF-8 text content.", maxLength = ProjectFilePolicy.MAX_WRITE_BYTES))
                    put("expectedSha256", stringProperty("Required SHA-256 when replacing an existing file.", minLength = 64, maxLength = 64))
                },
                required = listOf("path", "content"),
            ),
        ) { request ->
            toolResult {
                requireArgumentKeys(request.arguments, setOf("path", "content", "expectedSha256"))
                workspace.writeFile(
                    pathValue = requiredString(request.arguments, "path"),
                    contentValue = requiredContent(request.arguments, "content"),
                    expectedSha256 = optionalString(request.arguments, "expectedSha256"),
                )
            }
        }

        server.addTool(
            name = FileTool.UNIFIED_DIFF,
            description = "Return a deterministic unified diff for every session change relative to its baseline.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            toolResult {
                requireArgumentKeys(it.arguments, emptySet())
                workspace.unifiedDiff()
            }
        }
        return server
    }

    private fun stringProperty(
        description: String,
        minLength: Int? = null,
        maxLength: Int? = null,
    ) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
        minLength?.let { put("minLength", JsonPrimitive(it)) }
        maxLength?.let { put("maxLength", JsonPrimitive(it)) }
    }

    private fun integerProperty(description: String, minimum: Int, maximum: Int) = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
        put("minimum", JsonPrimitive(minimum))
        put("maximum", JsonPrimitive(maximum))
    }

    private fun requiredString(arguments: JsonObject?, name: String): String =
        arguments?.get(name)?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)
            ?: error("$name is required.")

    private fun requiredContent(arguments: JsonObject?, name: String): String =
        arguments?.get(name)?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
            ?: error("$name is required and must be a string.")

    private fun optionalString(arguments: JsonObject?, name: String): String? =
        arguments?.get(name)?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
            ?.trim()?.takeIf(String::isNotBlank)

    private fun optionalInt(arguments: JsonObject?, name: String, default: Int): Int =
        arguments?.get(name)?.jsonPrimitive?.intOrNull ?: default

    private fun requireArgumentKeys(arguments: JsonObject?, allowed: Set<String>) {
        val keys = arguments?.keys.orEmpty()
        require(keys.all { it in allowed }) {
            "Unexpected tool arguments: ${(keys - allowed).sorted().joinToString()}."
        }
    }

    private fun requiredStringList(arguments: JsonObject?, name: String): List<String> {
        val value = arguments?.get(name) as? JsonArray ?: error("$name must be an array.")
        return value.jsonArray.map { item ->
            item.jsonPrimitive.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                ?: error("$name must contain non-blank strings.")
        }
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

data class RunningFileMcpServer(
    val engine: ApplicationEngine,
    val port: Int,
)

suspend fun startFileMcpServer(
    config: AppConfig,
    workspace: ProjectWorkspace,
): RunningFileMcpServer {
    val server = embeddedServer(CIO, host = config.mcpHost, port = config.mcpPort) {
        intercept(ApplicationCallPipeline.Setup) {
            if (call.request.path().startsWith("/mcp") &&
                call.request.headers[FileMcpAuth.HEADER] != config.mcpSessionToken
            ) {
                call.respondText("Unauthorized MCP session.", status = HttpStatusCode.Unauthorized)
                finish()
            }
        }
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
            allowHeader(FileMcpAuth.HEADER)
            exposeHeader("Mcp-Session-Id")
            exposeHeader("Mcp-Protocol-Version")
            exposeHeader(HttpHeaders.Location)
        }
        mcpStreamableHttp(path = "/mcp") {
            FileMcpServerFactory(workspace).create()
        }
    }
    try {
        server.startSuspend(wait = false)
        val connectors = server.engine.resolvedConnectors()
        require(connectors.size == 1) { "Embedded MCP must expose exactly one connector." }
        val connector = connectors.single()
        require(connector.host == config.mcpHost &&
            (config.mcpPort == 0 || connector.port == config.mcpPort)
        ) {
            "Embedded MCP bound unexpected connector ${connector.host}:${connector.port}."
        }
        return RunningFileMcpServer(server.engine, connector.port)
    } catch (error: Exception) {
        server.stop(0, 500)
        throw error
    }
}
