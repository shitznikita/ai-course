import kotlinx.serialization.encodeToString
import java.time.Instant

class AgentOrchestrator(
    private val storage: TaskStateStorage,
    private val invariantStore: InvariantStore,
    private val invariantChecker: InvariantChecker,
    private val stateMachine: TaskStateMachine,
    private val planningSwarm: PlanningAgentsSwarm,
    private val executionAgent: ExecutionAgent,
    private val validationAgent: ValidationAgent,
) {
    fun start(userRequest: String): String {
        invariantStore.ensureSeed()
        val now = Instant.now().toString()
        var state = TaskState(
            title = "Реализовать CLI-команду экспорта CSV",
            currentState = "intake",
            currentStep = "collect_task",
            expectedAction = "llm_generation",
            userRequest = userRequest,
            createdAt = now,
            updatedAt = now,
            artifacts = TaskArtifacts(taskBrief = "Task accepted: $userRequest"),
        )
        storage.save(state)
        val log = StringBuilder()
        log.appendLine(renderCurrentState(state))

        val toPlanning = stateMachine.transition(state, "planning", "task_brief_ready", "build_plan_with_swarm", "llm_generation")
        state = toPlanning.state
        storage.save(state)
        log.appendLine(renderTransition(toPlanning))

        return log.append(runPlanning(state)).toString()
    }

    fun approve(): String {
        var state = storage.load() ?: return "No saved task."
        if (state.currentState != "waiting_for_approval") return "Cannot approve from ${state.currentState}"
        state = state.copy(approvedPlan = true, expectedAction = "llm_generation")
        storage.save(state)

        val toExecution = stateMachine.transition(state, "execution", "user approved plan", "execute_approved_plan", "llm_generation")
        state = toExecution.state
        storage.save(state)
        val log = StringBuilder()
        log.appendLine(renderTransition(toExecution))

        val invariants = invariantStore.active()
        val execution = executionAgent.run(state, invariants)
        val executionCheck = invariantChecker.check(execution, invariants)
        state = state.copy(artifacts = state.artifacts.copy(executionResult = execution))
        storage.save(state)
        log.appendLine("=== EXECUTION RESULT ===")
        log.appendLine(execution.take(1800))
        log.appendLine("=== INVARIANT CHECK ===")
        log.appendLine(executionCheck.render())
        if (!executionCheck.valid) {
            val back = stateMachine.transition(state, "planning", "execution_result violated invariants", "revise_plan", "llm_generation")
            storage.save(back.state)
            return log.appendLine(renderTransition(back)).toString()
        }

        val toValidation = stateMachine.transition(state, "validation", "execution_result_ready", "validate_result", "validation")
        state = toValidation.state
        storage.save(state)
        log.appendLine(renderTransition(toValidation))

        val validation = validationAgent.run(state, invariants)
        val validationCheck = invariantChecker.check(validation, invariants)
        val passed = validationCheck.valid
        state = state.copy(
            validationPassed = passed,
            artifacts = state.artifacts.copy(validationReport = validation),
        )
        storage.save(state)
        log.appendLine("=== VALIDATION REPORT ===")
        log.appendLine(validation.take(1800))
        log.appendLine("=== VALIDATION INVARIANT CHECK ===")
        log.appendLine(validationCheck.render())

        val nextState = if (passed) "done" else "execution"
        val finalTransition = stateMachine.transition(
            state,
            nextState,
            if (passed) "validation_passed=true" else "validation_found_fixable_issues",
            if (passed) "task_completed" else "fix_execution_result",
            if (passed) "none" else "llm_generation",
        )
        storage.save(finalTransition.state)
        log.appendLine(renderTransition(finalTransition))
        return log.toString()
    }

    fun tryTransition(to: String): String {
        val state = storage.load() ?: return "No saved task."
        val result = stateMachine.transition(state, to, "debug try-transition", "debug_try_$to", "debug")
        storage.save(result.state)
        return renderTransition(result)
    }

    fun pause(): String {
        val state = storage.load() ?: return "No saved task."
        val result = stateMachine.transition(state, "paused", "user requested pause", "paused_by_user", "user_input", "user requested pause")
        storage.save(result.state)
        return renderTransition(result) + "\n" + renderCurrentState(result.state)
    }

    fun resume(): String {
        val state = storage.load() ?: return "No saved task."
        if (state.currentState != "paused") return "Current state is ${state.currentState}; nothing to resume."
        val target = state.previousState ?: "intake"
        val result = stateMachine.transition(state, target, "resume from pause", "continue_$target", if (target == "waiting_for_approval") "user_approval" else "llm_generation")
        storage.save(result.state)
        return renderTransition(result) + "\n" + renderCurrentState(result.state)
    }

    fun reject(reason: String): String {
        val state = storage.load() ?: return "No saved task."
        if (state.currentState != "waiting_for_approval") return "Reject allowed only from waiting_for_approval."
        val back = stateMachine.transition(state, "planning", "user rejected plan: $reason", "revise_plan_with_swarm", "llm_generation")
        storage.save(back.state)
        return renderTransition(back) + "\n" + runPlanning(back.state)
    }

    fun reset(): String {
        storage.reset()
        return "Task state reset."
    }

    fun status(): String = storage.load()?.let { renderCurrentState(it) } ?: "No task state yet."

    fun stateJson(): String = storage.render()

    fun history(): String = storage.load()?.transitionHistory?.joinToString("\n") {
        "${it.timestamp}: ${it.from} -> ${it.to}, allowed=${it.allowed}, guards=${it.guardsPassed}, reason=${it.reason}"
    } ?: "No task state yet."

    fun forceStateForTest(state: TaskState) {
        storage.save(state)
    }

    private fun runPlanning(state: TaskState): String {
        val invariants = invariantStore.active()
        val swarm = planningSwarm.run(state.userRequest, invariants)
        val planCheck = invariantChecker.check(swarm.finalPlan, invariants)
        val planned = state.copy(
            artifacts = state.artifacts.copy(
                productPlan = swarm.productPlan,
                techPlan = swarm.techPlan,
                riskPlan = swarm.riskPlan,
                finalPlan = swarm.finalPlan,
            ),
        )
        storage.save(planned)
        val log = StringBuilder()
        log.appendLine("=== SWARM RESULTS ===")
        log.appendLine("ProductPlannerAgent: ${swarm.productPlan.take(600)}")
        log.appendLine("TechPlannerAgent: ${swarm.techPlan.take(600)}")
        log.appendLine("RiskPlannerAgent: ${swarm.riskPlan.take(600)}")
        log.appendLine("=== ORCHESTRATOR DECISION ===")
        log.appendLine(swarm.finalPlan.take(1200))
        log.appendLine("=== PLAN INVARIANT CHECK ===")
        log.appendLine(planCheck.render())
        if (!planCheck.valid) {
            val paused = stateMachine.transition(planned, "paused", "plan violated invariants", "fix_plan_invariants", "user_input", "plan violated invariants")
            storage.save(paused.state)
            return log.appendLine(renderTransition(paused)).toString()
        }
        val waiting = stateMachine.transition(planned, "waiting_for_approval", "plan_created_requires_approval", "wait_for_plan_approval", "user_approval")
        storage.save(waiting.state)
        log.appendLine(renderTransition(waiting))
        log.appendLine(renderCurrentState(waiting.state))
        return log.toString()
    }

    private fun renderCurrentState(state: TaskState): String = """
        |=== CURRENT STATE ===
        |State: ${state.currentState}
        |Step: ${state.currentStep}
        |Expected action: ${state.expectedAction}
        |approvedPlan: ${state.approvedPlan}
        |validationPassed: ${state.validationPassed}
    """.trimMargin()

    private fun renderTransition(result: TransitionResult): String = """
        |=== TRANSITION CHECK ===
        |From: ${result.from}
        |To: ${result.to}
        |Allowed: ${result.check.allowed}
        |Guards passed: ${result.check.guardsPassed}
        |Reason: ${result.check.reason}
        |=== TRANSITION ${if (result.check.allowed && result.check.guardsPassed) "APPLIED" else "DENIED"} ===
        |Current state: ${result.state.currentState}
    """.trimMargin()
}
