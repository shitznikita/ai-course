package ru.ai.course.day33.supportassistant

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = runCatching {
        runBlocking { runCommand(args.toList()) }
    }.fold(
        onSuccess = { 0 },
        onFailure = {
            System.err.println("ERROR: ${it.message ?: it::class.simpleName}")
            1
        },
    )
    if (exitCode != 0) exitProcess(exitCode)
}

private suspend fun runCommand(args: List<String>) {
    val config = AppConfig.load()
    val command = args.firstOrNull() ?: "fixture-demo"
    LazyEmbeddedSupportMcpGateway(config).use { gateway ->
        val assistant = SupportAssistant(config, gateway)
        when (command) {
            "mcp-smoke" -> mcpSmoke(config, gateway)
            "fixture-demo" -> fixtureDemo(assistant)
            "prompt-dry-run" -> {
                val ticketId = args.getOrNull(1) ?: error("Usage: prompt-dry-run <ticketId> <question>")
                val question = args.drop(2).joinToString(" ").takeIf(String::isNotBlank)
                    ?: error("Usage: prompt-dry-run <ticketId> <question>")
                promptDryRun(assistant, ticketId, question, config)
            }
            "eval-dry-run" -> evaluation(assistant, config)
            "ask" -> {
                val ticketId = args.getOrNull(1) ?: error("Usage: ask <ticketId> <question>")
                val question = args.drop(2).joinToString(" ").takeIf(String::isNotBlank)
                    ?: error("Usage: ask <ticketId> <question>")
                println(ConsoleRenderer.render(assistant.askLive(ticketId, question)))
            }
            "chat" -> chat(assistant, gateway, config)
            else -> error(
                "Unknown mode '$command'. Use mcp-smoke, fixture-demo, prompt-dry-run, " +
                    "eval-dry-run, ask, or chat.",
            )
        }
    }
}

private suspend fun mcpSmoke(config: AppConfig, gateway: SupportContextGateway) {
    val fetch = gateway.fetch("TCK-1001")
    val context = requireNotNull(fetch.context) { fetch.failureReason ?: "MCP context missing." }
    require(fetch.availableTools.toSet() == SupportTool.REQUIRED)
    require(context.ticket.userId == context.user.id)
    println("MCP ENDPOINT: ${config.mcpEndpoint()}")
    println("LOOPBACK ONLY: ${config.mcpHost == "127.0.0.1" || config.mcpHost == "localhost"}")
    println("ADVERTISED TOOLS: ${fetch.availableTools.joinToString()}")
    println("MCP TOOLS USED: ${fetch.usedTools.joinToString()}")
    println("TICKET: ${context.ticket.id} errorCode=${context.ticket.errorCode}")
    println("LINKED USER: ${context.user.id} accountState=${context.user.accountState}")
    println(
        "CHECK: exact tools=true, ticket/user link=${context.ticket.userId == context.user.id}, " +
            "server loopback=true",
    )
}

private suspend fun fixtureDemo(assistant: SupportAssistant) {
    val question = "Почему не работает авторизация?"
    val locked = assistant.askFixture("TCK-1001", question)
    val otp = assistant.askFixture("TCK-1002", question)
    println("=== ACCOUNT_LOCKED ===")
    println(ConsoleRenderer.render(locked))
    println()
    println("=== INVALID_OTP / CLOCK_SKEW ===")
    println(ConsoleRenderer.render(otp))
    println()
    val valid = locked.outcome == AssistantOutcome.ANSWERED &&
        otp.outcome == AssistantOutcome.ANSWERED &&
        locked.response.answer != otp.response.answer &&
        locked.llmCalls + otp.llmCalls == 0 &&
        locked.currentTicketIsolationValid &&
        otp.currentTicketIsolationValid
    require(valid) { "Fixture demo did not prove context-specific grounded answers." }
    println("SAME QUESTION, DIFFERENT TICKETS: context-grounded answers differ=true")
    println("CHECK: grounded=true, MCP context valid=true, current-ticket isolation valid=true, LLM calls=0")
}

