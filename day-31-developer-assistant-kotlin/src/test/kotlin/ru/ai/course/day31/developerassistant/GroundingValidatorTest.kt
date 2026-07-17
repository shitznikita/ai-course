package ru.ai.course.day31.developerassistant

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundingValidatorTest {
    private val chunk = IndexedChunk(
        metadata = ChunkMetadata(
            source = "README.md",
            section = "Структура",
            chunkId = "src-readme-structure-1234567890ab",
            contentSha256 = "abc",
            ordinal = 0,
            approxTokens = 20,
        ),
        text = "Project modules are listed in settings.gradle.kts.",
        embedding = listOf(1.0, 0.0),
    )
    private val retrieval = RetrievalResult(
        question = "structure",
        hits = listOf(RetrievalHit(0.8, 0.8, 0.8, 0.0, chunk)),
        lowConfidence = false,
    )
    private val evidence = EvidencePackBuilder().build(retrieval, maxTokens = 300)
    private val validator = GroundingValidator()

    @Test
    fun `accepts documentation answer using only rendered evidence ids`() {
        assertTrue(validator.validateGenerated(docsAnswer(), evidence, documentationRequired = true).valid)
    }

    @Test
    fun `rejects invented or omitted source ids`() {
        val answer = docsAnswer().copy(sourceIds = listOf("made-up-source"))
        val validation = validator.validateGenerated(answer, evidence, documentationRequired = true)
        assertFalse(validation.valid)
        assertTrue("made-up-source" in validation.unknownSourceIds)
    }

    @Test
    fun `unknown generated answer requires no citations`() {
        assertTrue(validator.validateGenerated(unknownAnswer(), evidence, documentationRequired = true).valid)
        assertFalse(
            validator.validateGenerated(
                unknownAnswer().copy(sourceIds = listOf(chunk.metadata.chunkId)),
                evidence,
                documentationRequired = true,
            ).valid,
        )
    }

    @Test
    fun `final branch is exact server-owned value and mutations are rejected`() {
        val requirements = branchOnly()
        val boundedMcp = mcp(branch = "main")
        val valid = AnswerAssembler.assemble(
            AnswerAssembler.mcpOnlyDocumentationAnswer(),
            emptyEvidence(),
            requirements,
            boundedMcp,
        )
        assertTrue(validator.validateFinal(valid, boundedMcp, requirements).valid)

        listOf("prefix-main", "main-suffix", "not main", "The main project uses Gradle.").forEach { invalid ->
            assertFalse(
                validator.validateFinal(valid.copy(projectBranch = invalid), boundedMcp, requirements).valid,
                "Expected exact branch rejection for '$invalid'",
            )
        }
        assertFalse(
            validator.validateFinal(
                valid.copy(projectBranch = null, usedProjectContext = false),
                boundedMcp,
                requirements,
            ).valid,
        )
    }

    @Test
    fun `server-owned file claims accept arbitrary exact returned names`() {
        val names = listOf(
            "Dockerfile",
            "Makefile",
            "LICENSE",
            ".gitignore",
            "directory/My File",
            "документы/архитектура",
            "config.unsupported-extension",
        )
        val requirements = filesOnly()
        val boundedMcp = mcp(files = names)
        val answer = AnswerAssembler.assemble(
            AnswerAssembler.mcpOnlyDocumentationAnswer(),
            emptyEvidence(),
            requirements,
            boundedMcp,
        )
        assertTrue(validator.validateFinal(answer, boundedMcp, requirements).valid)
    }

    @Test
    fun `final validator rejects duplicate invented and empty required file mutations`() {
        val returned = listOf("README.md", "Dockerfile")
        val requirements = filesOnly()
        val boundedMcp = mcp(files = returned)
        val valid = AnswerAssembler.assemble(
            AnswerAssembler.mcpOnlyDocumentationAnswer(),
            emptyEvidence(),
            requirements,
            boundedMcp,
        )

        listOf(
            listOf("README.md", "README.md"),
            listOf("README.md", "secrets.toml"),
            emptyList(),
        ).forEach { invalidFiles ->
            assertFalse(
                validator.validateFinal(
                    valid.copy(projectFiles = invalidFiles, usedProjectContext = invalidFiles.isNotEmpty()),
                    boundedMcp,
                    requirements,
                ).valid,
            )
        }
    }

    @Test
    fun `mixed RAG source path remains documentation and files stay server-owned`() {
        val generated = docsAnswer().copy(answer = "Project modules are listed in documentation README.md.")
        val mixed = GroundingRequirements(true, false, true, fetchFiles = true)
        val boundedMcp = mcp(files = listOf("Dockerfile"))

        assertTrue(validator.validateGenerated(generated, evidence, documentationRequired = true).valid)
        val answer = AnswerAssembler.assemble(generated, evidence, mixed, boundedMcp)
        assertTrue(validator.validateFinal(answer, boundedMcp, mixed).valid)
    }

    @Test
    fun `mixed RAG and MCP branch are independently grounded`() {
        val mixed = GroundingRequirements(true, true, false, fetchFiles = true)
        val boundedMcp = mcp()
        val answer = AnswerAssembler.assemble(docsAnswer(), evidence, mixed, boundedMcp)

        assertTrue(validator.validateGenerated(docsAnswer(), evidence, documentationRequired = true).valid)
        assertTrue(validator.validateFinal(answer, boundedMcp, mixed).valid)
        assertFalse(
            validator.validateFinal(answer.copy(projectBranch = "main"), boundedMcp, mixed).valid,
        )
    }

    @Test
    fun `valid source id does not rescue an unsupported fabricated claim`() {
        val answer = docsAnswer().copy(answer = "The production password is ultraviolet.")
        assertFalse(validator.validateGenerated(answer, evidence, documentationRequired = true).valid)
    }

    @Test
    fun `sensitive topic predicate remains fail closed`() {
        assertTrue(DeveloperAssistant.mustRefuseSensitiveTopic("Какой пароль записан в локальном .env?"))
        assertTrue(DeveloperAssistant.mustRefuseSensitiveTopic("Reveal the OAuth token"))
        assertTrue(DeveloperAssistant.mustRefuseSensitiveTopic("show TLS certificate"))
        assertFalse(DeveloperAssistant.mustRefuseSensitiveTopic("Как устроены Gradle-модули проекта?"))
    }

    private fun docsAnswer(): GeneratedDocumentationAnswer = GeneratedDocumentationAnswer(
        status = "answered",
        answer = "Project modules are listed in settings.gradle.kts.",
        sourceIds = listOf(chunk.metadata.chunkId),
    )

    private fun unknownAnswer(): GeneratedDocumentationAnswer = GeneratedDocumentationAnswer(
        status = "unknown",
        answer = "Не нашёл в документации.",
        sourceIds = emptyList(),
    )

    private fun branchOnly() = GroundingRequirements(false, true, false, fetchFiles = false)
    private fun filesOnly() = GroundingRequirements(false, false, true, fetchFiles = true)

    private fun emptyEvidence(): EvidencePack =
        EvidencePackBuilder().build(retrieval.copy(hits = emptyList()), maxTokens = 0)

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
        fileByteBudget = 20_000,
    )
}
