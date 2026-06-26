import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val config = AppConfig.load()

    when (args.firstOrNull() ?: "list") {
        "list" -> runBlocking {
            println("Day 16: MCP connection")
            println("CONNECTING TO MCP SERVER")
            println("SERVER: ${config.serverUrl}")
            println("CLIENT: ${config.clientName}")
            println()

            try {
                val tools = McpToolLister(config).listTools()
                ToolPrinter.print(config, tools)
            } catch (e: Exception) {
                printFriendlyError(e)
            }
        }
        "raw-check" -> {
            try {
                RawJsonRpcProbe(config).run()
            } catch (e: Exception) {
                printFriendlyError(e)
            }
        }
        else -> println("Usage: list | raw-check")
    }
}

private fun printFriendlyError(e: Exception) {
    println("CHECK: connection failed")
    println("HINT: check MCP_SERVER_URL, network access, and server availability.")
    println("ERROR: ${e::class.simpleName}: ${e.message ?: "no message"}")
}
