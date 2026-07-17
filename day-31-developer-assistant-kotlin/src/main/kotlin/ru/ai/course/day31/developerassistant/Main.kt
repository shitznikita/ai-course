package ru.ai.course.day31.developerassistant

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val FIXTURE_QUESTION = "На какой ветке я сейчас и как устроен проект?"

fun main(args: Array<String>) {
    try {
        runBlocking {
            runCommand(AppConfig.load(), args.toList())
        }
    } catch (error: Exception) {
        System.err.println("Day 31 failed: ${error.message ?: error::class.simpleName}")
        exitProcess(1)
    }
}

private suspend fun runCommand(config: AppConfig, args: List<String>) {
    when (val command = args.firstOrNull() ?: "chat") {
        "server" -> {
            println("Day 31 project MCP server: ${config.mcpUrl}")
            startProjectMcpServer(config, GitProjectGateway(config.projectRoot), wait = true)
        }
        "mcp-smoke" -> withEmbeddedMcp(config) { client ->
            println("Day 31 MCP smoke")
            println("MCP SERVER: ${config.mcpUrl}")
            val context = client.fetchContext(includeFiles = true, fileLimit = 12)
            ConsoleRenderer.printMcp(context)
            println("CHECK: tools/list, git_current_branch and bounded project_list_files succeeded")
        }
        "fixture-demo" -> withEmbeddedMcp(config) { client ->
            val assistant = DeveloperAssistant(config, client)
            ConsoleRenderer.printCorpus(assistant.ensureIndex())
            ConsoleRenderer.printRun(assistant.askFixture(FIXTURE_QUESTION))
        }
        "prompt-dry-run" -> {
            val question = args.drop(1).joinToString(" ").trim().ifBlank { FIXTURE_QUESTION }
            withEmbeddedMcp(config) { client ->
                val assistant = DeveloperAssistant(config, client)
                ConsoleRenderer.printRun(
                    assistant.askFixture(question),
                    includePrompt = true,
                )
                println()
                println("CHECK: documentation-only prompt contains no concrete MCP values; Ollama was not called")
            }
        }
        "eval-dry-run" -> {
            val report = Evaluation(config).run()
            ConsoleRenderer.printEvaluation(report)
            check(report.passed == report.total) { "Evaluation failed: ${report.passed}/${report.total} passed." }
        }
        "diagnose" -> diagnose(config)
        "ask" -> {
            val question = args.drop(1).joinToString(" ").trim()
            require(question.isNotBlank()) { "Usage: ask <question>" }
            withEmbeddedMcp(config) { client ->
                ConsoleRenderer.printRun(DeveloperAssistant(config, client).askLive(question))
            }
        }
        "chat" -> withEmbeddedMcp(config) { client ->
            runChat(config, client)
        }
        else -> {
            printUsage()
            error("Unknown command: $command")
        }
    }
}

private suspend fun <T> withEmbeddedMcp(
    config: AppConfig,
    block: suspend (ProjectContextGateway) -> T,
): T {
    val gateway = LazyEmbeddedProjectMcpGateway(config)
    try {
        return block(gateway)
    } finally {
        gateway.close()
    }
}

private fun diagnose(config: AppConfig) {
    println("DAY 31 DIAGNOSTICS")
    println("PROJECT ROOT: ${config.projectRoot}")
    val gateway = GitProjectGateway(config.projectRoot)
    println("GIT BRANCH: ${gateway.currentBranch().displayName}")
    println("MCP: ${config.mcpUrl} (loopback only)")
    val embeddings = EmbeddingFactory.create(config)
    val index = RagIndexManager(config, embeddings = embeddings).ensureIndex()
    ConsoleRenderer.printCorpus(index)
    println("OLLAMA: ${config.ollamaBaseUrl}")
    try {
        val status = OllamaClient(config).diagnose()
        val names = status.models.map { it.name }
        println("OLLAMA VERSION: ${status.version}")
        println("MODEL ${config.ollamaModel}: ${if (names.any { sameModel(it, config.ollamaModel) }) "installed" else "missing"}")
        if (names.none { sameModel(it, config.ollamaModel) }) println("NEXT: ollama pull ${config.ollamaModel}")
        if (config.embeddingBackend == "ollama" && names.none { sameModel(it, config.ollamaEmbeddingModel) }) {
            println("EMBED MODEL ${config.ollamaEmbeddingModel}: missing")
            println("NEXT: ollama pull ${config.ollamaEmbeddingModel}")
        }
    } catch (error: OllamaUnavailableException) {
        println("OLLAMA STATUS: unavailable (${error.message})")
        println("NEXT: ollama serve && ollama pull ${config.ollamaModel}")
    }
    println("CHECK: git root, allowlisted corpus, index manifest and loopback endpoints inspected")
}

private fun sameModel(actual: String, configured: String): Boolean =
    actual == configured || actual.substringBefore(':') == configured.substringBefore(':')

private suspend fun runChat(config: AppConfig, client: ProjectContextGateway) {
    println("Day 31 developer assistant")
    println("MCP: ${config.mcpUrl}")
    println("Commands: /help <question>, /branch, /files [prefix], /sources, /exit")
    val assistant = DeveloperAssistant(config, client)
    println("RAG CORPUS: lazy; loaded only by a non-sensitive documentation question")
    var lastRun: AssistantRun? = null

    while (true) {
        print("developer> ")
        val input = readlnOrNull()?.trim() ?: break
        when {
            input == "/exit" -> break
            input == "/help" -> println("Usage: /help <question>\nExample: /help Как добавить новый day-модуль?")
            input.startsWith("/help ") -> {
                val question = input.removePrefix("/help ").trim()
                val run = assistant.askLive(question)
                lastRun = run
                ConsoleRenderer.printRun(run)
            }
            input == "/branch" -> {
                val context = client.fetchContext(includeFiles = false, fileLimit = config.maxFileList)
                ConsoleRenderer.printMcp(context)
            }
            input == "/files" || input.startsWith("/files ") -> {
                val prefix = input.removePrefix("/files").trim().ifBlank { null }
                ConsoleRenderer.printMcp(
                    client.fetchContext(includeFiles = true, prefix = prefix, fileLimit = config.maxFileList),
                )
            }
            input == "/sources" -> {
                val run = lastRun
                if (run == null) {
                    println("No /help answer yet.")
                } else {
                    run.evidence.items.forEach { item ->
                        val metadata = item.hit.chunk.metadata
                        println("- ${metadata.source}#${metadata.section} [${item.sourceId}]")
                    }
                }
            }
            input.isBlank() -> Unit
            else -> println("Unknown command. Use /help <question>, /branch, /files [prefix], /sources, or /exit.")
        }
    }
    println("Assistant stopped.")
}

private fun printUsage() {
    println("Usage:")
    println("  chat                              Interactive /help assistant")
    println("  ask <question>                    One live local Ollama answer")
    println("  fixture-demo                      Offline RAG + real MCP + deterministic answer")
    println("  mcp-smoke                         tools/list + branch + tracked files")
    println("  prompt-dry-run <question>         Show bounded prompt without Ollama")
    println("  eval-dry-run                      Run offline retrieval controls")
    println("  diagnose                          Check git, corpus, MCP config and Ollama")
    println("  server                            Run the read-only MCP server")
}
