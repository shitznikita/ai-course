import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class RawJsonRpcProbe(private val config: AppConfig) {
    fun run(): Boolean {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .build()

        println("RAW JSON-RPC CHECK")
        var ok = true
        val sessions = mutableMapOf<String, String?>()

        config.endpoints.forEachIndexed { index, endpoint ->
            println()
            println("SERVER ${index + 1}: ${endpoint.displayName}")
            println("URL: ${endpoint.url}")

            val initialize = send(client, endpoint.url, initializeBody(endpoint), null)
            printResponse("${endpoint.displayName} initialize", initialize)
            ok = ok && initialize.statusCode() == 200
            val sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElse(null)
            sessions[endpoint.id] = sessionId

            val initialized = send(client, endpoint.url, initializedBody(), sessionId)
            printResponse("${endpoint.displayName} notifications/initialized", initialized)

            val toolsList = send(client, endpoint.url, toolsListBody(), sessionId)
            printResponse("${endpoint.displayName} tools/list", toolsList)
            ok = ok && toolsList.statusCode() == 200
        }

        val source = config.endpoint("source")
        val toolCall = send(client, source.url, sourceToolCallBody(), sessions[source.id])
        printResponse("${source.displayName} tools/call ${OrchestrationTool.READ_MESSAGES}", toolCall)
        ok = ok && toolCall.statusCode() == 200 && !toolCall.body().contains("\"isError\":true")

        return ok
    }

    private fun send(client: HttpClient, url: String, body: String, sessionId: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!sessionId.isNullOrBlank()) {
            builder.header("Mcp-Session-Id", sessionId)
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun initializeBody(endpoint: McpEndpointConfig): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", JsonPrimitive(1))
        put("method", JsonPrimitive("initialize"))
        put(
            "params",
            buildJsonObject {
                put("protocolVersion", JsonPrimitive("2025-06-18"))
                put("capabilities", JsonObject(emptyMap()))
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", JsonPrimitive("${config.clientName}-raw-${endpoint.id}"))
                        put("version", JsonPrimitive("1.0.0"))
                    },
                )
            },
        )
    }.toString()

    private fun initializedBody(): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("method", JsonPrimitive("notifications/initialized"))
    }.toString()

    private fun toolsListBody(): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", JsonPrimitive(2))
        put("method", JsonPrimitive("tools/list"))
        put("params", JsonObject(emptyMap()))
    }.toString()

    private fun sourceToolCallBody(): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", JsonPrimitive(3))
        put("method", JsonPrimitive("tools/call"))
        put(
            "params",
            buildJsonObject {
                put("name", JsonPrimitive(OrchestrationTool.READ_MESSAGES))
                put(
                    "arguments",
                    buildJsonObject {
                        put("chat", JsonPrimitive(config.telegramChat))
                        put("limit", JsonPrimitive(config.telegramLimit.coerceIn(1, 10)))
                    },
                )
            },
        )
    }.toString()

    private fun printResponse(label: String, response: HttpResponse<String>) {
        println()
        println("=== $label ===")
        println("HTTP ${response.statusCode()}")
        response.headers().firstValue("Mcp-Session-Id").ifPresent { println("Mcp-Session-Id: $it") }
        if (response.body().isBlank()) return

        val dataLines = response.body()
            .lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .toList()

        if (dataLines.isEmpty()) {
            println(response.body().take(1600))
            return
        }

        dataLines.forEach { line ->
            val element = PipelineJson.compact.parseToJsonElement(line).jsonObject
            println(PipelineJson.prettyString(element).take(2200))
        }
    }
}
