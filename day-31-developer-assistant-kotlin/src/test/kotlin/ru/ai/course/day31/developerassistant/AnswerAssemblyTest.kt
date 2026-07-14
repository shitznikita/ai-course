package ru.ai.course.day31.developerassistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerAssemblyTest {
    private val branchContradictions = listOf(
        "The branch is main.",
        "We are on branch main.",
        "Мы на ветке main.",
        "Мы сейчас работаем в main.",
    )
    private val fileContradictions = listOf(
        "Tracked files: secrets.toml",
        "The repository contains secrets.toml.",
        "secrets.toml отслеживается Git.",
        "В проекте есть secrets.toml.",
    )
    private val evidence = evidence()

    @Test
    fun `branch-only assembly ignores every arbitrary generated paraphrase`() {
        val requirements = GroundingRequirements(false, true, false, fetchFiles = false)
        val mcp = mcp(branch = "AICOURSE-1")

        branchContradictions.forEach { prose ->
            val answer = AnswerAssembler.assemble(
                GeneratedDocumentationAnswer("answered", prose, emptyList()),
                emptyEvidence(),
                requirements,
                mcp,
            )

            assertEquals("AICOURSE-1", answer.projectBranch)
            assertEquals(emptyList(), answer.projectFiles)
            assertEquals(AnswerAssembler.MCP_ONLY_MESSAGE, answer.answer)
            assertFalse("main" in answer.answer, prose)
            assertTrue(GroundingValidator().validateFinal(answer, mcp, requirements).valid)
        }
    }

    @Test
    fun `file-only assembly ignores every arbitrary generated paraphrase`() {
        val requirements = GroundingRequirements(false, false, true, fetchFiles = true)
        val mcp = mcp(files = listOf("README.md"))

        fileContradictions.forEach { prose ->
            val answer = AnswerAssembler.assemble(
                GeneratedDocumentationAnswer("answered", prose, emptyList()),
                emptyEvidence(),
                requirements,
                mcp,
            )

            assertEquals(null, answer.projectBranch)
            assertEquals(listOf("README.md"), answer.projectFiles)
            assertEquals(AnswerAssembler.MCP_ONLY_MESSAGE, answer.answer)
            assertFalse("secrets.toml" in answer.answer, prose)
            assertTrue(GroundingValidator().validateFinal(answer, mcp, requirements).valid)
        }
    }

    @Test
    fun `mixed assembly never publishes contradictory model prose`() {
        val requirements = GroundingRequirements(true, true, true, fetchFiles = true)
        val mcp = mcp(branch = "AICOURSE-1", files = listOf("README.md"))
        val validator = GroundingValidator()

        (branchContradictions + fileContradictions).forEach { prose ->
            val generated = GeneratedDocumentationAnswer(
                status = "answered",
                answer = prose,
                sourceIds = listOf(evidence.items.single().sourceId),
            )
            assertFalse(
                validator.validateGenerated(generated, evidence, documentationRequired = true).valid,
                prose,
            )

            val answer = AnswerAssembler.assemble(generated, evidence, requirements, mcp)
            assertEquals("AICOURSE-1", answer.projectBranch)
            assertEquals(listOf("README.md"), answer.projectFiles)
            assertFalse(prose in answer.answer, prose)
            assertFalse("secrets.toml" in answer.answer, prose)
            assertFalse("branch is main" in answer.answer.lowercase(), prose)
            assertTrue(validator.validateFinal(answer, mcp, requirements).valid)
        }
    }

    private fun evidence(): EvidencePack {
        val text = "Project modules are listed in settings.gradle.kts and use Kotlin JVM 21."
        val chunk = IndexedChunk(
            metadata = ChunkMetadata(
                source = "README.md",
                section = "Структура",
                chunkId = "src-readme-structure-1234567890ab",
                contentSha256 = TextTools.sha256(text),
                ordinal = 0,
                approxTokens = TextTools.approxTokens(text),
            ),
            text = text,
            embedding = listOf(1.0, 0.0),
        )
        return EvidencePackBuilder().build(
            RetrievalResult(
                question = "structure",
                hits = listOf(RetrievalHit(0.8, 0.8, 0.8, 0.0, chunk)),
                lowConfidence = false,
            ),
            maxTokens = 300,
        )
    }

    private fun emptyEvidence(): EvidencePack =
        EvidencePackBuilder().build(
            RetrievalResult("mcp", emptyList(), lowConfidence = false),
            maxTokens = 0,
        )

    private fun mcp(
        branch: String = "AICOURSE-1",
        files: List<String> = emptyList(),
    ): McpEvidence = McpEvidenceBuilder().build(
        McpProjectContext(
            availableTools = listOf(ProjectTool.CURRENT_BRANCH, ProjectTool.LIST_FILES),
            branch = GitBranchInfo(branch, detached = false),
            files = files.takeIf(List<String>::isNotEmpty)?.let { GitFileList(files = it, truncated = false) },
            usedTools = buildList {
                add(ProjectTool.CURRENT_BRANCH)
                if (files.isNotEmpty()) add(ProjectTool.LIST_FILES)
            },
        ),
        fileByteBudget = 10_000,
    )
}
