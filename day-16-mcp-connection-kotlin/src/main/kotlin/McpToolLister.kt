import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlin.time.Duration.Companion.seconds

class McpToolLister(private val config: AppConfig) {
    suspend fun listTools(): List<Tool> {
        val httpClient = HttpClient(CIO) {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                connectTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                socketTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
            }
        }

        return httpClient.use {
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
            client.listTools().tools
        }
    }
}
