package ru.ai.course.day33.supportassistant

import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class JsonSupportDataRepositoryTest {
    @Test
    fun `committed fixture is synthetic strict and referentially valid`() {
        val config = TestSupport.config()
        val repository = JsonSupportDataRepository(config.fixturePath)

        val ticket = assertNotNull(repository.ticket("TCK-1001"))
        val user = assertNotNull(repository.user(ticket.userId))

        assertEquals("USR-1001", user.id)
        assertEquals("ACCOUNT_LOCKED", ticket.errorCode)
    }

    @Test
    fun `unknown fields including PII are rejected by strict schema`() {
        val path = TestSupport.tempDirectory("schema-").resolve("support-data.json")
        Files.writeString(
            path,
            """
            {
              "synthetic": true,
              "users": [{
                "id": "USR-1001",
                "displayName": "Demo",
                "plan": "PRO",
                "accountState": "ACTIVE",
                "locale": "ru-RU",
                "email": "real@example.com"
              }],
              "tickets": []
            }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> { JsonSupportDataRepository(path) }
    }

    @Test
    fun `non-synthetic duplicate and broken references fail closed`() {
        val path = TestSupport.tempDirectory("invalid-").resolve("support-data.json")
        Files.writeString(
            path,
            """
            {
              "synthetic": false,
              "users": [{
                "id": "USR-1001",
                "displayName": "Demo",
                "plan": "PRO",
                "accountState": "ACTIVE",
                "locale": "ru-RU"
              }],
              "tickets": [{
                "id": "TCK-1001",
                "userId": "USR-9999",
                "category": "AUTHENTICATION",
                "productArea": "ACCOUNT_ACCESS",
                "status": "OPEN",
                "priority": "NORMAL",
                "errorCode": "ACCOUNT_LOCKED",
                "summary": "Synthetic.",
                "failedAuthAttempts": 1,
                "deviceClockSkewSeconds": 0
              }]
            }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> { JsonSupportDataRepository(path) }
    }

    @Test
    fun `MCP-shaped records are revalidated before context facts are built`() {
        val valid = TestSupport.context("TCK-1001")
        val oversized = valid.ticket.copy(summary = "x".repeat(801))

        assertFailsWith<IllegalArgumentException> {
            SupportDataPolicy.sanitized(oversized, valid.user)
        }
    }

    @Test
    fun `oversized fixture is rejected before JSON decoding`() {
        val path = TestSupport.tempDirectory("oversized-fixture-").resolve("support-data.json")
        Files.newByteChannel(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.position(1_000_000)
            channel.write(ByteBuffer.wrap(byteArrayOf('{'.code.toByte())))
        }

        val error = assertFailsWith<IllegalArgumentException> {
            JsonSupportDataRepository(path)
        }
        assertEquals("Support fixture exceeds 1000000 bytes.", error.message)
    }
}
