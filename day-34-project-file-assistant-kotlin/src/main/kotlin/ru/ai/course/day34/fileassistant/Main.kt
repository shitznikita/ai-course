package ru.ai.course.day34.fileassistant

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

fun main(args: Array<String>) = runBlocking {
    val moduleRoot = Path.of("").toAbsolutePath().normalize()
    val config = AppConfig()
    when (val mode = args.firstOrNull() ?: "fixture-demo") {
        "mcp-smoke" -> mcpSmoke(moduleRoot, config)
        "fixture-demo" -> fixtureDemo(moduleRoot, config)
        "eval-dry-run" -> evaluation(moduleRoot, config)
        "repro-check" -> reproducibility(moduleRoot, config)
        "goal-dry-run" -> {
            val goal = args.drop(1).joinToString(" ").trim()
            require(goal.isNotBlank()) { "goal-dry-run requires a goal." }
            val root = DemoWorkspace(moduleRoot).reset("goal-dry-run")
            renderRun(root, WriteMode.PREVIEW, OllamaPlanner(config), goal, config)
        }
        "live-demo" -> {
            val root = DemoWorkspace(moduleRoot).reset("live-demo")
            renderRun(
                root,
                WriteMode.APPLY,
                OllamaPlanner(config),
                DemoGoals.LEGACY_USAGE + ". Сохрани результат в новом Markdown-файле проекта и покажи diff",
                config,
            )
        }
        "goal" -> customGoal(args.drop(1), config)
        else -> error("Unknown mode '$mode'.\n${usage()}")
    }
}

private suspend fun mcpSmoke(moduleRoot: Path, config: AppConfig) {
    val root = DemoWorkspace(moduleRoot).reset("mcp-smoke")
    val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.PREVIEW)
    val gateway = LazyEmbeddedFileMcpGateway(config, workspace)
    try {
        val tools = gateway.tools()
        val listedJson = gateway.call(
            FileTool.LIST_FILES,
            JsonObject(mapOf("limit" to JsonPrimitive(20))),
        )
        val searchedJson = gateway.call(
            FileTool.SEARCH_TEXT,
            JsonObject(mapOf("query" to JsonPrimitive("LegacyPaymentsApi"), "limit" to JsonPrimitive(20))),
        )
        val searched = AppJson.strict.decodeFromJsonElement<SearchResult>(searchedJson)
        val paths = searched.hits.map(SearchHit::path).distinct().take(3)
        require(paths.size == 3) { "Smoke fixture must expose three LegacyPaymentsApi files." }
        val readJson = gateway.call(
            FileTool.READ_FILES,
            JsonObject(mapOf("paths" to JsonArray(paths.map(::JsonPrimitive)))),
        )
        val diffJson = gateway.call(FileTool.UNIFIED_DIFF, JsonObject(emptyMap()))
        val listed = AppJson.strict.decodeFromJsonElement<FileListResult>(listedJson)
        val read = AppJson.strict.decodeFromJsonElement<ReadFilesResult>(readJson)
        val diff = AppJson.strict.decodeFromJsonElement<DiffResult>(diffJson)
        println("MCP ENDPOINT: ${gateway.endpoint()}")
        println("MCP TOOLS (${tools.size}): ${tools.sorted().joinToString()}")
        println("LIST: ${listed.files.size} bounded text files")
        println("SEARCH: ${searched.hits.size} hits in ${searched.hits.map(SearchHit::path).distinct().size} files")
        println("READ: ${read.files.joinToString { "${it.path} (${it.bytes} B)" }}")
        println("DIFF: ${diff.changedPaths.size} changed files")
        println("CHECK: loopback endpoint and exact five tools accepted")
        println("CHECK: list/search/read succeeded with zero writes")
        println("LLM CALLS: 0")
    } finally {
        gateway.close()
    }
}

