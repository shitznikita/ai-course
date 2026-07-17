package ru.ai.course.day31.developerassistant

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleRendererTest {
    @Test
    fun `project state is rendered once and only inside typed MCP section`() {
        val evidence = EvidencePackBuilder().build(
            RetrievalResult("project state", emptyList(), lowConfidence = false),
            maxTokens = 0,
        )
        val mcp = McpEvidenceBuilder().build(
            McpProjectContext(
                availableTools = listOf(ProjectTool.CURRENT_BRANCH, ProjectTool.LIST_FILES),
                branch = GitBranchInfo("branch-render-sentinel", detached = false),
                files = GitFileList(files = listOf("file-render-sentinel"), truncated = false),
                usedTools = listOf(ProjectTool.CURRENT_BRANCH, ProjectTool.LIST_FILES),
            ),
            fileByteBudget = 1_000,
        )
        val requirements = GroundingRequirements(false, true, true, fetchFiles = true)
        val answer = AnswerAssembler.assemble(
            AnswerAssembler.mcpOnlyDocumentationAnswer(),
            evidence,
            requirements,
            mcp,
        )
        val run = AssistantRun(
            question = "Какая ветка и какие tracked files?",
            retrieval = RetrievalResult("project state", emptyList(), lowConfidence = false),
            evidence = evidence,
            mcp = mcp,
            requirements = requirements,
            answer = answer,
            validation = GroundingValidator().validateFinal(answer, mcp, requirements),
            prompt = PromptPack("", "", "(not built)", 0, 7552, 0, 7552),
            fixture = true,
        )

        val output = captureOutput { ConsoleRenderer.printRun(run) }

        assertEquals(1, Regex("branch-render-sentinel").findAll(output).count())
        assertEquals(1, Regex("file-render-sentinel").findAll(output).count())
        assertFalse("CURRENT BRANCH:" in output)
        assertTrue(output.indexOf("TYPED MCP CLAIMS:") < output.indexOf("PROJECT BRANCH: branch-render-sentinel"))
        assertTrue(output.indexOf("PROJECT FILES: 1") < output.indexOf("- file-render-sentinel"))
        assertTrue(output.indexOf("- file-render-sentinel") < output.indexOf("ANSWER:"))
    }

    @Test
    fun `direct MCP rendering uses the same typed section`() {
        val context = McpProjectContext(
            availableTools = listOf(ProjectTool.CURRENT_BRANCH, ProjectTool.LIST_FILES),
            branch = GitBranchInfo("direct-branch-sentinel", detached = false),
            files = GitFileList(files = listOf("direct-file-sentinel"), truncated = false),
            usedTools = listOf(ProjectTool.CURRENT_BRANCH, ProjectTool.LIST_FILES),
        )

        val output = captureOutput { ConsoleRenderer.printMcp(context) }

        assertEquals(1, Regex("direct-branch-sentinel").findAll(output).count())
        assertEquals(1, Regex("direct-file-sentinel").findAll(output).count())
        assertFalse("CURRENT BRANCH:" in output)
        assertFalse("TRACKED FILES:" in output)
        assertTrue(output.indexOf("TYPED MCP CLAIMS:") < output.indexOf("PROJECT BRANCH: direct-branch-sentinel"))
    }

    private fun captureOutput(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
