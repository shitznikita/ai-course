import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
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

data class OrchestrationStep(
    val serverId: String,
    val toolName: String,
) {
    val route: String = "$serverId/$toolName"
}

object OrchestrationTool {
    const val READ_MESSAGES = "read_course_chat_messages"
    const val EXTRACT_WINDOW = "extract_course_day_window"
    const val CHUNK_DISCUSSION = "chunk_course_discussion"
    const val BUILD_BRIEF = "build_execution_brief"
    const val BUILD_PROMPT = "build_codex_execution_prompt"
    const val SAVE_ARTIFACTS = "save_orchestration_artifacts"
    const val READ_LATEST = "read_latest_orchestration_artifact"

    val ORDER: List<OrchestrationStep> = listOf(
        OrchestrationStep("source", READ_MESSAGES),
        OrchestrationStep("window", EXTRACT_WINDOW),
        OrchestrationStep("window", CHUNK_DISCUSSION),
        OrchestrationStep("brief", BUILD_BRIEF),
        OrchestrationStep("brief", BUILD_PROMPT),
        OrchestrationStep("storage", SAVE_ARTIFACTS),
        OrchestrationStep("storage", READ_LATEST),
    )
}

object OrchestrationMcpServerFactory {
    fun create(serverId: String, config: AppConfig): Server {
        val endpoint = config.endpoint(serverId)
        val server = Server(
            serverInfo = Implementation(
                name = "ai-course-day-20-${endpoint.displayName}",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        when (serverId) {
            "source" -> registerSourceTools(server, config)
            "window" -> registerWindowTools(server, config)
            "brief" -> registerBriefTools(server)
            "storage" -> registerStorageTools(server, config)
            else -> error("Unsupported MCP server id: $serverId")
        }

        return server
    }

    private fun registerSourceTools(server: Server, config: AppConfig) {
        val service = SourceMcpService(config)
        server.addTool(
            name = OrchestrationTool.READ_MESSAGES,
            description = "Read recent Telegram course chat messages through a read-only TDLib backend or offline fixture.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
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
                val chat = parseChat(config, call.arguments)
                val limit = parseLimit(config, call.arguments)
                val result = service.readMessages(chat, limit)
                CallToolResult(
                    content = listOf(TextContent(text = OrchestrationResultFormatter.formatSource(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }
    }

    private fun registerWindowTools(server: Server, config: AppConfig) {
        val service = WindowMcpService(config)
        server.addTool(
            name = OrchestrationTool.EXTRACT_WINDOW,
            description = "Extract one course-day assignment/discussion window from source messages and exclude the next day marker.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("handoffJson", stringProperty("Exact handoffJson returned by read_course_chat_messages."))
                    put("courseDay", stringProperty("Course day number or 'auto'. Default comes from COURSE_DAY."))
                },
                required = listOf("handoffJson"),
            ),
        ) { call ->
            runTool {
                val result = service.extractWindow(
                    sourceHandoffJson = requiredHandoffJson(call.arguments),
                    courseDay = parseCourseDay(config, call.arguments),
                )
                CallToolResult(
                    content = listOf(TextContent(text = OrchestrationResultFormatter.formatWindow(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }

        server.addTool(
            name = OrchestrationTool.CHUNK_DISCUSSION,
            description = "Split extracted course-day messages into bounded chunks for agent-side LLM analysis.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("handoffJson", stringProperty("Exact handoffJson returned by extract_course_day_window."))
                    put(
                        "messagesPerChunk",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Selected messages per chunk. Default ORCHESTRATION_CHUNK_MESSAGES."))
                            put("minimum", JsonPrimitive(2))
                            put("maximum", JsonPrimitive(20))
                        },
                    )
                },
                required = listOf("handoffJson"),
            ),
        ) { call ->
            runTool {
                val result = service.chunkDiscussion(
                    windowHandoffJson = requiredHandoffJson(call.arguments),
                    messagesPerChunk = call.arguments?.get("messagesPerChunk")?.jsonPrimitive?.intOrNull
                        ?: config.chunkMessagesPerChunk,
                )
                CallToolResult(
                    content = listOf(TextContent(text = OrchestrationResultFormatter.formatChunks(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }
    }

    private fun registerBriefTools(server: Server) {
        val service = BriefMcpService()
        server.addTool(
            name = OrchestrationTool.BUILD_BRIEF,
            description = "Build a project-aware execution brief from agent analysis. The MCP tool does no LLM call.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("handoffJson", stringProperty("Exact handoffJson produced by the orchestration agent analysis step."))
                },
                required = listOf("handoffJson"),
            ),
        ) { call ->
            runTool {
                val result = service.buildExecutionBrief(requiredHandoffJson(call.arguments))
                CallToolResult(
                    content = listOf(TextContent(text = OrchestrationResultFormatter.formatBrief(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }

        server.addTool(
            name = OrchestrationTool.BUILD_PROMPT,
            description = "Convert an execution brief into a ready Codex/GPT-5.5 implementation prompt.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("handoffJson", stringProperty("Exact handoffJson returned by build_execution_brief."))
                },
                required = listOf("handoffJson"),
            ),
        ) { call ->
            runTool {
                val result = service.buildCodexPrompt(requiredHandoffJson(call.arguments))
                CallToolResult(
                    content = listOf(TextContent(text = OrchestrationResultFormatter.formatPrompt(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }
    }

    private fun registerStorageTools(server: Server, config: AppConfig) {
        val service = StorageMcpService(OrchestrationStorage(config))
        server.addTool(
            name = OrchestrationTool.SAVE_ARTIFACTS,
            description = "Persist orchestration prompt handoffJson to state/runs, state/latest-orchestration.json, and state/latest-report.md.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("handoffJson", stringProperty("Exact handoffJson returned by build_codex_execution_prompt."))
                },
                required = listOf("handoffJson"),
            ),
        ) { call ->
            runTool {
                val result = service.save(requiredHandoffJson(call.arguments))
                CallToolResult(
                    content = listOf(TextContent(text = OrchestrationResultFormatter.formatSave(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }

        server.addTool(
            name = OrchestrationTool.READ_LATEST,
            description = "Read the latest saved orchestration artifact without calling Telegram or LLM.",
            inputSchema = ToolSchema(properties = buildJsonObject { }),
        ) {
            runTool {
                val result = service.readLatest()
                CallToolResult(
                    content = listOf(TextContent(text = OrchestrationResultFormatter.formatLatest(result))),
                    isError = false,
                    structuredContent = result.toJson(),
                )
            }
        }
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

    private fun parseChat(config: AppConfig, arguments: JsonObject?): String {
        val chat = arguments?.get("chat")?.jsonPrimitive?.contentOrNull?.trim() ?: config.telegramChat
        require(chat.isNotBlank()) { "chat must not be blank." }
        require(chat.length <= 128) { "chat is too long." }
        require(!chat.contains('\n') && !chat.contains('\r')) { "chat must be a single-line identifier." }
        return chat
    }

    private fun parseLimit(config: AppConfig, arguments: JsonObject?): Int {
        val limit = arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: config.telegramLimit
        require(limit in 1..500) { "limit must be between 1 and 500." }
        return limit
    }

    private fun parseCourseDay(config: AppConfig, arguments: JsonObject?): String {
        val courseDay = arguments?.get("courseDay")?.jsonPrimitive?.contentOrNull?.trim() ?: config.courseDay
        require(courseDay.equals("auto", ignoreCase = true) || courseDay.toIntOrNull() in 1..999) {
            "courseDay must be 'auto' or a number between 1 and 999."
        }
        return courseDay
    }

    private fun requiredHandoffJson(arguments: JsonObject?): String {
        val value = arguments?.get("handoffJson")?.jsonPrimitive?.contentOrNull?.trim()
        require(!value.isNullOrBlank()) { "handoffJson is required." }
        require(value.length <= 2_000_000) { "handoffJson is too large." }
        return value
    }

    private fun stringProperty(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }
}

fun startOrchestrationMcpServers(config: AppConfig): List<EmbeddedServer<*, *>> =
    config.endpoints.map { endpoint ->
        embeddedServer(CIO, host = endpoint.host, port = endpoint.port) {
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
                OrchestrationMcpServerFactory.create(endpoint.id, config)
            }
        }.start(wait = false)
    }

fun stopOrchestrationMcpServers(servers: List<EmbeddedServer<*, *>>) {
    servers.forEach { it.stop(500, 1_000) }
}
