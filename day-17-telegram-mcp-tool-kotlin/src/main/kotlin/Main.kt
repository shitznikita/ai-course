import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val config = AppConfig.load()

    when (args.firstOrNull() ?: "fixture-demo") {
        "server" -> {
            printServerBanner(config)
            startTelegramMcpServer(config, wait = true)
        }
        "agent-demo" -> runBlocking {
            runEmbeddedAgentDemo(config)
        }
        "fixture-demo" -> runBlocking {
            runEmbeddedAgentDemo(config.copy(telegramBackend = "fixture"))
        }
        "raw-check" -> runBlocking {
            runEmbeddedRawCheck(config.copy(telegramBackend = "fixture", telegramLimit = 3))
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
    val server = startTelegramMcpServer(config, wait = false)
    try {
        delay(300)
        println()
        println("AGENT CONNECTING")
        println("CLIENT: ${config.clientName}")
        TelegramMcpAgent(config).run()
        println()
        println("CHECK: MCP tool call ok, result used by agent")
    } finally {
        server.stop(500, 1_000)
    }
}

private suspend fun runEmbeddedRawCheck(config: AppConfig) {
    printServerBanner(config)
    val server = startTelegramMcpServer(config, wait = false)
    try {
        delay(300)
        RawJsonRpcProbe(config).run()
        println()
        println("CHECK: initialize, tools/list, tools/call attempted")
    } finally {
        server.stop(500, 1_000)
    }
}

private fun printServerBanner(config: AppConfig) {
    println("Day 17: Telegram MCP tool")
    println("MCP SERVER: ${config.serverUrl}")
    println("TELEGRAM BACKEND: ${config.telegramBackend}")
    println("TELEGRAM CHAT: ${config.telegramChat}")
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo  Start local MCP server with fixture Telegram data and run agent demo")
    println("  raw-check     Start local MCP server and show raw JSON-RPC initialize/tools/list/tools/call")
    println("  agent-demo    Start local MCP server with configured TELEGRAM_BACKEND and run agent demo")
    println("  auth-check    Run TDLib login diagnostics without starting MCP")
    println("  auth-resend   Ask TDLib to resend the login code when Telegram allows it")
    println("  auth-qr       Request QR login confirmation from another logged-in Telegram device")
    println("  server        Start MCP server only")
}
