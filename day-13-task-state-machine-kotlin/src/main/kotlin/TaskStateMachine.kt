import java.time.Instant

class TaskStateMachine {
    private val allowedTransitions = mapOf(
        "intake" to setOf("planning", "paused", "cancelled"),
        "planning" to setOf("waiting_for_approval", "execution", "paused", "cancelled"),
        "waiting_for_approval" to setOf("execution", "planning", "paused", "cancelled"),
        "execution" to setOf("validation", "planning", "paused", "cancelled"),
        "validation" to setOf("done", "execution", "planning", "paused", "cancelled"),
        "paused" to setOf("intake", "planning", "execution", "validation", "cancelled"),
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
        val from = state.status
        val allowed = allowedTransitions[from]?.contains(to) == true
        if (!allowed) {
            return TransitionResult(false, state, "Transition denied: $from -> $to is not allowed")
        }
        val now = Instant.now().toString()
        val next = state.copy(
            status = to,
            previousStatus = from,
            currentStep = currentStep,
            expectedAction = expectedAction,
            updatedAt = now,
            pausedReason = pausedReason,
            history = state.history + TransitionRecord(now, from, to, reason),
        )
        return TransitionResult(true, next, "Transition applied: $from -> $to ($reason)")
    }

    fun allowedTable(): String = allowedTransitions.entries.joinToString("\n") {
        "${it.key} -> ${if (it.value.isEmpty()) "[]" else it.value.joinToString(", ")}"
    }
}
