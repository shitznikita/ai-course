import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val config = AppConfig.load()

    when (args.firstOrNull() ?: "fixture-demo") {
        "server" -> {
            printServerBanner(config)
            startCourseSchedulerMcpServer(config, wait = true)
        }
        "once" -> runBlocking {
            runEmbeddedOnce(config)
        }
        "fixture-demo" -> runBlocking {
            runEmbeddedOnce(
                config.copy(
                    telegramBackend = "fixture",
                    telegramChat = "fixture-course-chat",
                    courseDay = "18",
                    llmApiKey = null,
                ),
            )
        }
        "scheduler-demo" -> runBlocking {
            runEmbeddedIntervalScheduler(config)
        }
        "scheduler" -> runBlocking {
            runEmbeddedDailyScheduler(config)
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

private suspend fun runEmbeddedOnce(config: AppConfig) {
    printServerBanner(config)
    val server = startCourseSchedulerMcpServer(config, wait = false)
    try {
        delay(300)
        println()
        println("AGENT CONNECTING")
        println("CLIENT: ${config.clientName}")
        CourseSchedulerAgent(config).runOnce()
        println()
        println("CHECK: scheduled MCP tool call ok, prompt saved and returned")
    } finally {
        server.stop(500, 1_000)
    }
}

private suspend fun runEmbeddedIntervalScheduler(config: AppConfig) {
    printServerBanner(config)
    val server = startCourseSchedulerMcpServer(config, wait = false)
    try {
        delay(300)
        println()
        println("AGENT CONNECTING")
        println("CLIENT: ${config.clientName}")
        CourseSchedulerAgent(config).runIntervalScheduler()
        println()
        println("CHECK: interval scheduler triggered MCP tool calls")
    } finally {
        server.stop(500, 1_000)
    }
}

private suspend fun runEmbeddedDailyScheduler(config: AppConfig) {
    printServerBanner(config)
    val server = startCourseSchedulerMcpServer(config, wait = false)
    try {
        delay(300)
        println()
        println("AGENT CONNECTING")
        println("CLIENT: ${config.clientName}")
        CourseSchedulerAgent(config).runDailyScheduler()
    } finally {
        server.stop(500, 1_000)
    }
}

private fun printServerBanner(config: AppConfig) {
    println("Day 18: Telegram course scheduler MCP")
    println("MCP SERVER: ${config.serverUrl}")
    println("TELEGRAM BACKEND: ${config.telegramBackend}")
    println("TELEGRAM CHAT: ${config.telegramChat}")
    println("TELEGRAM LIMIT: ${config.telegramLimit}")
    println("COURSE DAY: ${config.courseDay}")
    println("STATE DIR: ${config.stateDir.toAbsolutePath()}")
}

private fun printUsage() {
    println("Usage:")
    println("  fixture-demo    Start local MCP server with fixture chat and generate Day 18 prompt")
    println("  once            Start local MCP server with configured backend and run one scheduled job now")
    println("  scheduler-demo  Run finite interval scheduler for video/smoke checks")
    println("  scheduler       Run daily scheduler at SCHEDULE_TIME/SCHEDULE_ZONE")
    println("  auth-check      Run TDLib login diagnostics without starting MCP")
    println("  auth-resend     Ask TDLib to resend the login code when Telegram allows it")
    println("  auth-qr         Request QR login confirmation from another logged-in Telegram device")
    println("  server          Start MCP server only")
}
