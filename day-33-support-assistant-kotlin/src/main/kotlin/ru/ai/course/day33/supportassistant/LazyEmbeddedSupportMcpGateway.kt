package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.ServerSocket

internal data class RunningSupportGateway(
    val gateway: SupportContextGateway,
    val close: () -> Unit,
)

class LazyEmbeddedSupportMcpGateway internal constructor(
    private val config: AppConfig,
    private val starter: suspend () -> RunningSupportGateway = { startDefault(config) },
) : SupportContextGateway, AutoCloseable {
    private val mutex = Mutex()

    @Volatile
    private var running: RunningSupportGateway? = null

    override suspend fun fetch(ticketId: String): McpContextFetch =
        ensureRunning().gateway.fetch(ticketId)

    override fun close() {
        running?.close?.invoke()
        running = null
    }

    private suspend fun ensureRunning(): RunningSupportGateway {
        running?.let { return it }
        return mutex.withLock {
            running ?: starter().also { running = it }
        }
    }

    private companion object {
        suspend fun startDefault(config: AppConfig): RunningSupportGateway {
            val repository = JsonSupportDataRepository(config.fixturePath)
            requirePortAvailable(config)
            val server = startSupportMcpServer(config, repository)
            try {
                return RunningSupportGateway(
                    gateway = SupportMcpClient(config),
                    close = { server.stop(500, 1_500) },
                )
            } catch (error: Exception) {
                server.stop(500, 1_500)
                throw error
            }
        }

        private fun requirePortAvailable(config: AppConfig) {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(config.mcpHost, config.mcpPort))
                }
            } catch (error: Exception) {
                throw IllegalStateException(
                    "MCP port ${config.mcpHost}:${config.mcpPort} is already in use.",
                    error,
                )
            }
        }
    }
}
