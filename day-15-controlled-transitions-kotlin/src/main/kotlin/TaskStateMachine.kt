import java.time.Instant

class TaskStateMachine(private val transitionValidator: TransitionValidator) {
    private val allowedTransitions = mapOf(
        "intake" to setOf("planning", "paused", "cancelled"),
        "planning" to setOf("waiting_for_approval", "paused", "cancelled"),
        "waiting_for_approval" to setOf("execution", "planning", "paused", "cancelled"),
        "execution" to setOf("validation", "planning", "paused", "cancelled"),
        "validation" to setOf("done", "execution", "planning", "paused", "cancelled"),
        "paused" to setOf("intake", "planning", "waiting_for_approval", "execution", "validation", "cancelled"),
        "done" to emptySet(),
        "cancelled" to emptySet(),
    )

    fun transition(
        state: TaskState,
        to: String,
        reason: String,
        currentStep: String,
        expectedAction: String,
        pausedReason: String? = null,
    ): TransitionResult {
        val from = state.currentState
        val allowed = allowedTransitions[from]?.contains(to) == true
        val guardCheck = if (allowed) transitionValidator.validate(from, to, state) else TransitionCheck(false, false, "$from -> $to is not in allowed transitions")
        val now = Instant.now().toString()
        val record = TransitionRecord(
            timestamp = now,
            from = from,
            to = to,
            allowed = allowed,
            guardsPassed = guardCheck.guardsPassed,
            reason = if (allowed && guardCheck.guardsPassed) reason else guardCheck.reason,
        )
        val next = if (allowed && guardCheck.guardsPassed) {
            state.copy(
                currentState = to,
                previousState = from,
                currentStep = currentStep,
                expectedAction = expectedAction,
                pausedReason = pausedReason,
                updatedAt = now,
                transitionHistory = state.transitionHistory + record,
            )
        } else {
            state.copy(updatedAt = now, transitionHistory = state.transitionHistory + record)
        }
        return TransitionResult(next, TransitionCheck(allowed, guardCheck.guardsPassed, record.reason), from, to)
    }

    fun table(): String = allowedTransitions.entries.joinToString("\n") {
        "${it.key} -> ${if (it.value.isEmpty()) "[]" else it.value.joinToString(", ")}"
    }
}
