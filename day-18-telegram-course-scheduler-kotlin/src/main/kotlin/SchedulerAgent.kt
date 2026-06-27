import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.seconds

class CourseSchedulerAgent(private val config: AppConfig) {
    suspend fun runOnce(label: String = "manual run"): Boolean =
        runCatching {
            connectClient { client ->
                runOnceConnected(client, label)
            }
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                println("AGENT RUN FAILED: ${error.message ?: error::class.simpleName}")
                println("Hint: live Telegram + Eliza may need more time. Increase MCP_TIMEOUT_SECONDS if this repeats.")
                false
            },
        )

    private suspend fun runOnceConnected(client: Client, label: String) {
        println("AGENT RUN: $label")
        val tools = client.listTools().tools
        println("TOOLS RETURNED: ${tools.size}")
        tools.forEachIndexed { index, tool ->
            println("${index + 1}. ${tool.name}")
            println("   description: ${tool.description}")
        }
        println()

        val result = client.callTool(
            name = CourseSchedulerTool.GENERATE_PROMPT,
            arguments = mapOf(
                "courseDay" to config.courseDay,
                "chat" to config.telegramChat,
                "limit" to config.telegramLimit,
                "persist" to true,
            ),
        )
        val toolText = result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }

        println("TOOL RESULT")
        println(toolText)
        println()
        if (result.isError == true) {
            println("LATEST CHECK SKIPPED: generate tool returned an error")
            return
        }

        val latest = client.callTool(
            name = CourseSchedulerTool.GET_LATEST_PROMPT,
            arguments = emptyMap<String, Any>(),
        )
        val latestText = latest.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
        println("LATEST CHECK")
        println(latestText.lineSequence().take(8).joinToString("\n"))
    }

    suspend fun runIntervalScheduler() {
        println("SCHEDULER DEMO")
        println("interval seconds: ${config.scheduleIntervalSeconds}")
        println("runs: ${config.schedulerRuns}")
        for (run in 1..config.schedulerRuns) {
            val ok = runOnce("interval run $run/${config.schedulerRuns}")
            if (!ok) {
                println("Interval run $run/${config.schedulerRuns} failed; scheduler remains alive for the next run.")
            }
            if (run < config.schedulerRuns) {
                println()
                println("Next interval run in ${config.scheduleIntervalSeconds}s")
                delay(config.scheduleIntervalSeconds.seconds)
                println()
            }
        }
        println("SCHEDULER DEMO COMPLETE")
    }

    suspend fun runDailyScheduler() {
        println("DAILY SCHEDULER")
        println("time: ${config.scheduleTime}")
        println("zone: ${config.scheduleZone}")
        while (true) {
            val delayMillis = millisUntilNextRun()
            val nextAt = ZonedDateTime.now(config.scheduleZone).plus(Duration.ofMillis(delayMillis))
            println("Next run at $nextAt")
            delay(delayMillis)
            val ok = runOnce("daily scheduled run at ${ZonedDateTime.now(config.scheduleZone)}")
            if (!ok) {
                println("Daily run failed; scheduler remains alive for the next scheduled run.")
            }
        }
    }

    private fun millisUntilNextRun(): Long {
        val now = ZonedDateTime.now(config.scheduleZone)
        var next = now.toLocalDate().atTime(config.scheduleTime).atZone(config.scheduleZone)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        return Duration.between(now, next).toMillis().coerceAtLeast(1)
    }

    private suspend fun connectClient(block: suspend (Client) -> Unit) {
        HttpClient(CIO) {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                connectTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
                socketTimeoutMillis = config.timeoutSeconds.seconds.inWholeMilliseconds
            }
        }.use { httpClient ->
            val client = Client(
                clientInfo = Implementation(
                    name = config.clientName,
                    version = "1.0.0",
                ),
            )
            val transport = StreamableHttpClientTransport(
                client = httpClient,
                url = config.serverUrl,
            )

            client.connect(transport)
            block(client)
        }
    }
}
