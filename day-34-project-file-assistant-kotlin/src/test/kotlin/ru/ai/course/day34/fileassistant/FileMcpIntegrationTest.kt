package ru.ai.course.day34.fileassistant

import java.nio.file.Files
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileMcpIntegrationTest {
    @Test
    fun `streamable HTTP MCP advertises exact tools and executes a safe preview session`() = runBlocking {
        val root = TestSupport.fixtureCopy("mcp-")
        val original = Files.readString(root.resolve("README.md"))
        val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.PREVIEW)
        val config = TestSupport.config()
        val gateway = LazyEmbeddedFileMcpGateway(config, workspace)
        try {
            assertEquals(FileTool.expected, gateway.tools())
            val unauthorized = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(gateway.endpoint())).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauthorized.statusCode())
            val listed = decode<FileListResult>(
                gateway.call(FileTool.LIST_FILES, obj("limit" to JsonPrimitive(30))),
            )
            assertTrue("README.md" in listed.files)
            val searched = decode<SearchResult>(
                gateway.call(
                    FileTool.SEARCH_TEXT,
                    obj("query" to JsonPrimitive("refund("), "limit" to JsonPrimitive(20)),
                ),
            )
            assertTrue(searched.hits.any { it.path.endsWith("PaymentClient.kt") })
            val read = decode<ReadFilesResult>(
                gateway.call(
                    FileTool.READ_FILES,
                    obj("paths" to JsonArray(listOf(JsonPrimitive("README.md")))),
                ),
            ).files.single()
            val write = decode<WriteFileResult>(
                gateway.call(
                    FileTool.WRITE_FILE,
                    obj(
                        "path" to JsonPrimitive("README.md"),
                        "content" to JsonPrimitive(original + "\nMCP preview.\n"),
                        "expectedSha256" to JsonPrimitive(read.sha256),
                    ),
                ),
            )
            val diff = decode<DiffResult>(gateway.call(FileTool.UNIFIED_DIFF, obj()))

            assertTrue(write.changed)
            assertEquals(listOf("README.md"), diff.changedPaths)
            assertFalse("MCP preview." in Files.readString(root.resolve("README.md")))
            assertEquals(listOf("README.md"), gateway.snapshot().filesWritten)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `consecutive embedded server lifecycles bind ephemeral ports without collision`() = runBlocking {
        val endpoints = mutableListOf<String>()

        repeat(8) { index ->
            val root = TestSupport.fixtureCopy("mcp-lifecycle-$index-")
            val gateway = LazyEmbeddedFileMcpGateway(
                TestSupport.config(port = 0),
                ProjectWorkspace(ProjectFilePolicy(root), WriteMode.PREVIEW),
            )
            try {
                assertEquals(FileTool.expected, gateway.tools())
                endpoints += gateway.endpoint()
            } finally {
                gateway.close()
            }
        }

        assertEquals(8, endpoints.size)
        assertTrue(endpoints.all { !it.contains(":0/mcp") })
    }

    private inline fun <reified T> decode(value: JsonObject): T =
        AppJson.strict.decodeFromJsonElement(value)

    private fun obj(vararg values: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(values.toMap())
}
