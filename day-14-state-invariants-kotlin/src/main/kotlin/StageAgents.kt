import kotlinx.serialization.encodeToString

private data class SafeLlmResult(
    val content: String,
    val validation: ValidationResult,
    val retryUsed: Boolean,
)

private fun callStageLlm(
    stage: String,
    messages: List<ChatMessage>,
    invariants: List<Invariant>,
    llmClient: LlmClient,
    promptBuilder: PromptBuilder,
    checker: InvariantChecker,
): SafeLlmResult {
    val first = llmClient.chat(messages)
    val firstAnswer = first.warningOrError ?: first.content
    val firstCheck = checker.check(firstAnswer, "assistant_response:$stage", invariants)
    if (firstCheck.valid) {
        return SafeLlmResult(firstAnswer, firstCheck, retryUsed = false)
    }

    val retry = llmClient.chat(promptBuilder.retry(stage, firstAnswer, firstCheck, invariants))
    val retryAnswer = retry.warningOrError ?: retry.content
    val retryCheck = checker.check(retryAnswer, "assistant_response_retry:$stage", invariants)
    return SafeLlmResult(retryAnswer, retryCheck, retryUsed = true)
}

class IntakeAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val checker: InvariantChecker,
) {
    fun run(userRequest: String, invariants: List<Invariant>): StageResult<TaskBrief> {
        val lower = userRequest.lowercase()
        val missing = buildList {
            if (!lower.contains("android") && !lower.contains("ios")) add("platform")
            if (!lower.contains("срок") && !lower.contains("недел")) add("deadline")
            if (!lower.contains("финанс") && !lower.contains("csv") && !lower.contains("экспорт")) add("core features")
        }
        val llm = callStageLlm(
            stage = "intake",
            messages = promptBuilder.stagePrompt(
                "intake",
                "Extract known inputs and missing inputs for mobile MVP requirements. If request violates invariants, explain safe refusal in this stage output.",
                userRequest,
                invariants,
            ),
            invariants = invariants,
            llmClient = llmClient,
            promptBuilder = promptBuilder,
            checker = checker,
        )
        val ready = missing.isEmpty()
        val brief = TaskBrief(
            taskTitle = if (ready) "ТЗ для MVP приложения учета финансов" else "Неполное ТЗ для приложения",
            goal = "Собрать требования для MVP мобильного приложения",
            knownInputs = userRequest.split(".", ",").map { it.trim() }.filter { it.isNotBlank() },
            missingInputs = missing,
            readyForPlanning = ready,
        )
        return StageResult(brief, llm.content, llm.validation.valid, llm.validation, llm.retryUsed)
    }
}

class PlanningAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val checker: InvariantChecker,
) {
    fun run(brief: TaskBrief, invariants: List<Invariant>): StageResult<TaskPlan> {
        val llm = callStageLlm(
            stage = "planning",
            messages = promptBuilder.stagePrompt(
                "planning",
                "Create task_plan with steps, artifacts, and user approval requirement.",
                appJson.encodeToString(brief),
                invariants,
            ),
            invariants = invariants,
            llmClient = llmClient,
            promptBuilder = promptBuilder,
            checker = checker,
        )
        val plan = TaskPlan(
            steps = listOf(
                "сформировать цели MVP",
                "описать пользователей",
                "описать функциональные требования",
                "описать ограничения и инварианты",
                "сформировать итоговое ТЗ",
            ),
            requiresUserApproval = true,
            readyForExecution = false,
            notes = llm.content.take(1600),
        )
        return StageResult(plan, llm.content, plan.steps.isNotEmpty() && llm.validation.valid, llm.validation, llm.retryUsed)
    }
}

class ExecutionAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val checker: InvariantChecker,
) {
    fun run(state: TaskState, invariants: List<Invariant>): StageResult<DraftResult> {
        val llm = callStageLlm(
            stage = "execution",
            messages = promptBuilder.stagePrompt(
                "execution",
                "Create draft_result: concise requirements document for MVP.",
                appJson.encodeToString(state),
                invariants,
            ),
            invariants = invariants,
            llmClient = llmClient,
            promptBuilder = promptBuilder,
            checker = checker,
        )
        val draft = DraftResult(
            content = llm.content.ifBlank {
                "Черновик ТЗ: Android MVP учета финансов, локальное хранение Room, экспорт CSV, срок 3 недели."
            },
            completedSteps = listOf("цели", "пользователи", "функции", "ограничения", "итоговое ТЗ"),
            readyForValidation = llm.validation.valid,
        )
        return StageResult(draft, llm.content, draft.content.isNotBlank() && llm.validation.valid, llm.validation, llm.retryUsed)
    }
}

class ValidationAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val checker: InvariantChecker,
) {
    fun run(state: TaskState, invariants: List<Invariant>): StageResult<ValidationReport> {
        val llm = callStageLlm(
            stage = "validation",
            messages = promptBuilder.stagePrompt(
                "validation",
                "Validate draft_result against task_brief, task_plan, and active invariants. Return issues and recommendations.",
                appJson.encodeToString(state),
                invariants,
            ),
            invariants = invariants,
            llmClient = llmClient,
            promptBuilder = promptBuilder,
            checker = checker,
        )
        val report = ValidationReport(
            isValid = llm.validation.valid,
            issues = if (llm.validation.valid) emptyList() else llm.validation.violations.map { it.message },
            recommendations = listOf("Перед разработкой уточнить необходимость авторизации без серверной части."),
            readyForDone = llm.validation.valid,
        )
        return StageResult(report, llm.content, report.readyForDone, llm.validation, llm.retryUsed)
    }
}
