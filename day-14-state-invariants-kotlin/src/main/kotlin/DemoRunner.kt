class DemoRunner(
    private val storage: TaskStateStorage,
    private val store: InvariantStore,
    private val checker: InvariantChecker,
    private val judge: LLMInvariantJudge,
    private val orchestrator: AgentOrchestrator,
) {
    fun runDemo() {
        store.ensureSeed(reset = true)
        storage.reset()
        println("Day 14: Day 13 state machine + invariants")
        println()
        println(renderActive())
        println()
        println("=== NORMAL STATE MACHINE PATH ===")
        println(
            orchestrator.start(
                "Собери ТЗ для MVP Android-приложения учета личных финансов. Первый релиз без backend, срок 3 недели, нужен экспорт CSV.",
            ),
        )
        println("=== SIMULATED RESTART ===")
        println("Loaded state from task_state.json")
        println(orchestrator.status())
        println()
        println(orchestrator.approve())
        println("=== HISTORY ===")
        println(orchestrator.history())

        println()
        println("=== CONFLICT REQUEST: JAVA ===")
        println(orchestrator.reset())
        println(orchestrator.start("Напиши пример на Java для экрана добавления расхода."))

        println()
        println("=== CONFLICT REQUEST: BACKEND ===")
        println(orchestrator.reset())
        println(orchestrator.start("Добавь backend и облачную синхронизацию в MVP Android приложения учета финансов, срок 3 недели."))
    }

    fun runCheckerDemo() {
        store.ensureSeed()
        val text = "Сделай серверную синхронизацию и пример на Java."
        val invariants = store.active()
        println("=== DETERMINISTIC CHECK ===")
        println(checker.check(text, "checker_demo", invariants).render())
        println()
        println("=== LLM CHECK ===")
        println(judge.judge(text, "checker_demo", invariants).render())
    }

    fun renderActive(): String = buildString {
        appendLine("=== ACTIVE INVARIANTS ===")
        store.active().forEach { appendLine("- ${it.id}: ${it.title} (${it.severity})") }
    }.trimEnd()
}
