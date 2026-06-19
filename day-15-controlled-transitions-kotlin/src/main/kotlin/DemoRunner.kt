import java.time.Instant

class DemoRunner(
    private val storage: TaskStateStorage,
    private val invariantStore: InvariantStore,
    private val orchestrator: AgentOrchestrator,
) {
    fun runFullDemo() {
        storage.reset()
        invariantStore.ensureSeed(reset = true)
        println("Day 15: Контролируемые переходы состояний")
        println(orchestrator.start("Сделай план реализации CLI-команды экспорта CSV для Kotlin-агента."))
        println("=== SKIP ATTEMPT ===")
        println("User: Пропусти план, сразу делай реализацию.")
        println(orchestrator.tryTransition("execution"))
        println("=== APPROVE ===")
        println(orchestrator.approve())
        println("=== HISTORY ===")
        println(orchestrator.history())
    }

    fun runTransitionTests() {
        storage.reset()
        invariantStore.ensureSeed(reset = true)
        println("Day 15: transition tests")
        val now = Instant.now().toString()
        val base = TaskState(createdAt = now, updatedAt = now, userRequest = "test")

        forceAndTry(base.copy(currentState = "intake"), "execution", "intake -> execution")
        forceAndTry(base.copy(currentState = "planning"), "execution", "planning -> execution without approval")
        forceAndTry(base.copy(currentState = "execution", approvedPlan = true), "done", "execution -> done")
        forceAndTry(base.copy(currentState = "validation", approvedPlan = true, validationPassed = false), "done", "validation -> done while invalid")
        forceAndTry(base.copy(currentState = "validation", approvedPlan = true, validationPassed = false), "execution", "validation -> execution for fix")
        forceAndTry(base.copy(currentState = "done", approvedPlan = true, validationPassed = true), "execution", "done -> execution")
    }

    fun runPauseResumeDemo() {
        storage.reset()
        invariantStore.ensureSeed(reset = true)
        println("Day 15: pause/resume demo")
        println(orchestrator.start("Сделай план реализации CLI-команды экспорта CSV для Kotlin-агента."))
        println("=== PAUSE ===")
        println(orchestrator.pause())
        println("=== SIMULATED RESTART ===")
        println(orchestrator.status())
        println("=== RESUME ===")
        println(orchestrator.resume())
    }

    private fun forceAndTry(state: TaskState, target: String, label: String) {
        println()
        println("=== TEST: $label ===")
        orchestrator.forceStateForTest(state)
        println(orchestrator.tryTransition(target))
    }
}
