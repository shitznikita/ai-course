import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val config = AppConfig.load()

    when (args.firstOrNull() ?: "fixture-demo") {
        "server" -> {
            printServerBanner(config)
            startCoursePipelineMcpServer(config, wait = true)
        }
        "agent-demo" -> runBlocking {
            runEmbeddedAgentDemo(config)
        }
        "fixture-demo" -> runBlocking {
            runEmbeddedAgentDemo(
                config.copy(
                    telegramBackend = "fixture",
                    telegramChat = "fixture-course-chat",
                    courseDay = "19",
                    llmApiKey = null,
                ),
            )
        }
        "raw-check" -> runBlocking {
            runEmbeddedRawCheck(
                config.copy(
                    telegramBackend = "fixture",
                    telegramChat = "fixture-course-chat",
                    courseDay = "19",
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
    val server = startCoursePipelineMcpServer(config, wait = false)
    try {
        delay(300)
        println()
        println("AGENT CONNECTING")
        println("CLIENT: ${config.clientName}")
        val ok = CoursePipelineAgent(config).run()
        println()
        if (ok) {
            println("CHECK: MCP pipeline executed search -> summarize -> save")
        } else {
            println("CHECK: MCP pipeline attempted, but did not complete successfully")
        }
    } finally {
        server.stop(500, 1_000)
    }
}

private suspend fun runEmbeddedRawCheck(config: AppConfig) {
    printServerBanner(config)
    val server = startCoursePipelineMcpServer(config, wait = false)
    try {
        delay(300)
        val ok = RawJsonRpcProbe(config).run()
        check(ok) { "Raw MCP check failed." }
        println()
        println("CHECK: initialize, tools/list, search tools/call ok")
    } finally {
        server.stop(500, 1_000)
    }
}

private fun printServerBanner(config: AppConfig) {
    println("Day 19: Telegram MCP tool composition pipeline")
    println("MCP SERVER: ${config.serverUrl}")
    println("TELEGRAM BACKEND: ${config.telegramBackend}")
    println("TELEGRAM CHAT: ${config.telegramChat}")
    println("TELEGRAM LIMIT: ${config.telegramLimit}")
    println("COURSE DAY: ${config.courseDay}")
    println("STATE DIR: ${config.stateDir.toAbsolutePath()}")
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo  Start local MCP server with fixture chat and run search -> summarize -> save")
    println("  raw-check     Start local MCP server and show raw JSON-RPC initialize/tools/list/tools/call")
    println("  agent-demo    Start local MCP server with configured TELEGRAM_BACKEND and run pipeline agent")
    println("  auth-check    Run TDLib login diagnostics without starting MCP")
    println("  auth-resend   Ask TDLib to resend the login code when Telegram allows it")
    println("  auth-qr       Request QR login confirmation from another logged-in Telegram device")
    println("  server        Start MCP server only")
}
