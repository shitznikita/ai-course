import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.time.Duration.Companion.seconds

class TelegramMcpAgent(private val config: AppConfig) {
    suspend fun run() {
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
            val tools = client.listTools().tools
            println("TOOLS RETURNED: ${tools.size}")
            tools.forEachIndexed { index, tool ->
                println("${index + 1}. ${tool.name}")
                println("   description: ${tool.description}")
            }
            println()

            val result = client.callTool(
                name = TelegramTool.READ_MESSAGES,
                arguments = mapOf(
                    "chat" to config.telegramChat,
                    "limit" to config.telegramLimit,
                    "onlyLocal" to false,
                    "includeSender" to false,
                ),
            )
            val toolText = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }

            println("TOOL RESULT")
            println(toolText)
            println()

            println("AGENT SUMMARY")
            val summarizer = if (config.llmApiKey.isNullOrBlank()) LocalTelegramSummarizer else ElizaTelegramSummarizer(config)
            println(summarizer.summarize(toolText))
        }
    }

    suspend fun listChats() {
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
            val result = client.callTool(
                name = TelegramTool.LIST_CHATS,
                arguments = mapOf("limit" to config.telegramLimit.coerceIn(1, 100)),
            )
            val toolText = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
            println("TOOL RESULT")
            println(toolText)
        }
    }
}
