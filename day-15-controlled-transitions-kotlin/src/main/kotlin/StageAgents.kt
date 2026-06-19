interface TaskStateAgent {
    val agentName: String
    val ownedState: String
    val artifactName: String

    fun run(state: TaskState, invariants: List<Invariant>): StateAgentResult
}

private fun callStateAgent(
    agentName: String,
    ownedState: String,
    artifactName: String,
    state: TaskState,
    invariants: List<Invariant>,
    llmClient: LlmClient,
    promptBuilder: PromptBuilder,
    context: String,
): StateAgentResult {
    val response = llmClient.chat(
        promptBuilder.stateAgent(
            agentName = agentName,
            ownedState = ownedState,
            task = state.userRequest,
            invariants = invariants,
            context = context,
        ),
    )
    return StateAgentResult(
        agentName = agentName,
        ownedState = ownedState,
        artifactName = artifactName,
        content = response.warningOrError ?: response.content.ifBlank { "$agentName produced empty result." },
    )
}

class IntakeAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) : TaskStateAgent {
    override val agentName = "IntakeStateAgent"
    override val ownedState = "intake"
    override val artifactName = "taskBrief"

    override fun run(state: TaskState, invariants: List<Invariant>): StateAgentResult = callStateAgent(
        agentName = agentName,
        ownedState = ownedState,
        artifactName = artifactName,
        state = state,
        invariants = invariants,
        llmClient = llmClient,
        promptBuilder = promptBuilder,
        context = """
            Own only the intake slice:
            - extract user request;
            - define task title and task brief;
            - decide whether task is ready for planning.
            For demo, choose explicit conservative defaults for missing details and set ready_for_planning=true.
            Do not create implementation plan.
        """.trimIndent(),
    )
}

class PlanningAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) : TaskStateAgent {
    override val agentName = "PlanningStateAgent"
    override val ownedState = "planning"
    override val artifactName = "finalPlan"

    override fun run(state: TaskState, invariants: List<Invariant>): StateAgentResult = callStateAgent(
        agentName = agentName,
        ownedState = ownedState,
        artifactName = artifactName,
        state = state,
        invariants = invariants,
        llmClient = llmClient,
        promptBuilder = promptBuilder,
        context = """
            Own only the planning slice.
            Intake brief:
            ${state.artifacts.taskBrief}

            Produce one approved-ready plan with steps, risks, acceptance criteria, and demo notes.
            Use conservative demo defaults instead of blocking on open questions.
            Mark assumptions explicitly, but keep the plan executable after user approval.
            Do not execute the plan. Do not validate final result.
        """.trimIndent(),
    )
}

class ApprovalAgent : TaskStateAgent {
    override val agentName = "ApprovalStateAgent"
    override val ownedState = "waiting_for_approval"
    override val artifactName = "approvalSummary"

    override fun run(state: TaskState, invariants: List<Invariant>): StateAgentResult {
        val content = """
            Approval gate owns waiting_for_approval.
            Plan is ready, but execution is blocked until user calls /approve.
            approvedPlan=${state.approvedPlan}
            expectedAction=${state.expectedAction}
            Active invariants: ${invariants.joinToString { it.id }}
        """.trimIndent()
        return StateAgentResult(agentName, ownedState, artifactName, content)
    }
}

class ExecutionAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) : TaskStateAgent {
    override val agentName = "ExecutionStateAgent"
    override val ownedState = "execution"
    override val artifactName = "executionResult"

    override fun run(state: TaskState, invariants: List<Invariant>): StateAgentResult = callStateAgent(
        agentName = agentName,
        ownedState = ownedState,
        artifactName = artifactName,
        state = state,
        invariants = invariants,
        llmClient = llmClient,
        promptBuilder = promptBuilder,
        context = """
            Own only the execution slice.
            Approved plan:
            ${state.artifacts.finalPlan}

            Create execution result from approved plan.
            Treat the plan as approved and executable.
            Produce a completed demo-ready execution artifact, not a pending/questions list.
            Do not mark validation passed. Do not move to done.
        """.trimIndent(),
    )
}

class ValidationAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) : TaskStateAgent {
    override val agentName = "ValidationStateAgent"
    override val ownedState = "validation"
    override val artifactName = "validationReport"

    override fun run(state: TaskState, invariants: List<Invariant>): StateAgentResult = callStateAgent(
        agentName = agentName,
        ownedState = ownedState,
        artifactName = artifactName,
        state = state,
        invariants = invariants,
        llmClient = llmClient,
        promptBuilder = promptBuilder,
        context = """
            Own only the validation slice.
            Execution result:
            ${state.artifacts.executionResult}

            Check lifecycle, invariants, and acceptance criteria.
            If execution artifact is complete enough for demo and no invariants are violated, start output with PASS.
            Start output with FAIL only for real blocking issues in the execution artifact.
            Do not fail only because optional product details could be refined later.
            Output PASS/FAIL without repeating forbidden keywords in PASS text.
        """.trimIndent(),
    )
}