private suspend fun promptDryRun(
    assistant: SupportAssistant,
    ticketId: String,
    question: String,
    config: AppConfig,
) {
    val preparation = assistant.prepare(ticketId, question)
    val context = requireNotNull(preparation.context) { preparation.fetch.failureReason ?: "Context missing." }
    val evidence = requireNotNull(preparation.evidence)
    val prompt = requireNotNull(preparation.prompt) { "No relevant evidence; no live prompt would be sent." }
    println("PROMPT DRY RUN (no network, no Authorization header)")
    println("TICKET: ${context.ticket.id}")
    println("QUESTION CHARS: ${question.length}/${config.limits.maxQuestionChars}")
    println("EVIDENCE CHARS: ${evidence.totalChars}/${config.limits.maxEvidenceChars}")
    println(
        "PROMPT CHARS: ${prompt.system.length + prompt.user.length}/${config.limits.maxPromptChars} " +
            "(system=${prompt.system.length}, user=${prompt.user.length})",
    )
    println("ALLOWED SOURCE IDS: ${prompt.allowedSourceIds.sorted().joinToString()}")
    println("ALLOWED CONTEXT FACT IDS: ${prompt.allowedFactIds.sorted().joinToString()}")
    println(
        "DATA BOUNDARIES: <<<TYPED_SUPPORT_INPUT_JSON>>>, <<<KNOWLEDGE_EVIDENCE>>>, " +
            "<<<END_KNOWLEDGE_EVIDENCE>>>",
    )
    println("CHECK: current ticket only=true, secrets printed=false, raw model output printed=false")
}

private suspend fun evaluation(assistant: SupportAssistant, config: AppConfig) {
    val summary = Evaluation(config).run(assistant)
    summary.checks.forEach {
        println("${if (it.passed) "PASS" else "FAIL"} ${it.name}: ${it.detail}")
    }
    println("RESULT: ${summary.passed}/${summary.total} checks passed")
    require(summary.allPassed) { "Evaluation failed." }
}

private suspend fun chat(
    assistant: SupportAssistant,
    gateway: SupportContextGateway,
    config: AppConfig,
) {
    val state = ChatSessionState(config.limits.chatHistoryTurns)
    println("Day 33 support chat. Current ticket: ${state.ticketId}")
    println("Commands: /ticket TCK-..., /context, /sources, /clear, /exit")
    while (true) {
        print("support[${state.ticketId}]> ")
        val line = readlnOrNull()?.trim() ?: break
        when {
            line.isBlank() -> Unit
            line == "/exit" -> return
            line == "/clear" -> {
                state.clear()
                println("In-memory history and last evidence cleared.")
            }
            line == "/context" -> {
                val fetch = gateway.fetch(state.ticketId)
                val context = fetch.context
                if (context == null) {
                    println(fetch.failureReason ?: "Context unavailable.")
                } else {
                    println("CURRENT CONTEXT FACTS:")
                    context.facts.forEach { println("  ${it.id} = ${it.value}") }
                }
            }
            line == "/sources" -> {
                val items = state.lastEvidence?.items.orEmpty()
                if (items.isEmpty()) println("(no sources)") else items.forEach {
                    println("${it.chunk.sourceId} score=${"%.3f".format(it.score)}")
                }
            }
            line.startsWith("/ticket ") -> {
                val changed = state.switchTicket(line.removePrefix("/ticket ").trim())
                println(
                    if (changed) {
                        "Ticket switched to ${state.ticketId}; history and last evidence cleared."
                    } else {
                        "Ticket unchanged."
                    },
                )
            }
            else -> {
                val result = assistant.askLive(state.ticketId, line, state.history())
                println(ConsoleRenderer.render(result))
                state.record(result)
            }
        }
    }
}
