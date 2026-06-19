class DemoRunner(
    private val storage: TaskStateStorage,
    private val orchestrator: AgentOrchestrator,
) {
    fun runFullDemo() {
        storage.reset()
        println("Day 13: Task State Machine")
        println()
        println(
            orchestrator.start(
                "Собери ТЗ для MVP Android-приложения учета личных финансов. Первый релиз без backend, срок 3 недели, нужен экспорт CSV.",
            ),
        )
        println("=== SIMULATED RESTART ===")
        println("Loaded state from task_state.json")
        println(orchestrator.status())
        println("Напишите /approve, чтобы перейти к execution, или /reject <comment>, чтобы изменить план.")
        println()
        println(orchestrator.approve())
        println("=== HISTORY ===")
        println(orchestrator.history())
    }

    fun runPauseDemo() {
        storage.reset()
        println("Day 13: pause demo")
        println(orchestrator.start("Сделай ТЗ для приложения."))
        println("=== USER PROVIDES MISSING INPUTS ===")
        println(orchestrator.resume("Это Android-приложение для учета финансов, срок 3 недели, без backend, нужен экспорт CSV."))
    }
}
