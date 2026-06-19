class TransitionValidator {
    fun validate(from: String, to: String, state: TaskState): TransitionCheck {
        if (to == "execution" && !state.approvedPlan) {
            return TransitionCheck(
                allowed = true,
                guardsPassed = false,
                reason = "execution requires approved_plan=true",
            )
        }
        if (to == "done" && !state.validationPassed) {
            return TransitionCheck(
                allowed = true,
                guardsPassed = false,
                reason = "done requires validation_passed=true",
            )
        }
        if (from == "done" || from == "cancelled") {
            return TransitionCheck(false, false, "$from is terminal")
        }
        return TransitionCheck(true, true, "guards passed")
    }
}
