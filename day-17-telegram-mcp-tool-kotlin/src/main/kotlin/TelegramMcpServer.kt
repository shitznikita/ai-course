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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object TelegramTool {
    const val READ_MESSAGES = "read_telegram_chat_messages"
    const val LIST_CHATS = "list_telegram_chats"
}

object TelegramMcpServerFactory {
    fun create(config: AppConfig): Server {
        val reader = TelegramBackends.create(config)
        val server = Server(
            serverInfo = Implementation(
                name = "ai-course-day-17-telegram-mcp",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        server.addTool(
            name = TelegramTool.READ_MESSAGES,
            description = "Read recent Telegram chat messages through a read-only TDLib/MTProto backend or offline fixture.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("chat", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Telegram numeric chat id, public @username, or fixture chat name."))
                    })
                    put("limit", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("description", JsonPrimitive("How many recent messages to return. Default 10, maximum 500."))
                        put("minimum", JsonPrimitive(1))
                        put("maximum", JsonPrimitive(500))
                    })
                    put("onlyLocal", buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("description", JsonPrimitive("TDLib only_local flag. Default false."))
                    })
                    put("includeSender", buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("description", JsonPrimitive("Include sender ids. Default false to reduce privacy exposure."))
                    })
                },
                required = listOf("chat"),
            ),
        ) { request ->
            try {
                val toolRequest = parseToolArguments(request.arguments)
                val result = reader.readMessages(toolRequest)
                CallToolResult(
                    content = listOf(TextContent(text = TelegramResultFormatter.format(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Tool call failed: ${e.message ?: e::class.simpleName}")),
                    isError = true,
                )
            }
        }

        server.addTool(
            name = TelegramTool.LIST_CHATS,
            description = "List recent Telegram chats visible to the authorized TDLib account and return chat ids for private chat reading.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("description", JsonPrimitive("How many recent chats to return. Default 20, maximum 100."))
                        put("minimum", JsonPrimitive(1))
                        put("maximum", JsonPrimitive(100))
                    })
                },
            ),
        ) { request ->
            try {
                val listRequest = parseListChatsArguments(request.arguments)
                val result = reader.listChats(listRequest)
                CallToolResult(
                    content = listOf(TextContent(text = TelegramResultFormatter.formatChats(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Tool call failed: ${e.message ?: e::class.simpleName}")),
                    isError = true,
                )
            }
        }

        return server
    }

    private fun parseToolArguments(arguments: JsonObject?): TelegramReadRequest {
        val chat = arguments?.get("chat")?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw IllegalArgumentException("chat is required")
        require(chat.isNotBlank()) { "chat must not be blank" }
        require(chat.length <= 128) { "chat is too long" }
        require(!chat.contains('\n') && !chat.contains('\r')) { "chat must be a single-line identifier" }

        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 10
        require(limit in 1..500) { "limit must be between 1 and 500" }

        return TelegramReadRequest(
            chat = chat,
            limit = limit,
            onlyLocal = arguments["onlyLocal"]?.jsonPrimitive?.booleanOrNull ?: false,
            includeSender = arguments["includeSender"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun parseListChatsArguments(arguments: JsonObject?): TelegramListChatsRequest {
        val limit = arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 20
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return TelegramListChatsRequest(limit = limit)
    }
}

fun startTelegramMcpServer(config: AppConfig, wait: Boolean) =
    embeddedServer(CIO, host = config.serverHost, port = config.serverPort) {
        install(CORS) {
            anyHost()
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
            TelegramMcpServerFactory.create(config)
        }
    }.start(wait = wait)
