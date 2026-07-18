package ru.ai.course.day34.fileassistant

import kotlinx.serialization.json.JsonObject

interface FileToolGateway {
    suspend fun tools(): Set<String>
    suspend fun call(tool: String, arguments: JsonObject): JsonObject
    fun snapshot(): SessionSummary
}

class LazyEmbeddedFileMcpGateway(
    private val config: AppConfig,
    private val workspace: ProjectWorkspace,
) : FileToolGateway, AutoCloseable {
    private var server: RunningFileMcpServer? = null
    private var clientConfig: AppConfig? = null
    private var discovered: Set<String>? = null

    override suspend fun tools(): Set<String> {
        discovered?.let { return it }
        ensureStarted()
        return FileMcpClient(requireNotNull(clientConfig)).discoverTools().also { discovered = it }
    }

    override suspend fun call(tool: String, arguments: JsonObject): JsonObject {
        require(tool in tools()) { "Tool was not discovered: $tool" }
        return FileMcpClient(requireNotNull(clientConfig)).call(tool, arguments)
    }

    override fun snapshot(): SessionSummary = workspace.snapshot()

    suspend fun endpoint(): String {
        ensureStarted()
        return requireNotNull(clientConfig).mcpUrl
    }

    override fun close() {
        server?.engine?.stop(500, 1_000)
        server = null
        clientConfig = null
        discovered = null
    }

    private suspend fun ensureStarted() {
        if (server != null) return
        val started = startFileMcpServer(config, workspace)
        server = started
        clientConfig = if (config.mcpPort == 0) config.copy(mcpPort = started.port) else config
    }
}
