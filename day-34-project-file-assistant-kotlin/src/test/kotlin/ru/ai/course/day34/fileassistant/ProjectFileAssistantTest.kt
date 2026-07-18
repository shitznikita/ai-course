package ru.ai.course.day34.fileassistant

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectFileAssistantTest {
    @Test
    fun `legacy fixture goal autonomously searches reads three files and creates report`() = runBlocking {
        val (root, result) = runFixture(DemoGoals.LEGACY_USAGE)

        assertEquals(0, result.llmCalls)
        assertTrue(result.trace.map(ToolObservation::tool).containsAll(FileTool.expected))
        assertEquals(3, result.session.filesRead.size)
        assertEquals(listOf("docs/legacy-payments-usage.md"), result.session.changedPaths)
        assertTrue(Files.isRegularFile(root.resolve("docs/legacy-payments-usage.md")))
        assertTrue("CheckoutServiceTest.kt" in Files.readString(root.resolve("docs/legacy-payments-usage.md")))
    }

    @Test
    fun `documentation fixture goal reads code and docs then changes only expected docs`() = runBlocking {
        val (root, result) = runFixture(DemoGoals.DOCS_SYNC)

        assertEquals(4, result.session.filesRead.size)
        assertEquals(listOf("CHANGELOG.md", "README.md", "docs/api.md"), result.session.changedPaths)
        assertTrue("refund(paymentId, reason)" in Files.readString(root.resolve("README.md")))
        assertTrue("RefundReceipt" in Files.readString(root.resolve("docs/api.md")))
        assertTrue(Files.isRegularFile(root.resolve("CHANGELOG.md")))
        assertTrue(result.trace.size <= ProjectFileAssistant.MAX_STEPS)
    }

    @Test
    fun `fixture runs reproduce normalized trace paths and diff fingerprint`() = runBlocking {
        val first = runFixture(DemoGoals.DOCS_SYNC).second
        val second = runFixture(DemoGoals.DOCS_SYNC).second

        assertEquals(
            first.trace.map { it.tool to it.observationSummary },
            second.trace.map { it.tool to it.observationSummary },
        )
        assertEquals(first.session.changedPaths, second.session.changedPaths)
        assertEquals(first.session.diffSha256, second.session.diffSha256)
    }

    @Test
    fun `loop rejects identical calls and enforces max steps`() {
        val repeated = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                ProjectFileAssistant(FakeGateway(), RepeatingPlanner()).run("Exercise repeated call guard")
            }
        }
        assertTrue("Repeated" in repeated.message.orEmpty())

        val bounded = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                ProjectFileAssistant(FakeGateway(), NeverFinishingPlanner()).run("Exercise maximum step guard")
            }
        }
        assertTrue("12-step" in bounded.message.orEmpty())
    }

    private suspend fun runFixture(goal: String): Pair<java.nio.file.Path, AgentRunResult> {
        val root = TestSupport.fixtureCopy("assistant-")
        val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.APPLY)
        val gateway = LazyEmbeddedFileMcpGateway(TestSupport.config(), workspace)
        return try {
            root to ProjectFileAssistant(gateway, FixturePlanner()).run(goal)
        } finally {
            gateway.close()
        }
    }

    private class FakeGateway : FileToolGateway {
        override suspend fun tools(): Set<String> = FileTool.expected
        override suspend fun call(tool: String, arguments: JsonObject): JsonObject {
            require(tool == FileTool.LIST_FILES)
            return AppJson.strict.encodeToJsonElement(
                FileListResult.serializer(),
                FileListResult(emptyList(), false),
            ) as JsonObject
        }

        override fun snapshot(): SessionSummary = SessionSummary(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", sha256(""),
        )
    }

    private class RepeatingPlanner : AgentPlanner {
        override val mode = "repeat-test"
        override val llmCalls = 0
        override suspend fun next(context: PlannerContext): PlanAction = PlanAction(
            type = "tool_call",
            tool = FileTool.LIST_FILES,
            arguments = JsonObject(mapOf("limit" to JsonPrimitive(1))),
        )
    }

    private class NeverFinishingPlanner : AgentPlanner {
        override val mode = "max-step-test"
        override val llmCalls = 0
        override suspend fun next(context: PlannerContext): PlanAction = PlanAction(
            type = "tool_call",
            tool = FileTool.LIST_FILES,
            arguments = JsonObject(mapOf("limit" to JsonPrimitive(context.step))),
        )
    }
}
