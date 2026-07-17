package ru.ai.course.day32.codereview

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HttpTransportTest {
    @Test
    fun `java transport stops reading responses at configured byte limit`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "x".repeat(20_000).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            assertFailsWith<HttpResponseTooLargeException> {
                JavaHttpTransport().execute(
                    HttpCall(
                        method = "GET",
                        uri = URI.create("http://127.0.0.1:${server.address.port}/"),
                        maxResponseBytes = 1_024,
                    ),
                )
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `java transport also caps chunked responses without content length`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "y".repeat(20_000).toByteArray()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            assertFailsWith<HttpResponseTooLargeException> {
                JavaHttpTransport().execute(
                    HttpCall(
                        method = "GET",
                        uri = URI.create("http://127.0.0.1:${server.address.port}/"),
                        maxResponseBytes = 1_024,
                    ),
                )
            }
        } finally {
            server.stop(0)
        }
    }
}
