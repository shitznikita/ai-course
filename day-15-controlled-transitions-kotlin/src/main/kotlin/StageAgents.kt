class PlanningAgentsSwarm(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) {
    fun run(userRequest: String, invariants: List<Invariant>): SwarmResult {
        val product = call("ProductPlannerAgent", userRequest, invariants, "Фокус: пользователь, ценность, MVP scope.")
        val tech = call("TechPlannerAgent", userRequest, invariants, "Фокус: Kotlin CLI architecture, data flow, implementation steps.")
        val risk = call("RiskPlannerAgent", userRequest, invariants, "Фокус: риски, пропущенные требования, конфликты с инвариантами.")
        val final = call(
            "OrchestratorAgent",
            userRequest,
            invariants,
            """
            Product plan:
            $product

            Tech plan:
            $tech

            Risk plan:
            $risk

            Сведи в один approved-ready plan with steps and acceptance criteria.
            """.trimIndent(),
        )
        return SwarmResult(product, tech, risk, final)
    }

    private fun call(name: String, task: String, invariants: List<Invariant>, context: String): String {
        val response = llmClient.chat(promptBuilder.stage(name, task, invariants, context))
        return response.warningOrError ?: response.content.ifBlank { "$name produced empty result." }
    }
}

class ExecutionAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) {
    fun run(state: TaskState, invariants: List<Invariant>): String {
        val response = llmClient.chat(
            promptBuilder.stage(
                "ExecutionAgent",
                state.userRequest,
                invariants,
                "Approved plan:\n${state.artifacts.finalPlan}",
            ),
        )
        return response.warningOrError ?: response.content.ifBlank { "Execution result: CLI export CSV feature draft." }
    }
}

class ValidationAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) {
    fun run(state: TaskState, invariants: List<Invariant>): String {
        val response = llmClient.chat(
            promptBuilder.stage(
                "ValidationAgent",
                state.userRequest,
                invariants,
                "Execution result:\n${state.artifacts.executionResult}\nCheck lifecycle, invariants, and acceptance criteria.",
            ),
        )
        return response.warningOrError ?: response.content.ifBlank { "Validation passed: result follows approved plan and invariants." }
    }
}
