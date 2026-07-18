package ru.ai.course.day34.fileassistant

object ConsoleRenderer {
    fun render(result: AgentRunResult) {
        println("GOAL: ${result.goal}")
        println("PLANNER MODE: ${result.plannerMode}")
        println("MCP TOOLS: ${FileTool.expected.sorted().joinToString()}")
        result.trace.forEach { observation ->
            println("STEP ${observation.step}")
            println("TOOL: ${observation.tool}")
            println("ARGUMENTS: ${observation.argumentsSummary}")
            println("OBSERVATION: ${observation.observationSummary}")
        }
        println("FINISH: ${result.finishSummary}")
        printPaths("FILES DISCOVERED", result.session.filesDiscovered)
        printPaths("FILES SEARCHED", result.session.filesSearched)
        printPaths("FILES READ", result.session.filesRead)
        printPaths("FILES WRITTEN", result.session.filesWritten)
        println("DIFF:")
        println(result.session.diff.ifBlank { "(no changes)" })
        result.checks.forEach { println("CHECK: $it") }
        println("DIFF SHA-256: ${result.session.diffSha256}")
        println("LLM CALLS: ${result.llmCalls}")
    }

    private fun printPaths(label: String, paths: List<String>) {
        println("$label (${paths.size}): ${paths.joinToString().ifBlank { "(none)" }}")
    }
}
