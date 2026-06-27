import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val config = AppConfig.load()

    when (args.firstOrNull() ?: "fixture-demo") {
        "server" -> {
            printServerBanner(config)
            val servers = startOrchestrationMcpServers(config)
            Runtime.getRuntime().addShutdownHook(Thread { stopOrchestrationMcpServers(servers) })
            Thread.currentThread().join()
        }
        "agent-demo" -> runBlocking {
            runEmbeddedAgentDemo(config)
        }
        "fixture-demo" -> runBlocking {
            runEmbeddedAgentDemo(
                config.copy(
                    telegramBackend = "fixture",
                    telegramChat = "fixture-course-chat",
                    courseDay = "20",
                    llmApiKey = null,
                ),
            )
        }
        "raw-check" -> runBlocking {
            runEmbeddedRawCheck(
                config.copy(
                    telegramBackend = "fixture",
                    telegramChat = "fixture-course-chat",
                    courseDay = "20",
                    telegramLimit = 10,
                    llmApiKey = null,
                ),
            )
        }
        "auth-check" -> {
            println(TdlibAuthInspector(config).inspect(resendCode = false))
        }
        "auth-resend" -> {
            println(TdlibAuthInspector(config).inspect(resendCode = true))
        }
        "auth-qr" -> {
            TdlibAuthInspector(config).inspectLive(resendCode = false, requestQr = true)
        }
        else -> printUsage()
    }
}

private suspend fun runEmbeddedAgentDemo(config: AppConfig) {
    printServerBanner(config)
    val servers = startOrchestrationMcpServers(config)
    try {
        delay(350)
        println()
        println("ORCHESTRATION AGENT CONNECTING")
        println("CLIENT: ${config.clientName}")
        val ok = OrchestrationAgent(config).run()
        println()
        if (ok) {
            println("CHECK: multi-server MCP orchestration executed source -> window -> chunks -> brief -> prompt -> save -> read")
        } else {
            println("CHECK: multi-server MCP orchestration attempted, but did not complete successfully")
        }
    } finally {
        stopOrchestrationMcpServers(servers)
    }
}

private suspend fun runEmbeddedRawCheck(config: AppConfig) {
    printServerBanner(config)
    val servers = startOrchestrationMcpServers(config)
    try {
        delay(350)
        val ok = RawJsonRpcProbe(config).run()
        check(ok) { "Raw MCP check failed." }
        println()
        println("CHECK: initialize and tools/list ok for all MCP servers; source tools/call ok")
    } finally {
        stopOrchestrationMcpServers(servers)
    }
}

private fun printServerBanner(config: AppConfig) {
    println("Day 20: Multi-server MCP orchestration")
    config.endpoints.forEach { endpoint ->
        println("${endpoint.displayName.uppercase()}: ${endpoint.url}")
    }
    println("TELEGRAM BACKEND: ${config.telegramBackend}")
    println("TELEGRAM CHAT: ${config.telegramChat}")
    println("TELEGRAM LIMIT: ${config.telegramLimit}")
    println("COURSE DAY: ${config.courseDay}")
    println("CHUNK MESSAGES: ${config.chunkMessagesPerChunk}")
    println("STATE DIR: ${config.stateDir.toAbsolutePath()}")
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo  Start 4 local MCP servers and run a 7-tool orchestration flow on fixture data")
    println("  raw-check     Start 4 local MCP servers and show raw JSON-RPC initialize/tools/list plus one safe source tool call")
    println("  agent-demo    Start 4 local MCP servers with configured TELEGRAM_BACKEND and run orchestration agent")
    println("  auth-check    Run TDLib login diagnostics without starting MCP")
    println("  auth-resend   Ask TDLib to resend the login code when Telegram allows it")
    println("  auth-qr       Request QR login confirmation from another logged-in Telegram device")
    println("  server        Start MCP servers only")
}