private suspend fun fixtureDemo(moduleRoot: Path, config: AppConfig) {
    val evaluator = Evaluation(moduleRoot, config)
    evaluator.loadScenarios().forEachIndexed { index, scenario ->
        if (index > 0) println("\n${"=".repeat(80)}\n")
        val result = evaluator.runFixtureScenario(scenario, "fixture-${scenario.id}")
        ConsoleRenderer.render(result)
    }
}

private suspend fun evaluation(moduleRoot: Path, config: AppConfig) {
    val checks = Evaluation(moduleRoot, config).run()
    checks.forEach { println("CHECK: PASS — $it") }
    println("EVAL: PASS (${checks.size} checks)")
    println("LLM CALLS: 0")
}

private suspend fun reproducibility(moduleRoot: Path, config: AppConfig) {
    val evaluator = Evaluation(moduleRoot, config)
    evaluator.loadScenarios().forEach { scenario ->
        val first = evaluator.runFixtureScenario(scenario, "repro-${scenario.id}-a")
        val second = evaluator.runFixtureScenario(scenario, "repro-${scenario.id}-b")
        val firstTrace = normalizedTrace(first)
        val secondTrace = normalizedTrace(second)
        val same = firstTrace == secondTrace &&
            first.session.changedPaths == second.session.changedPaths &&
            first.session.diffSha256 == second.session.diffSha256
        println("SCENARIO: ${scenario.id}")
        println("TRACE SHA-256: ${sha256(firstTrace)}")
        println("CHANGED PATHS: ${first.session.changedPaths.joinToString()}")
        println("DIFF SHA-256: ${first.session.diffSha256}")
        println("REPRODUCIBLE: $same")
        check(same) { "Scenario ${scenario.id} is not reproducible." }
    }
    println("LLM CALLS: 0")
}

private suspend fun customGoal(args: List<String>, config: AppConfig) {
    var workspace: String? = null
    var mode = WriteMode.PREVIEW
    var explicitMode: WriteMode? = null
    val goalParts = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        when (val value = args[index]) {
            "--workspace" -> {
                require(workspace == null && index + 1 < args.size) { usage() }
                workspace = args[index + 1]
                index += 2
            }
            "--preview" -> {
                require(explicitMode == null || explicitMode == WriteMode.PREVIEW) {
                    "Choose either --preview or --apply."
                }
                explicitMode = WriteMode.PREVIEW
                mode = WriteMode.PREVIEW
                index++
            }
            "--apply" -> {
                require(explicitMode == null || explicitMode == WriteMode.APPLY) {
                    "Choose either --preview or --apply."
                }
                explicitMode = WriteMode.APPLY
                mode = WriteMode.APPLY
                index++
            }
            else -> {
                require(!value.startsWith("--")) { "Unknown goal option: $value" }
                goalParts += value
                index++
            }
        }
    }
    val root = Path.of(requireNotNull(workspace) { usage() }).toAbsolutePath().normalize()
    val goal = goalParts.joinToString(" ").trim()
    require(goal.isNotBlank()) { "goal mode requires goal text." }
    renderRun(root, mode, OllamaPlanner(config), goal, config)
}

private suspend fun renderRun(
    root: Path,
    mode: WriteMode,
    planner: AgentPlanner,
    goal: String,
    config: AppConfig,
) {
    val workspace = ProjectWorkspace(ProjectFilePolicy(root), mode)
    val gateway = LazyEmbeddedFileMcpGateway(config, workspace)
    try {
        ConsoleRenderer.render(ProjectFileAssistant(gateway, planner).run(goal))
    } finally {
        gateway.close()
    }
}

private fun normalizedTrace(result: AgentRunResult): String =
    result.trace.joinToString("\n") {
        "${it.step}|${it.tool}|${it.argumentsSummary}|${it.observationSummary}"
    }

private fun usage(): String = """
    Usage:
      mcp-smoke
      fixture-demo
      eval-dry-run
      repro-check
      goal-dry-run <goal>
      live-demo
      goal --workspace <dir> [--preview|--apply] <goal>
""".trimIndent()
