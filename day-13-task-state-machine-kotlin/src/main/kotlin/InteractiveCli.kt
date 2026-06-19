class InteractiveCli(private val orchestrator: AgentOrchestrator) {
    fun run() {
        println("Day 13 task state machine. Commands: /status, /state, /history, /approve, /pause, /resume, /reset, /exit.")
        val existing = orchestrator.status()
        if (!existing.startsWith("No task")) {
            println("=== RESUMED ===")
            println(existing)
        }
        while (true) {
            print("> ")
            val line = readlnOrNull()?.trim() ?: break
            if (line.isBlank()) continue
            when {
                line == "/exit" -> return
                line == "/status" -> println(orchestrator.status())
                line == "/state" -> println(orchestrator.stateJson())
                line == "/history" -> println(orchestrator.history())
                line == "/approve" -> println(orchestrator.approve())
                line == "/pause" -> println(orchestrator.pause())
                line == "/resume" -> println(orchestrator.resume())
                line.startsWith("/reject ") -> println(orchestrator.reject(line.removePrefix("/reject ").trim()))
                line == "/reset" -> println(orchestrator.reset())
                else -> println(orchestrator.start(line))
            }
        }
    }
}
