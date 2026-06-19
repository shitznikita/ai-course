import kotlinx.serialization.encodeToString
import java.time.Instant

class AgentOrchestrator(
    private val storage: TaskStateStorage,
    private val stateMachine: TaskStateMachine,
    private val intakeAgent: IntakeAgent,
    private val planningAgent: PlanningAgent,
    private val executionAgent: ExecutionAgent,
    private val validationAgent: ValidationAgent,
) {
    fun start(userRequest: String): String {
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
        val intake = intakeAgent.run(userRequest)
        state = state.copy(
            title = intake.artifact.taskTitle,
            artifacts = state.artifacts.copy(taskBrief = intake.artifact),
        )
        storage.save(state)

        val log = StringBuilder()
        log.appendLine(renderState(state))
        log.appendLine(renderArtifact("task_brief", intake.valid, appJson.encodeToString(intake.artifact)))
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

        return log.append(runPlanning(state)).toString()
    }

    fun approve(): String {
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

        val execution = executionAgent.run(state)
        state = state.copy(artifacts = state.artifacts.copy(draftResult = execution.artifact))
        storage.save(state)
        log.appendLine(renderArtifact("draft_result", execution.valid, execution.artifact.content.take(1600)))

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

        val validation = validationAgent.run(state)
        state = state.copy(artifacts = state.artifacts.copy(validationReport = validation.artifact))
        storage.save(state)
        log.appendLine(renderArtifact("validation_report", validation.valid, appJson.encodeToString(validation.artifact)))

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
            "continue_${previous}",
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
        return renderTransition(back) + "\n" + runPlanning(back.state)
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

    private fun runPlanning(state: TaskState): String {
        val planning = planningAgent.run(state.artifacts.taskBrief ?: TaskBrief())
        val planned = state.copy(artifacts = state.artifacts.copy(taskPlan = planning.artifact))
        storage.save(planned)
        val wait = stateMachine.transition(
            planned,
            "waiting_for_approval",
            "task_plan.requires_user_approval=true",
            "wait_for_plan_approval",
            "user_approval",
            "waiting for user approval",
        )
        storage.save(wait.state)
        return buildString {
            appendLine(renderArtifact("task_plan", planning.valid, appJson.encodeToString(planning.artifact)))
            appendLine(renderTransition(wait))
            appendLine(renderPaused(wait.state))
        }
    }

    private fun renderState(state: TaskState): String = """
        |=== TASK STATE ===
        |Task ID: ${state.taskId}
        |Status: ${state.status}
        |Current step: ${state.currentStep}
        |Expected action: ${state.expectedAction}
    """.trimMargin()

    private fun renderArtifact(name: String, valid: Boolean, content: String): String = """
        |=== ARTIFACT CREATED ===
        |Artifact: $name
        |Valid: $valid
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
