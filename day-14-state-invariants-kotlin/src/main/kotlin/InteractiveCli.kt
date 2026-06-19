class InteractiveCli(
    private val store: InvariantStore,
    private val checker: InvariantChecker,
    private val agent: InvariantAwareAgent,
) {
    fun run() {
        store.ensureSeed()
        println("Day 14 invariant-aware agent. Commands: /invariants active, /invariants check <text>, /debug is env AGENT_DEBUG, /exit.")
        while (true) {
            print("> ")
            val line = readlnOrNull()?.trim() ?: break
            if (line.isBlank()) continue
            when {
                line == "/exit" -> return
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
                else -> {
                    val result = agent.ask(line)
                    println("=== ACTIVE INVARIANTS ===")
                    result.activeInvariants.forEach { println("- ${it.id}") }
                    println("=== INVARIANT CHECK ===")
                    println(result.requestValidation.render())
                    println("=== ASSISTANT RESPONSE ===")
                    println(result.response)
                }
            }
        }
    }

    private fun printInvariants(invariants: List<Invariant>) {
        invariants.forEach { println("- ${it.id}: enabled=${it.enabled}, type=${it.type}, severity=${it.severity}, ${it.title}") }
    }
}
