class InteractiveCli(private val orchestrator: AgentOrchestrator) {
    fun run() {
        println("Day 15 controlled lifecycle. Commands: /status, /approve, /try-transition <state>, /pause, /resume, /state, /history, /reset, /exit.")
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
                line.startsWith("/reject ") -> println(orchestrator.reject(line.removePrefix("/reject ").trim()))
                line == "/pause" -> println(orchestrator.pause())
                line == "/resume" -> println(orchestrator.resume())
                line.startsWith("/try-transition ") -> println(orchestrator.tryTransition(line.removePrefix("/try-transition ").trim()))
                line == "/reset" -> println(orchestrator.reset())
                else -> println(orchestrator.start(line))
            }
        }
    }
}
