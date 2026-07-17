package ru.ai.course.day33.supportassistant

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

object TestSupport {
    fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: error("Cannot locate repository root.")
        }
        error("Cannot locate repository root.")
    }

    fun tempDirectory(prefix: String): Path {
        val parent = repositoryRoot()
            .resolve("day-33-support-assistant-kotlin/build/test-runtime")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, prefix)
    }

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    fun config(
        temp: Path = tempDirectory("config-"),
        port: Int = freePort(),
        values: Map<String, String> = emptyMap(),
    ): AppConfig = AppConfig.fromValues(
        mapOf(
            "REPOSITORY_ROOT" to repositoryRoot().toString(),
            "SUPPORT_RAG_INDEX_PATH" to temp.resolve("rag-index.json").toString(),
            "MCP_PORT" to port.toString(),
            "LLM_API_KEY" to "unit-key",
        ) + values,
    )

    fun context(ticketId: String): SupportContext {
        val config = config()
        val repository = JsonSupportDataRepository(config.fixturePath)
        val ticket = requireNotNull(repository.ticket(ticketId))
        val user = requireNotNull(repository.user(ticket.userId))
        return SupportDataPolicy.sanitized(ticket, user)
    }

    fun gateway(context: SupportContext): SupportContextGateway = SupportContextGateway {
        McpContextFetch(
            availableTools = SupportTool.REQUIRED.sorted(),
            usedTools = listOf(SupportTool.GET_TICKET, SupportTool.GET_USER),
            context = context,
            failureReason = null,
        )
    }

    suspend fun preparation(
        ticketId: String = "TCK-1001",
        question: String = "Почему не работает авторизация?",
    ): Pair<AppConfig, SupportPreparation> {
        val config = config()
        val context = context(ticketId)
        val assistant = SupportAssistant(config, gateway(context))
        return config to assistant.prepare(ticketId, question)
    }
}
