class InteractiveCli(
    private val memoryManager: MemoryManager,
    private val promptBuilder: PromptBuilder,
    private val agent: MemoryAgent,
) {
    fun run() {
        println("MemoryLayersAgent CLI")
        println("Commands: /memory show [short|working|long], /memory save short <text>, /memory save working <key> <value>, /memory save long <key> <value>, /memory clear short|working, /memory status, /exit")
        println()

        while (true) {
            print("User: ")
            System.out.flush()
            val input = readLine()?.trim() ?: break
            if (input.isBlank()) continue

            when {
                input == "/exit" || input == "exit" || input == "quit" -> return
                input == "/memory show" -> println(memoryManager.show(null))
                input.startsWith("/memory show ") -> handleShow(input)
                input.startsWith("/memory save short ") -> {
                    val text = input.removePrefix("/memory save short ").trim()
                    memoryManager.saveShortText(text)
                    println("Agent: saved to short-term memory.")
                }
                input.startsWith("/memory save working ") -> handleSaveWorking(input)
                input.startsWith("/memory save long ") -> handleSaveLong(input)
                input == "/memory clear short" -> {
                    memoryManager.clearShort()
                    println("Agent: short-term memory cleared.")
                }
                input == "/memory clear working" -> {
                    memoryManager.clearWorking()
                    println("Agent: working memory cleared.")
                }
                input == "/memory status" -> println(promptBuilder.status(memoryManager.snapshot()))
                input.startsWith("/memory ") -> printHelp()
                else -> {
                    val answer = agent.ask(input)
                    printPromptAudit(answer.prompt)
                    println("API usage: ${formatApiUsage(answer.usage)}")
                    answer.warningOrError?.let { println("Warning: $it") }
                    println()
                    println("Agent: ${answer.answer}")
                    println()
                }
            }
        }
    }

    private fun handleShow(input: String) {
        val layerName = input.removePrefix("/memory show ").trim()
        val layer = MemoryLayer.fromCli(layerName)
        if (layer == null) {
            println("Agent: unknown layer. Use short, working, or long.")
            return
        }
        println(memoryManager.show(layer))
    }

    private fun handleSaveWorking(input: String) {
        val rest = input.removePrefix("/memory save working ").trim()
        val key = rest.substringBefore(" ", missingDelimiterValue = "")
        val value = rest.substringAfter(" ", missingDelimiterValue = "").trim()
        if (key.isBlank() || value.isBlank()) {
            println("Agent: usage: /memory save working <key> <value>")
            return
        }
        if (memoryManager.saveWorking(key, value)) {
            println("Agent: saved to working memory.")
        } else {
            println("Agent: unsupported working key. Use task_name, goal, status, stage, constraint, decision, open_question, next_step.")
        }
    }

    private fun handleSaveLong(input: String) {
        val rest = input.removePrefix("/memory save long ").trim()
        val key = rest.substringBefore(" ", missingDelimiterValue = "")
        val value = rest.substringAfter(" ", missingDelimiterValue = "").trim()
        if (key.isBlank() || value.isBlank()) {
            println("Agent: usage: /memory save long <key> <value>")
            return
        }
        if (memoryManager.saveLong(key, value)) {
            println("Agent: saved to long-term memory.")
        } else {
            println("Agent: unsupported long key. Use user_name, preference, global_rule, stable_decision, knowledge.")
        }
    }

    private fun printHelp() {
        println(
            """
            Commands:
            /memory show
            /memory show short
            /memory show working
            /memory show long
            /memory save short <text>
            /memory save working <key> <value>
            /memory save long <key> <value>
            /memory clear short
            /memory clear working
            /memory status
            /exit
            """.trimIndent(),
        )
    }
}
