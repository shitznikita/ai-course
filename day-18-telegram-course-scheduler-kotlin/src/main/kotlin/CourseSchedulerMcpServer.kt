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

object CourseSchedulerTool {
    const val GENERATE_PROMPT = "generate_course_day_prompt"
    const val GET_LATEST_PROMPT = "get_latest_course_day_prompt"
}

object CourseSchedulerMcpServerFactory {
    fun create(config: AppConfig): Server {
        val service = CoursePromptService(config)
        val server = Server(
            serverInfo = Implementation(
                name = "ai-course-day-18-telegram-course-scheduler",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        server.addTool(
            name = CourseSchedulerTool.GENERATE_PROMPT,
            description = "Read Telegram course chat, extract one course-day discussion window, save JSON/Markdown state, and return an implementation prompt.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("courseDay", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Course day number or 'auto'. Default comes from COURSE_DAY."))
                    })
                    put("chat", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Telegram numeric chat id, public @username, or fixture chat name."))
                    })
                    put("limit", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("description", JsonPrimitive("How many recent Telegram messages to inspect. Default TELEGRAM_LIMIT, maximum 500."))
                        put("minimum", JsonPrimitive(1))
                        put("maximum", JsonPrimitive(500))
                    })
                    put("persist", buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("description", JsonPrimitive("Save run JSON and latest-prompt.md. Default true."))
                    })
                },
            ),
        ) { call ->
            try {
                val result = service.generate(parseGenerateArguments(config, call.arguments))
                CallToolResult(
                    content = listOf(TextContent(text = CourseResultFormatter.format(result))),
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
            name = CourseSchedulerTool.GET_LATEST_PROMPT,
            description = "Return the latest saved course-day prompt without calling Telegram or the LLM.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            try {
                val result = service.latest()
                CallToolResult(
                    content = listOf(TextContent(text = CourseResultFormatter.formatLatest(result))),
                    isError = false,
                    structuredContent = buildJsonObject {
                        put("latestRunJson", JsonPrimitive(result.latestRunJson))
                        put("latestPromptMarkdown", JsonPrimitive(result.latestPromptMarkdown))
                        put("prompt", JsonPrimitive(result.prompt))
                    },
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

    private fun parseGenerateArguments(config: AppConfig, arguments: JsonObject?): CourseDayPromptRequest {
        val courseDay = arguments?.get("courseDay")?.jsonPrimitive?.contentOrNull?.trim() ?: config.courseDay
        require(courseDay.equals("auto", ignoreCase = true) || courseDay.toIntOrNull() in 1..999) {
            "courseDay must be 'auto' or a number between 1 and 999"
        }

        val chat = arguments?.get("chat")?.jsonPrimitive?.contentOrNull?.trim() ?: config.telegramChat
        require(chat.isNotBlank()) { "chat must not be blank" }
        require(chat.length <= 128) { "chat is too long" }
        require(!chat.contains('\n') && !chat.contains('\r')) { "chat must be a single-line identifier" }

        val limit = arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: config.telegramLimit
        require(limit in 1..500) { "limit must be between 1 and 500" }

        return CourseDayPromptRequest(
            courseDay = courseDay,
            chat = chat,
            limit = limit,
            persist = arguments?.get("persist")?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }
}

fun startCourseSchedulerMcpServer(config: AppConfig, wait: Boolean) =
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
            CourseSchedulerMcpServerFactory.create(config)
        }
    }.start(wait = wait)
