package ru.ai.course.day34.fileassistant

import java.net.URI
import java.time.Duration
import java.util.UUID

data class AppConfig(
    val mcpHost: String = env("FILE_ASSISTANT_MCP_HOST") ?: "127.0.0.1",
    val mcpPort: Int = env("FILE_ASSISTANT_MCP_PORT")?.toIntOrNull() ?: 3034,
    val mcpTimeout: Duration = Duration.ofSeconds(env("FILE_ASSISTANT_MCP_TIMEOUT_SECONDS")?.toLongOrNull() ?: 15),
    val mcpSessionToken: String = UUID.randomUUID().toString(),
    val ollamaUrl: String = env("OLLAMA_CHAT_URL") ?: "http://127.0.0.1:11434/api/chat",
    val ollamaModel: String = env("OLLAMA_MODEL") ?: "qwen3:4b",
    val ollamaTimeout: Duration = Duration.ofSeconds(env("OLLAMA_TIMEOUT_SECONDS")?.toLongOrNull() ?: 90),
) {
    val mcpUrl: String = "http://$mcpHost:$mcpPort/mcp"

    init {
        require(mcpHost == LOOPBACK_IPV4) {
            "FILE_ASSISTANT_MCP_HOST must be the literal loopback address $LOOPBACK_IPV4."
        }
        require(mcpPort in 0..65535) { "FILE_ASSISTANT_MCP_PORT must be between 0 and 65535." }
        require(mcpTimeout in Duration.ofSeconds(1)..Duration.ofMinutes(2)) { "Invalid MCP timeout." }
        require(mcpSessionToken.length >= 32) { "MCP session token is too short." }
        require(ollamaTimeout in Duration.ofSeconds(1)..Duration.ofMinutes(10)) { "Invalid Ollama timeout." }
        requireLoopbackHttp(ollamaUrl)
    }

    companion object {
        private const val LOOPBACK_IPV4 = "127.0.0.1"

        private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)

        private fun requireLoopbackHttp(value: String) {
            val uri = runCatching { URI.create(value) }.getOrElse {
                throw IllegalArgumentException("OLLAMA_CHAT_URL is not a valid URI.")
            }
            require(uri.scheme == "http") { "OLLAMA_CHAT_URL must use http on loopback." }
            require(uri.host == LOOPBACK_IPV4) {
                "OLLAMA_CHAT_URL must use the literal loopback address $LOOPBACK_IPV4."
            }
            require(uri.path == "/api/chat") { "OLLAMA_CHAT_URL must target /api/chat." }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "OLLAMA_CHAT_URL must not contain credentials, query, or fragment."
            }
        }
    }
}
