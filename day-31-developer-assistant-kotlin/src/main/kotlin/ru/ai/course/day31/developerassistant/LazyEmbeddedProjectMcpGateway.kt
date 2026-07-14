package ru.ai.course.day31.developerassistant

import kotlinx.coroutines.delay

internal data class RunningProjectContextGateway(
    val gateway: ProjectContextGateway,
    val close: () -> Unit,
)

class LazyEmbeddedProjectMcpGateway internal constructor(
    private val config: AppConfig,
    private val starter: suspend () -> RunningProjectContextGateway = { startDefault(config) },
) : ProjectContextGateway, AutoCloseable {
    private var running: RunningProjectContextGateway? = null

    override suspend fun fetchContext(
        includeFiles: Boolean,
        prefix: String?,
        fileLimit: Int,
    ): McpProjectContext =
        ensureRunning().gateway.fetchContext(includeFiles, prefix, fileLimit)

    override fun close() {
        running?.close?.invoke()
        running = null
    }

    private suspend fun ensureRunning(): RunningProjectContextGateway {
        running?.let { return it }
        return starter().also { running = it }
    }

    private companion object {
        suspend fun startDefault(config: AppConfig): RunningProjectContextGateway {
            val server = startProjectMcpServer(
                config,
                GitProjectGateway(config.projectRoot),
                wait = false,
            )
            try {
                delay(300)
                return RunningProjectContextGateway(
                    gateway = ProjectMcpClient(config),
                    close = { server.stop(500, 1_000) },
                )
            } catch (error: Exception) {
                server.stop(500, 1_000)
                throw error
            }
        }
    }
}
