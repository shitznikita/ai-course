class InteractiveCli(
    private val agent: ContextAgent,
) {
    fun run() {
        println("ContextStrategyAgent CLI")
        println("Commands: /strategy sliding|facts|branching, /checkpoint, /branch create <name>, /branch switch <name>, /branches, /status, /clear, /exit")
        println()

        while (true) {
            print("User: ")
            System.out.flush()
            val input = readLine()?.trim() ?: break
            if (input.isBlank()) continue

            when {
                input == "/exit" || input == "exit" || input == "quit" -> return
                input == "/clear" -> {
                    agent.clear()
                    println("Agent: state cleared.")
                }

                input.startsWith("/strategy ") -> {
                    val strategy = StrategyName.fromCli(input.removePrefix("/strategy ").trim())
                    if (strategy == null) {
                        println("Agent: unknown strategy. Use sliding, facts, or branching.")
                    } else {
                        agent.switchStrategy(strategy)
                        println("Agent: strategy is now ${strategy.cliName}.")
                    }
                }

                input == "/checkpoint" -> {
                    agent.createCheckpoint()
                    println("Agent: checkpoint saved.")
                }

                input.startsWith("/branch create ") -> {
                    val name = input.removePrefix("/branch create ").trim()
                    agent.createBranch(name)
                    println("Agent: branch created: $name")
                }

                input.startsWith("/branch switch ") -> {
                    val name = input.removePrefix("/branch switch ").trim()
                    if (agent.switchBranch(name)) {
                        println("Agent: active branch: $name")
                    } else {
                        println("Agent: branch not found: $name")
                    }
                }

                input == "/branches" -> println("Branches: ${agent.branchNames().joinToString().ifBlank { "none" }}")
                input == "/status" -> printStatus()
                else -> {
                    val answer = agent.ask(input)
                    println()
                    println("=== CONTEXT ===")
                    println("Strategy: ${answer.strategy.cliName}")
                    println("Recent/messages sent: ${answer.context.recentMessagesSent}")
                    println("Dropped messages: ${answer.context.droppedMessages}")
                    println("Prompt tokens: ${answer.context.promptTokens}")
                    answer.context.factsBlock?.let {
                        println("Facts:")
                        println(it)
                    }
                    answer.context.branchInfo?.let { println("Branch: $it") }
                    println("API usage: ${formatApiUsage(answer.usage)}")
                    println()
                    println("Agent: ${answer.answer}")
                    println()
                }
            }
        }
    }

    private fun printStatus() {
        val state = agent.currentState()
        val context = agent.buildCurrentContext()
        println("Strategy: ${agent.currentStrategy().cliName}")
        println("Stored main messages: ${state.messages.size}")
        println("Facts:")
        println(state.facts.toPromptBlock())
        println("Branches: ${state.branchState.branches.keys.joinToString().ifBlank { "none" }}")
        println("Active branch: ${state.branchState.activeBranch.ifBlank { "none" }}")
        println("Current prompt tokens: ${context.promptTokens}")
    }
}
