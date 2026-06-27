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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object CoursePipelineTool {
    const val SEARCH_MESSAGES = "search_course_day_messages"
    const val SUMMARIZE_DISCUSSION = "summarize_course_day_discussion"
    const val SAVE_RESULT = "save_course_day_pipeline_result"

    val ORDER: List<String> = listOf(
        SEARCH_MESSAGES,
        SUMMARIZE_DISCUSSION,
        SAVE_RESULT,
    )
}

object CoursePipelineMcpServerFactory {
    fun create(config: AppConfig): Server {
        val service = CoursePipelineService(config)
        val server = Server(
            serverInfo = Implementation(
                name = "ai-course-day-19-mcp-tool-composition",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        server.addTool(
            name = CoursePipelineTool.SEARCH_MESSAGES,
            description = "Search Telegram course chat, find one course-day assignment/discussion window, and return handoffJson for the summary tool.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("courseDay", stringProperty("Course day number or 'auto'. Default comes from COURSE_DAY."))
                    put("chat", stringProperty("Telegram numeric chat id, public @username, or fixture chat name."))
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("How many recent Telegram messages to inspect. Default TELEGRAM_LIMIT, maximum 500."))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(500))
                        },
                    )
                },
            ),
        ) { call ->
            runTool {
                val result = service.search(parseSearchArguments(config, call.arguments))
                CallToolResult(
                    content = listOf(TextContent(text = PipelineResultFormatter.formatSearch(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }

        server.addTool(
            name = CoursePipelineTool.SUMMARIZE_DISCUSSION,
            description = "Convert search handoffJson into a project-aware conclusion, discussion report, and execution prompt for GPT-5.5/Codex.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("handoffJson", stringProperty("Exact handoffJson returned by search_course_day_messages."))
                },
                required = listOf("handoffJson"),
            ),
        ) { call ->
            runTool {
                val result = service.summarize(requiredHandoffJson(call.arguments))
                CallToolResult(
                    content = listOf(TextContent(text = PipelineResultFormatter.formatSummary(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }

        server.addTool(
            name = CoursePipelineTool.SAVE_RESULT,
            description = "Persist summary handoffJson to state/runs, state/latest-pipeline.json, and state/latest-report.md.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("handoffJson", stringProperty("Exact handoffJson returned by summarize_course_day_discussion."))
                },
                required = listOf("handoffJson"),
            ),
        ) { call ->
            runTool {
                val result = service.save(requiredHandoffJson(call.arguments))
                CallToolResult(
                    content = listOf(TextContent(text = PipelineResultFormatter.formatSave(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }

        return server
    }

    private fun runTool(block: () -> CallToolResult): CallToolResult =
        try {
            block()
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent(text = "Tool call failed: ${e.message ?: e::class.simpleName}")),
                isError = true,
            )
        }

    private fun parseSearchArguments(config: AppConfig, arguments: JsonObject?): SearchCourseDayMessagesRequest {
        val courseDay = arguments?.get("courseDay")?.jsonPrimitive?.contentOrNull?.trim() ?: config.courseDay
        require(courseDay.equals("auto", ignoreCase = true) || courseDay.toIntOrNull() in 1..999) {
            "courseDay must be 'auto' or a number between 1 and 999."
        }

        val chat = arguments?.get("chat")?.jsonPrimitive?.contentOrNull?.trim() ?: config.telegramChat
        require(chat.isNotBlank()) { "chat must not be blank." }
        require(chat.length <= 128) { "chat is too long." }
        require(!chat.contains('\n') && !chat.contains('\r')) { "chat must be a single-line identifier." }

        val limit = arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: config.telegramLimit
        require(limit in 1..500) { "limit must be between 1 and 500." }

        return SearchCourseDayMessagesRequest(
            courseDay = courseDay,
            chat = chat,
            limit = limit,
        )
    }

    private fun requiredHandoffJson(arguments: JsonObject?): String {
        val value = arguments?.get("handoffJson")?.jsonPrimitive?.contentOrNull?.trim()
        require(!value.isNullOrBlank()) { "handoffJson is required." }
        require(value.length <= 1_500_000) { "handoffJson is too large." }
        return value
    }

    private fun stringProperty(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }
}

fun startCoursePipelineMcpServer(config: AppConfig, wait: Boolean) =
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
            CoursePipelineMcpServerFactory.create(config)
        }
    }.start(wait = wait)
