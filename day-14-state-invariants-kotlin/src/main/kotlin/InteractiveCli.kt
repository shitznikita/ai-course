class InteractiveCli(
    private val store: InvariantStore,
    private val checker: InvariantChecker,
    private val orchestrator: AgentOrchestrator,
) {
    fun run() {
        store.ensureSeed()
        println("Day 14 state machine + invariants. Commands: /status, /state, /history, /approve, /pause, /resume, /reset, /invariants active, /exit.")
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
                line == "/invariants list" -> printInvariants(store.load().all())
                line == "/invariants active" || line == "/invariants" -> printInvariants(store.active())
                line.startsWith("/invariants show ") -> {
                    val id = line.removePrefix("/invariants show ").trim()
                    println(store.show(id)?.let { appJson.encodeToString(Invariant.serializer(), it) } ?: "Invariant not found: $id")
                }
                line.startsWith("/invariants enable ") -> {
                    val id = line.removePrefix("/invariants enable ").trim()
                    println(if (store.setEnabled(id, true)) "Enabled $id" else "Invariant not found: $id")
                }
                line.startsWith("/invariants disable ") -> {
                    val id = line.removePrefix("/invariants disable ").trim()
                    println(if (store.setEnabled(id, false)) "Disabled $id" else "Invariant not found: $id")
                }
                line.startsWith("/invariants add ") -> {
                    val text = line.removePrefix("/invariants add ").trim()
                    println("Added: ${store.addUserDefined(text).id}")
                }
                line.startsWith("/invariants check ") -> {
                    val text = line.removePrefix("/invariants check ").trim()
                    println(checker.check(text, "manual_check", store.active()).render())
                }
                line == "/debug on" -> println("Set AGENT_DEBUG=true before launch to print raw prompts.")
                line == "/debug off" -> println("Set AGENT_DEBUG=false before launch to hide raw prompts.")
                else -> println(orchestrator.start(line))
            }
        }
    }

    private fun printInvariants(invariants: List<Invariant>) {
        invariants.forEach { println("- ${it.id}: enabled=${it.enabled}, type=${it.type}, severity=${it.severity}, ${it.title}") }
    }
}
