package ru.ai.course.day33.supportassistant

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class HttpCall(
    val method: String,
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeout: Duration,
    val maxResponseBytes: Int,
)

data class HttpResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
)

fun interface HttpTransport {
    fun execute(call: HttpCall): HttpResult
}

class JavaHttpTransport(connectTimeout: Duration) : HttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun execute(call: HttpCall): HttpResult {
        require(call.method == "POST") { "Day 33 LLM transport supports POST only." }
        require(call.maxResponseBytes > 0) { "HTTP response byte limit must be positive." }
        val builder = HttpRequest.newBuilder(call.uri)
            .timeout(call.timeout)
            .POST(HttpRequest.BodyPublishers.ofString(call.body.orEmpty()))
        call.headers.forEach(builder::header)
        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.headers().firstValueAsLong("Content-Length").ifPresent { length ->
                if (length > call.maxResponseBytes) {
                    response.body().close()
                    throw HttpResponseTooLargeException(call.maxResponseBytes)
                }
            }
            val bytes = response.body().use { input ->
                val output = ByteArrayOutputStream(minOf(call.maxResponseBytes, 16_384))
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (total + read > call.maxResponseBytes) {
                        throw HttpResponseTooLargeException(call.maxResponseBytes)
                    }
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
            HttpResult(
                status = response.statusCode(),
                headers = response.headers().map(),
                body = String(bytes, StandardCharsets.UTF_8),
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpTransportException("HTTP request was interrupted.", error)
        } catch (error: IOException) {
            throw HttpTransportException("HTTP request failed: ${error.javaClass.simpleName}.", error)
        }
    }
}

class HttpTransportException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class HttpResponseTooLargeException(maxBytes: Int) :
    IllegalStateException("HTTP response exceeded the configured $maxBytes-byte limit.")
