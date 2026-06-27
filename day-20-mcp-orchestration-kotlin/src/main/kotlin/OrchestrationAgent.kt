import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

data class ConnectedMcpServer(
    val endpoint: McpEndpointConfig,
    val client: Client,
)

class OrchestrationAgent(private val config: AppConfig) {
    suspend fun run(): Boolean =
        runCatching {
            withClients { servers ->
                runConnected(servers)
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                println("AGENT RUN FAILED: ${error.message ?: error::class.simpleName}")
                println("Hint: live Telegram + Eliza may need more time. Increase MCP_TIMEOUT_SECONDS if this repeats.")
                false
            },
        )

    private suspend fun runConnected(servers: List<ConnectedMcpServer>): Boolean {
        val registry = discoverTools(servers)
        val byServer = servers.associateBy { it.endpoint.id }
        val planner = createOrchestrationPlanner(config)
        val analyzer = createAgentChunkAnalyzer(config)
        val completed = mutableListOf<OrchestrationStep>()

        var sourceHandoff: String? = null
        var windowHandoff: String? = null
        var chunksHandoff: String? = null
        var analysisHandoff: String? = null
        var briefHandoff: String? = null
        var promptHandoff: String? = null

        println("USER REQUEST")
        println(config.orchestrationRequest)
        println()

        repeat(config.orchestrationMaxSteps) { stepIndex ->
            if (completed.contains(OrchestrationStep("window", OrchestrationTool.CHUNK_DISCUSSION)) && analysisHandoff == null) {
                val chunks = DiscussionChunksResult.fromHandoffJson(requireNotNull(chunksHandoff))
                val analysis = analyzer.analyze(chunks)
                analysisHandoff = analysis.handoffJson()
                println("AGENT ANALYSIS")
                println(OrchestrationResultFormatter.formatAnalysis(analysis))
                println("HANDOFF JSON FROM agent-analysis")
                println(analysisHandoff.shortPreview(700))
                println("handoff chars: ${analysisHandoff.length}")
                println()
            }

            val planned = planner.chooseNextTool(config.orchestrationRequest, registry, completed)
            val step = planned.asStep()
                ?.takeIf { isCallable(it, completed, sourceHandoff, windowHandoff, chunksHandoff, analysisHandoff, briefHandoff, promptHandoff) }
                ?: LocalOrchestrationPlanner.chooseNextTool(config.orchestrationRequest, registry, completed)
                    .asStep()
                    ?.takeIf { isCallable(it, completed, sourceHandoff, windowHandoff, chunksHandoff, analysisHandoff, briefHandoff, promptHandoff) }

            if (step == null) {
                println("ORCHESTRATION COMPLETE: no remaining callable tool")
                return completed == OrchestrationTool.ORDER
            }

            val arguments = argumentsFor(step, sourceHandoff, windowHandoff, analysisHandoff, briefHandoff, promptHandoff)
            println("ROUTING STEP ${stepIndex + 1}")
            println("planner mode: ${planned.mode}")
            println("planner choice: ${planned.serverId ?: "none"}/${planned.toolName ?: "none"}")
            if (planned.asStep() != step) println("fallback route: ${step.route}")
            println("planner note: ${planned.note}")
            println("server: ${step.serverId}")
            println("tool: ${step.toolName}")
            println("arguments preview: ${arguments.preview()}")
            println()

            val server = byServer.getValue(step.serverId)
            val result = server.client.callTool(name = step.toolName, arguments = arguments)
            val toolText = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            println("TOOL CALL: ${server.endpoint.displayName}/${step.toolName}")
            println(toolText)
            println()

            if (result.isError == true) return false

            result.structuredContent?.get("handoffJson")?.jsonPrimitive?.contentOrNull?.let { handoff ->
                when (step.toolName) {
                    OrchestrationTool.READ_MESSAGES -> sourceHandoff = handoff
                    OrchestrationTool.EXTRACT_WINDOW -> windowHandoff = handoff
                    OrchestrationTool.CHUNK_DISCUSSION -> chunksHandoff = handoff
                    OrchestrationTool.BUILD_BRIEF -> briefHandoff = handoff
                    OrchestrationTool.BUILD_PROMPT -> promptHandoff = handoff
                }
                println("HANDOFF JSON FROM ${step.route}")
                println(handoff.shortPreview(700))
                println("handoff chars: ${handoff.length}")
                println()
            }

            completed += step
            if (completed == OrchestrationTool.ORDER) return true
        }

        println("ORCHESTRATION STOPPED: max steps ${config.orchestrationMaxSteps} reached")
        return completed == OrchestrationTool.ORDER
    }

