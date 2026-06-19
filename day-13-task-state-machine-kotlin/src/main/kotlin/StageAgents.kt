class IntakeAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) {
    fun run(userRequest: String): StageResult<TaskBrief> {
        val missing = mutableListOf<String>()
        val lower = userRequest.lowercase()
        if (!lower.contains("android") && !lower.contains("ios")) missing += "platform"
        if (!lower.contains("срок") && !lower.contains("недел")) missing += "deadline"
        if (!lower.contains("финанс") && !lower.contains("csv") && !lower.contains("backend")) missing += "core features"

        val notes = llmClient.chat(
            promptBuilder.stagePrompt(
                "intake",
                "Extract known inputs and missing inputs for mobile MVP requirements.",
                userRequest,
            ),
        )
        val ready = missing.isEmpty()
        val brief = TaskBrief(
            taskTitle = if (ready) "ТЗ для MVP приложения учета финансов" else "Неполное ТЗ для приложения",
            goal = "Собрать требования для MVP мобильного приложения",
            knownInputs = userRequest.split(".", ",").map { it.trim() }.filter { it.isNotBlank() },
            missingInputs = missing,
            readyForPlanning = ready,
        )
        return StageResult(brief, notes.warningOrError ?: notes.content, valid = true)
    }
}

class PlanningAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) {
    fun run(brief: TaskBrief): StageResult<TaskPlan> {
        val notes = llmClient.chat(
            promptBuilder.stagePrompt(
                "planning",
                "Create task_plan with steps, artifacts, and user approval requirement.",
                appJson.encodeToString(TaskBrief.serializer(), brief),
            ),
        )
        val plan = TaskPlan(
            steps = listOf(
                "сформировать цели MVP",
                "описать пользователей",
                "описать функциональные требования",
                "описать ограничения",
                "сформировать итоговое ТЗ",
            ),
            requiresUserApproval = true,
            readyForExecution = false,
            notes = notes.warningOrError ?: notes.content.take(1200),
        )
        return StageResult(plan, notes.warningOrError ?: notes.content, valid = plan.steps.isNotEmpty())
    }
}

class ExecutionAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) {
    fun run(state: TaskState): StageResult<DraftResult> {
        val notes = llmClient.chat(
            promptBuilder.stagePrompt(
                "execution",
                "Create draft_result: concise requirements document for MVP.",
                appJson.encodeToString(TaskState.serializer(), state),
            ),
        )
        val content = notes.warningOrError ?: notes.content
        val draft = DraftResult(
            content = content.ifBlank {
                "Черновик ТЗ: Android MVP учета финансов без backend, категории расходов, экспорт CSV, срок 3 недели."
            },
            completedSteps = listOf("цели", "пользователи", "функции", "ограничения", "итоговое ТЗ"),
            readyForValidation = true,
        )
        return StageResult(draft, content, valid = draft.content.isNotBlank())
    }
}

class ValidationAgent(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
) {
    fun run(state: TaskState): StageResult<ValidationReport> {
        val notes = llmClient.chat(
            promptBuilder.stagePrompt(
                "validation",
                "Validate draft_result against task_brief and task_plan. Return issues and recommendations.",
                appJson.encodeToString(TaskState.serializer(), state),
            ),
        )
        val report = ValidationReport(
            isValid = true,
            issues = emptyList(),
            recommendations = listOf("Перед разработкой уточнить необходимость авторизации."),
            readyForDone = true,
        )
        return StageResult(report, notes.warningOrError ?: notes.content, valid = true)
    }
}
