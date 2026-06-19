import kotlinx.serialization.encodeToString
import java.time.Instant

class AgentOrchestrator(
    private val config: AppConfig,
    private val storage: TaskStateStorage,
    private val stateMachine: TaskStateMachine,
    private val invariantStore: InvariantStore,
    private val checker: InvariantChecker,
    private val judge: LLMInvariantJudge,
    private val intakeAgent: IntakeAgent,
    private val planningAgent: PlanningAgent,
    private val executionAgent: ExecutionAgent,
    private val validationAgent: ValidationAgent,
) {
    fun start(userRequest: String): String {
        val invariants = activeInvariants()
        val deterministicAudit = checker.check(userRequest, "user_request", invariants)
        val llmAudit = judgeRequest(userRequest, invariants)
        val now = Instant.now().toString()
        var state = TaskState(
            title = "Новая задача",
            status = "intake",
            currentStep = "collect_inputs",
            expectedAction = "llm_generation",
            createdAt = now,
            updatedAt = now,
            userRequest = userRequest,
        )
        storage.save(state)

        val intake = intakeAgent.run(userRequest, invariants)
        state = state.copy(
            title = intake.artifact.taskTitle,
            artifacts = state.artifacts.copy(taskBrief = intake.artifact),
        )
        storage.save(state)

        val log = StringBuilder()
        log.appendLine(renderInvariantAudit(invariants, deterministicAudit, llmAudit, userRequest))
        log.appendLine(renderState(state))
        log.appendLine(renderArtifact("task_brief", intake, appJson.encodeToString(intake.artifact)))
        if (!intake.responseValidation.valid) {
            val paused = pauseForInvariantViolation(state, "intake", intake.responseValidation)
            log.appendLine(renderTransition(paused))
            log.appendLine(renderPaused(paused.state))
            return log.toString()
        }
        if (!intake.artifact.readyForPlanning) {
            val paused = stateMachine.transition(
                state,
                "paused",
                "missing required inputs: ${intake.artifact.missingInputs.joinToString()}",
                "ask_missing_inputs",
                "user_input",
                "missing required fields: ${intake.artifact.missingInputs.joinToString()}",
            )
            storage.save(paused.state)
            log.appendLine(renderTransition(paused))
            log.appendLine(renderPaused(paused.state))
            return log.toString()
        }

        val toPlanning = stateMachine.transition(
            state,
            "planning",
            "task_brief.ready_for_planning=true",
            "create_plan",
            "llm_generation",
        )
        state = toPlanning.state
        storage.save(state)
        log.appendLine(renderTransition(toPlanning))

        return log.append(runPlanning(state, invariants)).toString()
    }

    fun approve(): String {
        val invariants = activeInvariants()
        var state = storage.load() ?: return "No saved task. Start with a user request."
        if (state.status != "waiting_for_approval") {
            return "Cannot approve now. Current status: ${state.status}"
        }
        val toExecution = stateMachine.transition(
            state,
            "execution",
            "user approved task_plan",
            "create_draft_result",
            "llm_generation",
        )
        state = toExecution.state
        storage.save(state)
        val log = StringBuilder()
        log.appendLine(renderTransition(toExecution))

        val execution = executionAgent.run(state, invariants)
        state = state.copy(artifacts = state.artifacts.copy(draftResult = execution.artifact))
        storage.save(state)
        log.appendLine(renderArtifact("draft_result", execution, execution.artifact.content.take(1600)))
        if (!execution.responseValidation.valid) {
            val paused = pauseForInvariantViolation(state, "execution", execution.responseValidation)
            log.appendLine(renderTransition(paused))
            log.appendLine(renderPaused(paused.state))
            return log.toString()
        }

        val toValidation = stateMachine.transition(
            state,
            "validation",
            "draft_result.ready_for_validation=true",
            "validate_result",
            "validation",
        )
        state = toValidation.state
        storage.save(state)
        log.appendLine(renderTransition(toValidation))

        val validation = validationAgent.run(state, invariants)
        state = state.copy(artifacts = state.artifacts.copy(validationReport = validation.artifact))
        storage.save(state)
        log.appendLine(renderArtifact("validation_report", validation, appJson.encodeToString(validation.artifact)))
        if (!validation.responseValidation.valid) {
            val paused = pauseForInvariantViolation(state, "validation", validation.responseValidation)
            log.appendLine(renderTransition(paused))
            log.appendLine(renderPaused(paused.state))
            return log.toString()
        }

        val toDone = stateMachine.transition(
            state,
            "done",
            "validation_report.ready_for_done=true",
            "task_completed",
            "none",
        )
        storage.save(toDone.state)
        log.appendLine(renderTransition(toDone))
        log.appendLine("=== FINAL RESULT ===")
        log.appendLine(toDone.state.artifacts.draftResult?.content ?: "No draft result")
        return log.toString()
    }

    fun resume(extraInput: String? = null): String {
        val state = storage.load() ?: return "No saved state."
        if (state.status != "paused") {
            return "Loaded state from task_state.json. Current status: ${state.status}. Nothing to resume."
        }
        if (!extraInput.isNullOrBlank()) {
            return start(state.userRequest + " " + extraInput)
        }
        val previous = state.previousStatus ?: "intake"
        val resumed = stateMachine.transition(
            state,
            previous,
            "resume from paused state",
            "continue_$previous",
            "llm_generation",
        )
        storage.save(resumed.state)
        return renderTransition(resumed)
    }

    fun pause(): String {
        val state = storage.load() ?: return "No saved task."
        val paused = stateMachine.transition(state, "paused", "user requested pause", "paused_by_user", "user_input", "user requested pause")
        storage.save(paused.state)
        return renderTransition(paused) + "\n" + renderPaused(paused.state)
    }

    fun reject(comment: String): String {
        val state = storage.load() ?: return "No saved task."
        if (state.status != "waiting_for_approval") return "Reject is available only in waiting_for_approval."
        val back = stateMachine.transition(state, "planning", "user rejected plan: $comment", "revise_plan", "llm_generation")
        storage.save(back.state)
        return renderTransition(back) + "\n" + runPlanning(back.state, activeInvariants())
    }

    fun reset(): String {
        storage.reset()
        return "Task state reset."
    }

    fun status(): String = storage.load()?.let { renderState(it) } ?: "No task state yet."

    fun stateJson(): String = storage.render()

    fun history(): String = storage.load()?.history?.joinToString("\n") {
        "${it.timestamp}: ${it.from} -> ${it.to} (${it.reason})"
    } ?: "No task state yet."

    private fun runPlanning(state: TaskState, invariants: List<Invariant>): String {
        val planning = planningAgent.run(state.artifacts.taskBrief ?: TaskBrief(), invariants)
        val planned = state.copy(artifacts = state.artifacts.copy(taskPlan = planning.artifact))
        storage.save(planned)
        val log = StringBuilder()
        log.appendLine(renderArtifact("task_plan", planning, appJson.encodeToString(planning.artifact)))
        if (!planning.responseValidation.valid) {
            val paused = pauseForInvariantViolation(planned, "planning", planning.responseValidation)
            log.appendLine(renderTransition(paused))
            log.appendLine(renderPaused(paused.state))
            return log.toString()
        }
        val wait = stateMachine.transition(
            planned,
            "waiting_for_approval",
            "task_plan.requires_user_approval=true",
            "wait_for_plan_approval",
            "user_approval",
            "waiting for user approval",
        )
        storage.save(wait.state)
        log.appendLine(renderTransition(wait))
        log.appendLine(renderPaused(wait.state))
        return log.toString()
    }

    private fun activeInvariants(): List<Invariant> {
        invariantStore.ensureSeed()
        return invariantStore.active()
    }

    private fun judgeRequest(userRequest: String, invariants: List<Invariant>): ValidationResult {
        return if (config.checkerMode in setOf("llm", "combined")) {
            judge.judge(userRequest, "user_request", invariants)
        } else {
            ValidationResult(valid = true)
        }
    }

    private fun pauseForInvariantViolation(state: TaskState, stage: String, validation: ValidationResult): TransitionResult {
        val reason = validation.violations.joinToString { it.invariantId }
        val paused = stateMachine.transition(
            state,
            "paused",
            "assistant_response_invariant_violation at $stage: $reason",
            "fix_invariant_violation",
            "user_input",
            "assistant_response_invariant_violation at $stage: $reason",
        )
        storage.save(paused.state)
        return paused
    }

    private fun renderInvariantAudit(
        invariants: List<Invariant>,
        deterministic: ValidationResult,
        llmAudit: ValidationResult,
        userRequest: String,
    ): String = buildString {
        appendLine("=== INVARIANT AUDIT ===")
        appendLine("Active invariants: ${invariants.joinToString { it.id }}")
        appendLine("Raw user request sent to stage LLM: true")
        appendLine("User request:")
        appendLine(userRequest)
        appendLine("Deterministic request check:")
        appendLine(deterministic.render())
        appendLine("LLM judge request audit called: ${config.checkerMode in setOf("llm", "combined")}")
        appendLine(llmAudit.render())
    }.trimEnd()

    private fun renderState(state: TaskState): String = """
        |=== TASK STATE ===
        |Task ID: ${state.taskId}
        |Status: ${state.status}
        |Current step: ${state.currentStep}
        |Expected action: ${state.expectedAction}
    """.trimMargin()

    private fun <T> renderArtifact(name: String, stage: StageResult<T>, content: String): String = """
        |=== ARTIFACT CREATED ===
        |Artifact: $name
        |Stage LLM called: true
        |Retry used: ${stage.retryUsed}
        |Stage valid: ${stage.valid}
        |Response validation:
        |${stage.responseValidation.render()}
        |LLM response:
        |${stage.llmNotes.take(1600)}
        |Artifact content:
        |$content
    """.trimMargin()

    private fun renderTransition(result: TransitionResult): String = """
        |=== TRANSITION ===
        |Allowed: ${result.allowed}
        |${result.message}
    """.trimMargin()

    private fun renderPaused(state: TaskState): String = """
        |=== PAUSED ===
        |Reason: ${state.pausedReason ?: "waiting for user approval"}
        |Expected action: ${state.expectedAction}
    """.trimMargin()
}