    private suspend fun discoverTools(servers: List<ConnectedMcpServer>): List<McpToolDescriptor> {
        println("REGISTERED MCP SERVERS: ${servers.size}")
        val registry = mutableListOf<McpToolDescriptor>()
        servers.forEach { server ->
            val tools = server.client.listTools().tools
            println("${server.endpoint.displayName}: ${server.endpoint.url}")
            println("TOOLS RETURNED: ${tools.size}")
            tools.forEachIndexed { index, tool ->
                println("${index + 1}. ${tool.name}")
                println("   description: ${tool.description}")
                registry += McpToolDescriptor(
                    serverId = server.endpoint.id,
                    serverName = server.endpoint.displayName,
                    name = tool.name,
                    description = tool.description ?: "",
                )
            }
            println()
        }
        println("GLOBAL TOOL REGISTRY: ${registry.size} tools")
        registry.forEach { println("- ${it.route}") }
        println()
        return registry
    }

    private fun isCallable(
        step: OrchestrationStep,
        completed: List<OrchestrationStep>,
        sourceHandoff: String?,
        windowHandoff: String?,
        chunksHandoff: String?,
        analysisHandoff: String?,
        briefHandoff: String?,
        promptHandoff: String?,
    ): Boolean =
        when (step.toolName) {
            OrchestrationTool.READ_MESSAGES -> step !in completed
            OrchestrationTool.EXTRACT_WINDOW -> sourceHandoff != null && step !in completed
            OrchestrationTool.CHUNK_DISCUSSION -> windowHandoff != null && step !in completed
            OrchestrationTool.BUILD_BRIEF -> chunksHandoff != null && analysisHandoff != null && step !in completed
            OrchestrationTool.BUILD_PROMPT -> briefHandoff != null && step !in completed
            OrchestrationTool.SAVE_ARTIFACTS -> promptHandoff != null && step !in completed
            OrchestrationTool.READ_LATEST -> OrchestrationStep("storage", OrchestrationTool.SAVE_ARTIFACTS) in completed && step !in completed
            else -> false
        }

    private fun argumentsFor(
        step: OrchestrationStep,
        sourceHandoff: String?,
        windowHandoff: String?,
        analysisHandoff: String?,
        briefHandoff: String?,
        promptHandoff: String?,
    ): Map<String, Any> =
        when (step.toolName) {
            OrchestrationTool.READ_MESSAGES -> mapOf(
                "chat" to config.telegramChat,
                "limit" to config.telegramLimit,
            )
            OrchestrationTool.EXTRACT_WINDOW -> mapOf(
                "handoffJson" to requireNotNull(sourceHandoff),
                "courseDay" to config.courseDay,
            )
            OrchestrationTool.CHUNK_DISCUSSION -> mapOf(
                "handoffJson" to requireNotNull(windowHandoff),
                "messagesPerChunk" to config.chunkMessagesPerChunk,
            )
            OrchestrationTool.BUILD_BRIEF -> mapOf("handoffJson" to requireNotNull(analysisHandoff))
            OrchestrationTool.BUILD_PROMPT -> mapOf("handoffJson" to requireNotNull(briefHandoff))
            OrchestrationTool.SAVE_ARTIFACTS -> mapOf("handoffJson" to requireNotNull(promptHandoff))
            OrchestrationTool.READ_LATEST -> emptyMap()
            else -> error("Unsupported orchestration step: ${step.route}")
        }

    private suspend fun <T> withClients(block: suspend (List<ConnectedMcpServer>) -> T): T =
        HttpClient(CIO) {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                connectTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                socketTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
            }
        }.use { httpClient ->
            val servers = config.endpoints.map { endpoint ->
                val client = Client(
                    clientInfo = Implementation(
                        name = "${config.clientName}-${endpoint.id}",
                        version = "1.0.0",
                    ),
                )
                val transport = StreamableHttpClientTransport(
                    client = httpClient,
                    url = endpoint.url,
                )
                client.connect(transport)
                ConnectedMcpServer(endpoint, client)
            }
            block(servers)
        }

    private fun PlannerDecision.asStep(): OrchestrationStep? =
        if (serverId.isNullOrBlank() || toolName.isNullOrBlank()) null else OrchestrationStep(serverId, toolName)

    private fun Map<String, Any>.preview(): String =
        entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val rendered = when (value) {
                is String -> value.shortPreview(180)
                is JsonPrimitive -> value.toString().shortPreview(180)
                else -> value.toString().shortPreview(180)
            }
            "$key=$rendered"
        }.shortPreview(700)
}
