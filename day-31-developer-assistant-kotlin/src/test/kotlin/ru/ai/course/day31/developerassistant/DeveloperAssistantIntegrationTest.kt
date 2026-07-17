package ru.ai.course.day31.developerassistant

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeveloperAssistantIntegrationTest {
    @Test
    fun `sensitive live ask returns unknown before embeddings MCP prompt or model`() = runBlocking {
        val root = createTempDirectory("day31-sensitive-")
        val config = config(root)
        val embeddings = CountingEmbeddingClient(failOnCall = true)
        var mcpStarts = 0
        val mcp = LazyEmbeddedProjectMcpGateway(config) {
            mcpStarts++
            error("Embedded MCP must not start.")
        }
        val generator = CountingGenerator(failOnCall = true)
        val assistant = DeveloperAssistant(
            config = config,
            mcpClient = mcp,
            embeddings = embeddings,
            generator = generator,
        )

        val run = assistant.askLive("Какой пароль записан в локальном .env?")

        assertEquals("unknown", run.answer.status)
        assertEquals("sensitive topic", run.preflightRefusalReason)
        assertEquals(0, embeddings.calls)
        assertEquals(0, mcpStarts)
        assertEquals(0, generator.calls)
        assertTrue(run.validation.valid)
        assertEquals(0, run.prompt.utf8Bytes)
    }

    @Test
    fun `file intent variants share required fetched and validated typed output`() = runBlocking {
        val root = createTempDirectory("day31-files-")
        val config = config(root)
        val mcp = RecordingMcpGateway(
            files = listOf("Dockerfile", "Makefile", "LICENSE", ".gitignore", "папка/Файл без расширения"),
        )
        val embeddings = CountingEmbeddingClient(failOnCall = true)
        val generator = CountingGenerator(failOnCall = true)
        val assistant = DeveloperAssistant(
            config = config,
            mcpClient = mcp,
            embeddings = embeddings,
            generator = generator,
        )

        listOf("Покажи файлы", "какие файлы есть в проекте?", "show tracked-files").forEach { question ->
            val run = assistant.askLive(question)
            assertTrue(run.requirements.filesRequired, question)
            assertTrue(run.requirements.fetchFiles, question)
            assertTrue(mcp.lastIncludeFiles, question)
            assertTrue(run.answer.projectFiles.isNotEmpty(), question)
            assertEquals(run.mcp.files?.files.orEmpty(), run.answer.projectFiles, question)
            assertTrue(run.validation.valid, "${question}: ${run.validation.errors}")
            assertTrue(run.evidence.items.isEmpty(), question)
        }
        assertEquals(0, embeddings.calls)
        assertEquals(0, generator.calls)
    }

    @Test
    fun `branch-only live ask bypasses generator and derives exact branch`() = runBlocking {
        val root = createTempDirectory("day31-branch-")
        val config = config(root)
        val mcp = RecordingMcpGateway(branch = "server-branch-sentinel")
        val embeddings = CountingEmbeddingClient(failOnCall = true)
        val generator = CountingGenerator(failOnCall = true)
        val assistant = DeveloperAssistant(config, mcp, embeddings = embeddings, generator = generator)

        val run = assistant.askLive("Какая сейчас ветка?")

        assertEquals(0, generator.calls)
        assertEquals(0, embeddings.calls)
        assertEquals("server-branch-sentinel", run.answer.projectBranch)
        assertEquals(AnswerAssembler.MCP_ONLY_MESSAGE, run.answer.answer)
        assertTrue(run.validation.valid, run.validation.errors.joinToString())
        assertEquals(0, run.prompt.utf8Bytes)
    }

    @Test
    fun `mixed live ask exposes only documentation contract and keeps MCP values out of prompt`() = runBlocking {
        val root = createTempDirectory("day31-mixed-")
        writeApprovedDocuments(root)
        val config = config(root)
        val mcp = RecordingMcpGateway(
            branch = "server-branch-sentinel",
            files = listOf("private-sentinel-file.zzz"),
        )
        val embeddings = CountingEmbeddingClient(delegate = HashEmbeddingClient())
        val generator = CountingGenerator(
            failOnCall = false,
            generatedAnswer = "Gradle modules are separate day folders. The branch is main. В проекте есть secrets.toml.",
        )
        val assistant = DeveloperAssistant(config, mcp, embeddings = embeddings, generator = generator)

        val run = assistant.askLive("Как устроены Gradle-модули проекта и какая сейчас ветка?")

        assertEquals(1, generator.calls)
        assertEquals("server-branch-sentinel", run.answer.projectBranch)
        assertEquals(run.mcp.branch.displayName, run.answer.projectBranch)
        assertTrue(run.answer.projectFiles.isEmpty())
        assertTrue(run.validation.valid, run.validation.errors.joinToString())
        assertFalse("The branch is main." in run.answer.answer)
        assertFalse("secrets.toml" in run.answer.answer)
        val prompt = requireNotNull(generator.lastPrompt).preview
        assertFalse("server-branch-sentinel" in prompt)
        assertFalse("private-sentinel-file.zzz" in prompt)
        assertFalse("projectBranch" in prompt)
        assertFalse("projectFiles" in prompt)
        assertFalse("usedProjectContext" in prompt)

        val schema = requireNotNull(generator.lastSchema)
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(GeneratedDocumentationAnswerContract.requiredFields, required)
        assertEquals(
            GeneratedDocumentationAnswerContract.requiredFields.toSet(),
            schema.getValue("properties").jsonObject.keys,
        )
    }

    @Test
    fun `mixed model unknown prose is replaced by canonical server unknown`() = runBlocking {
        val root = createTempDirectory("day31-model-unknown-")
        writeApprovedDocuments(root)
        val config = config(root)
        val mcp = RecordingMcpGateway(
            branch = "server-branch-sentinel",
            files = listOf("README.md", "settings.gradle.kts"),
        )
        val hostileProse = "Мы сейчас работаем в main. В проекте есть secrets.toml."
        val generator = CountingGenerator(
            failOnCall = false,
            generatedAnswer = hostileProse,
            generatedStatus = "unknown",
            includeSourceIds = false,
        )
        val assistant = DeveloperAssistant(
            config = config,
            mcpClient = mcp,
            embeddings = CountingEmbeddingClient(delegate = HashEmbeddingClient()),
            generator = generator,
        )

        val run = assistant.askLive(
            "Как устроен проект, какая сейчас ветка и какие tracked files есть в проекте?",
        )

        assertEquals(1, generator.calls)
        assertEquals("unknown", run.answer.status)
        assertEquals(DeveloperAssistant.CANONICAL_UNKNOWN_MESSAGE, run.answer.answer)
        assertFalse(hostileProse in run.answer.answer)
        assertFalse("main" in run.answer.answer)
        assertFalse("secrets.toml" in run.answer.answer)
        assertTrue(run.answer.sourceIds.isEmpty())
        assertEquals(null, run.answer.projectBranch)
        assertTrue(run.answer.projectFiles.isEmpty())
        assertFalse(run.answer.usedProjectContext)
        assertTrue(run.validation.valid, run.validation.errors.joinToString())
    }

    @Test
    fun `structure intent fetches optional files but does not manufacture file claims`() = runBlocking {
        val root = createTempDirectory("day31-structure-")
        writeApprovedDocuments(root)
        val config = config(root)
        val mcp = RecordingMcpGateway(files = listOf("README.md", "settings.gradle.kts"))
        val embeddings = CountingEmbeddingClient(delegate = HashEmbeddingClient())
        val assistant = DeveloperAssistant(
            config = config,
            mcpClient = mcp,
            embeddings = embeddings,
            generator = CountingGenerator(failOnCall = true),
        )

        val run = assistant.askFixture("Как устроены модули проекта?")

        assertTrue(run.requirements.documentationRequired)
        assertFalse(run.requirements.filesRequired)
        assertTrue(run.requirements.fetchFiles)
        assertTrue(mcp.lastIncludeFiles)
        assertTrue(run.answer.projectFiles.isEmpty())
        assertTrue(run.validation.valid, run.validation.errors.joinToString())
        assertTrue(embeddings.calls > 0)
    }

    private fun config(root: Path): AppConfig = AppConfig.fromValues(
        mapOf(
            "PROJECT_ROOT" to root.toString(),
            "RAG_INDEX_FILE" to root.resolve("runtime/rag-index.json").toString(),
        ),
    )

    private fun writeApprovedDocuments(root: Path) {
        root.resolve("README.md").writeText("# Project\nKotlin Gradle modules and settings.gradle.kts.\n")
        root.resolve("docs").createDirectories()
        root.resolve("docs/project-architecture.md").writeText("# Architecture\nGradle modules are separate day folders.\n")
        root.resolve("docs/developer-assistant-api.yaml").writeText("openapi: 3.1.0\ninfo:\n  title: API\n")
        root.resolve("day-31-developer-assistant-kotlin").createDirectories()
        root.resolve("day-31-developer-assistant-kotlin/README.md").writeText("# Day 31\nDeveloper assistant.\n")
    }

    private class CountingEmbeddingClient(
        private val failOnCall: Boolean = false,
        private val delegate: EmbeddingClient? = null,
    ) : EmbeddingClient {
        override val backend: String = delegate?.backend ?: "counting"
        override val model: String = delegate?.model ?: "counting"
        var calls: Int = 0
            private set

        override fun embed(texts: List<String>): List<List<Double>> {
            calls++
            check(!failOnCall) { "Embedding client must not be called." }
            return requireNotNull(delegate).embed(texts)
        }
    }

    private class RecordingMcpGateway(
        val files: List<String> = listOf("README.md"),
        private val branch: String = "test-branch",
        private val failOnCall: Boolean = false,
    ) : ProjectContextGateway {
        var calls: Int = 0
            private set
        var lastIncludeFiles: Boolean = false
            private set

        override suspend fun fetchContext(
            includeFiles: Boolean,
            prefix: String?,
            fileLimit: Int,
        ): McpProjectContext {
            calls++
            lastIncludeFiles = includeFiles
            check(!failOnCall) { "MCP gateway must not be called." }
            return McpProjectContext(
                availableTools = listOf(ProjectTool.CURRENT_BRANCH, ProjectTool.LIST_FILES),
                branch = GitBranchInfo(branch, detached = false),
                files = if (includeFiles) GitFileList(files = files.take(fileLimit), truncated = files.size > fileLimit) else null,
                usedTools = buildList {
                    add(ProjectTool.CURRENT_BRANCH)
                    if (includeFiles) add(ProjectTool.LIST_FILES)
                },
            )
        }
    }

    private class CountingGenerator(
        private val failOnCall: Boolean,
        private val generatedAnswer: String? = null,
        private val generatedStatus: String = "answered",
        private val includeSourceIds: Boolean = true,
    ) : AssistantAnswerGenerator {
        var calls: Int = 0
            private set
        var lastPrompt: PromptPack? = null
            private set
        var lastSchema: JsonObject? = null
            private set

        override fun answer(prompt: PromptPack, schema: JsonObject): OllamaReply {
            calls++
            lastPrompt = prompt
            lastSchema = schema
            check(!failOnCall) { "Model generator must not be called." }
            val answer = requireNotNull(generatedAnswer) { "No generated answer configured." }
            val sourceIds = prompt.user
                .lineSequence()
                .dropWhile { it.trim() != "ALLOWED SOURCE IDS:" }
                .drop(1)
                .first()
                .trim()
                .split(", ")
            return OllamaReply(
                model = "test-model",
                content = AppJson.strict.encodeToString(
                    GeneratedDocumentationAnswer(
                        status = generatedStatus,
                        answer = answer,
                        sourceIds = sourceIds.takeIf { includeSourceIds }.orEmpty(),
                    ),
                ),
                elapsedNanos = 1,
            )
        }
    }
}
