import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

class CoursePipelineAgent(private val config: AppConfig) {
    suspend fun run(): Boolean =
        runCatching {
            withClient { client ->
                runConnected(client)
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                println("AGENT RUN FAILED: ${error.message ?: error::class.simpleName}")
                println("Hint: live Telegram + Eliza may need more time. Increase MCP_TIMEOUT_SECONDS if this repeats.")
                false
            },
        )

    private suspend fun runConnected(client: Client): Boolean {
        val tools = client.listTools().tools
        val toolDescriptors = tools.map { McpToolDescriptor(it.name, it.description ?: "") }
        println("TOOLS RETURNED: ${tools.size}")
        tools.forEachIndexed { index, tool ->
            println("${index + 1}. ${tool.name}")
            println("   description: ${tool.description}")
        }
        println()
        println("USER REQUEST")
        println(config.pipelineRequest)
        println()

        val planner = createPipelinePlanner(config)
        val completed = mutableListOf<String>()
        var handoffJson: String? = null

        repeat(config.pipelineMaxSteps) { stepIndex ->
            val planned = planner.chooseNextTool(config.pipelineRequest, toolDescriptors, completed)
            val toolName = planned.toolName
                ?.takeIf { isCallable(it, completed, handoffJson) }
                ?: LocalPipelinePlanner.chooseNextTool(config.pipelineRequest, toolDescriptors, completed).toolName

            if (toolName == null) {
                println("PIPELINE COMPLETE: no remaining tool")
                return completed == CoursePipelineTool.ORDER
            }

            println("PLANNER STEP ${stepIndex + 1}")
            println("mode: ${planned.mode}")
            println("choice: ${planned.toolName ?: "none"}")
            if (planned.toolName != toolName) println("fallback choice: $toolName")
            println("note: ${planned.note}")
            println()

            val result = client.callTool(name = toolName, arguments = argumentsFor(toolName, handoffJson))
            val toolText = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            println("TOOL CALL: $toolName")
            println(toolText)
            println()

            if (result.isError == true) return false

            result.structuredContent?.get("handoffJson")?.jsonPrimitive?.contentOrNull?.let {
                handoffJson = it
                println("HANDOFF JSON FROM $toolName")
                println(it.shortPreview(700))
                println("handoff chars: ${it.length}")
                println()
            }

            completed += toolName
            if (completed == CoursePipelineTool.ORDER) return true
        }

        println("PIPELINE STOPPED: max steps ${config.pipelineMaxSteps} reached")
        return completed == CoursePipelineTool.ORDER
    }

    private fun isCallable(toolName: String, completed: List<String>, handoffJson: String?): Boolean =
        when (toolName) {
            CoursePipelineTool.SEARCH_MESSAGES -> toolName !in completed
            CoursePipelineTool.SUMMARIZE_DISCUSSION -> CoursePipelineTool.SEARCH_MESSAGES in completed && !handoffJson.isNullOrBlank() && toolName !in completed
            CoursePipelineTool.SAVE_RESULT -> CoursePipelineTool.SUMMARIZE_DISCUSSION in completed && !handoffJson.isNullOrBlank() && toolName !in completed
            else -> false
        }

    private fun argumentsFor(toolName: String, handoffJson: String?): Map<String, Any> =
        when (toolName) {
            CoursePipelineTool.SEARCH_MESSAGES -> mapOf(
                "courseDay" to config.courseDay,
                "chat" to config.telegramChat,
                "limit" to config.telegramLimit,
            )
            CoursePipelineTool.SUMMARIZE_DISCUSSION,
            CoursePipelineTool.SAVE_RESULT,
            -> mapOf("handoffJson" to requireNotNull(handoffJson) { "handoffJson missing before $toolName" })
            else -> error("Unsupported tool: $toolName")
        }

    private suspend fun <T> withClient(block: suspend (Client) -> T): T =
        HttpClient(CIO) {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                connectTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                socketTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
            }
        }.use { httpClient ->
            val client = Client(
                clientInfo = Implementation(
                    name = config.clientName,
                    version = "1.0.0",
                ),
            )
            val transport = StreamableHttpClientTransport(
                client = httpClient,
                url = config.serverUrl,
            )

            client.connect(transport)
            block(client)
        }
}
