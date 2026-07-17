package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportMcpIntegrationTest {
    @Test
    fun `real streamable HTTP MCP advertises exactly two tools and fetches linked user`() = runBlocking {
        val config = TestSupport.config()
        LazyEmbeddedSupportMcpGateway(config).use { gateway ->
            val fetch = gateway.fetch("TCK-1001")
            val context = requireNotNull(fetch.context)

            assertEquals(SupportTool.REQUIRED, fetch.availableTools.toSet())
            assertEquals(
                listOf(SupportTool.GET_TICKET, SupportTool.GET_USER),
                fetch.usedTools,
            )
            assertEquals(context.ticket.userId, context.user.id)
            assertEquals("127.0.0.1", config.mcpHost)
        }
    }

    @Test
    fun `missing ticket stops after ticket tool without fetching arbitrary user`() = runBlocking {
        val config = TestSupport.config()
        LazyEmbeddedSupportMcpGateway(config).use { gateway ->
            val fetch = gateway.fetch("TCK-4040")

            assertNull(fetch.context)
            assertEquals(listOf(SupportTool.GET_TICKET), fetch.usedTools)
            assertTrue(fetch.failureReason.orEmpty().contains("not found"))
        }
    }

    @Test
    fun `embedded gateway fails when configured port belongs to another listener`() = runBlocking {
        ServerSocket().use { occupied ->
            occupied.reuseAddress = false
            occupied.bind(InetSocketAddress("127.0.0.1", 0))
            val config = TestSupport.config(port = occupied.localPort)
            val gateway = LazyEmbeddedSupportMcpGateway(config)
            try {
                assertFailsWith<Exception> { gateway.fetch("TCK-1001") }
            } finally {
                gateway.close()
            }
            Unit
        }
    }
}
