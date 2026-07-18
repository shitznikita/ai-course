package ru.ai.course.day34.fileassistant

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject

@Serializable
data class EvaluationScenario(
    val id: String,
    val goal: String,
    val minimumReadFiles: Int,
    val requiredReadPaths: List<String>,
    val expectedChangedPaths: List<String>,
)

class Evaluation(
    private val moduleRoot: Path,
    private val config: AppConfig,
) {
    private val scenarioConfig = config.copy(mcpPort = 0)

    suspend fun run(): List<String> {
        val checks = mutableListOf<String>()
        val scenarios = loadScenarios()
        require(scenarios.size >= 2) { "At least two evaluation scenarios are required." }
        scenarios.forEach { scenario ->
            val result = runFixtureScenario(scenario, "eval-${scenario.id}")
            check(result.session.filesRead.size >= scenario.minimumReadFiles) {
                "${scenario.id}: expected at least ${scenario.minimumReadFiles} read files."
            }
            scenario.requiredReadPaths.forEach { suffix ->
                check(result.session.filesRead.any { it.endsWith(suffix) }) {
                    "${scenario.id}: missing required read path $suffix."
                }
            }
            check(result.session.changedPaths == scenario.expectedChangedPaths.sorted()) {
                "${scenario.id}: changed paths ${result.session.changedPaths}."
            }
            check(result.session.diff.isNotBlank()) { "${scenario.id}: diff must not be blank." }
            check(result.llmCalls == 0) { "${scenario.id}: fixture evaluation must make zero LLM calls." }
            checks += "${scenario.id}: goal-level loop read ${result.session.filesRead.size} files and changed expected paths"
        }

        val safetyRoot = DemoWorkspace(moduleRoot).reset("eval-safety")
        val policy = ProjectFilePolicy(safetyRoot)
        check(runCatching { policy.normalizeFile("../outside.md") }.isFailure)
        checks += "path traversal rejected"
        check(runCatching { policy.normalizeFile(".env") }.isFailure)
        checks += "excluded secret path rejected"

        val symlink = safetyRoot.resolve("linked-readme.md")
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(symlink, Path.of("README.md"))
        }.isSuccess
        if (symlinkCreated) {
            check(runCatching { policy.resolveExisting("linked-readme.md") }.isFailure)
            checks += "symlink path rejected"
        } else {
            checks += "symlink check unavailable on this filesystem"
        }

        val preview = ProjectWorkspace(policy, WriteMode.PREVIEW)
        check(runCatching {
            preview.writeFile("README.md", "# unsafe\n", expectedSha256 = null)
        }.isFailure)
        checks += "read-before-write enforced"
        val original = Files.readString(safetyRoot.resolve("README.md"), StandardCharsets.UTF_8)
        preview.listFiles(null, 200)
        val read = preview.readFiles(listOf("README.md")).files.single()
        check(runCatching {
            preview.writeFile("README.md", "# stale\n", expectedSha256 = "0".repeat(64))
        }.isFailure)
        checks += "stale SHA rejected"
        preview.writeFile("README.md", original + "\nPreview note.\n", read.sha256)
        check(Files.readString(safetyRoot.resolve("README.md"), StandardCharsets.UTF_8) == original)
        checks += "preview overlay did not mutate disk"

        val applyRoot = DemoWorkspace(moduleRoot).reset("eval-apply")
        val apply = ProjectWorkspace(ProjectFilePolicy(applyRoot), WriteMode.APPLY)
        apply.listFiles(null, 200)
        val applyRead = apply.readFiles(listOf("docs/api.md")).files.single()
        apply.writeFile("docs/api.md", applyRead.content + "\nApply marker.\n", applyRead.sha256)
        check("Apply marker." in Files.readString(applyRoot.resolve("docs/api.md")))
        checks += "apply changed only a policy-approved fixture file"

        val loopFailure = runCatching {
            ProjectFileAssistant(EmptyGateway(), NeverFinishingPlanner()).run("Exercise the bounded loop guard")
        }.exceptionOrNull()
        check(loopFailure?.message?.contains("12-step") == true)
        checks += "max-step guard stopped a non-finishing planner"

        val diff = apply.unifiedDiff()
        check("--- a/docs/api.md" in diff.diff && "+++ b/docs/api.md" in diff.diff && "@@" in diff.diff)
        checks += "deterministic unified diff contains valid headers and hunk"
        return checks
    }

    suspend fun runFixtureScenario(scenario: EvaluationScenario, runtimeName: String): AgentRunResult {
        val root = DemoWorkspace(moduleRoot).reset(runtimeName)
        val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.APPLY)
        val gateway = LazyEmbeddedFileMcpGateway(scenarioConfig, workspace)
        return try {
            ProjectFileAssistant(gateway, FixturePlanner()).run(scenario.goal)
        } finally {
            gateway.close()
        }
    }

    fun loadScenarios(): List<EvaluationScenario> {
        val path = moduleRoot.resolve("eval/scenarios.json")
        return AppJson.strict.decodeFromString(Files.readString(path, StandardCharsets.UTF_8))
    }

    private class EmptyGateway : FileToolGateway {
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

    private class NeverFinishingPlanner : AgentPlanner {
        override val mode: String = "loop-test"
        override val llmCalls: Int = 0
        override suspend fun next(context: PlannerContext): PlanAction = PlanAction(
            type = "tool_call",
            tool = FileTool.LIST_FILES,
            arguments = JsonObject(mapOf("limit" to kotlinx.serialization.json.JsonPrimitive(context.step))),
        )
    }
}
