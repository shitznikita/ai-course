class ContextAgent(
    private val llmClient: LlmClient,
    private val tokenCounter: ApproximateTokenCounter,
    private val factMemoryUpdater: FactMemoryUpdater,
    private val stateStore: StateStore?,
    recentMessagesLimit: Int,
    initialState: AgentState = AgentState(),
) {
    private val systemPrompt = """
        Ты ContextStrategyAgent. Помогай собирать техническое задание.
        Отвечай структурированно и не выдумывай факты, которых нет в текущем контексте.
        Если важной информации нет в доступном контексте, явно напиши, что ее не видно.
        Финальные ответы держи в пределах 200 слов. Не печатай случайный или поврежденный текст.
    """.trimIndent()

    private val strategies = mapOf(
        StrategyName.SLIDING to SlidingWindowStrategy(systemPrompt, recentMessagesLimit, tokenCounter),
        StrategyName.FACTS to FactsStrategy(systemPrompt, recentMessagesLimit, tokenCounter),
        StrategyName.BRANCHING to BranchingStrategy(systemPrompt, tokenCounter),
    )

    private var state: AgentState = initialState
    private var strategyName: StrategyName = StrategyName.SLIDING
    private var factsUpdatePromptTokens: Int = 0

    fun switchStrategy(next: StrategyName) {
        strategyName = next
        save()
    }

    fun currentStrategy(): StrategyName = strategyName

    fun currentState(): AgentState = state

    fun addUserMessage(content: String): FactUpdateStats? {
        val stats = if (strategyName == StrategyName.FACTS) {
            val (updatedFacts, updateStats) = factMemoryUpdater.update(state.facts, content)
            state = state.copy(facts = updatedFacts)
            factsUpdatePromptTokens += updateStats.promptTokens
            updateStats
        } else {
            null
        }

        appendMessage(ChatMessage("user", content))
        save()
        return stats
    }

    fun ask(content: String): AgentAnswer {
        addUserMessage(content)
        val context = strategies.getValue(strategyName).buildContext(state)
        val response = llmClient.complete(context.messagesForApi)
        appendMessage(ChatMessage("assistant", response.content))
        save()

        return AgentAnswer(
            strategy = strategyName,
            answer = response.content,
            context = context,
            usage = response.usage,
            warningOrError = response.warningOrError,
            factsUpdatePromptTokens = factsUpdatePromptTokens,
        )
    }

    fun createCheckpoint() {
        state = state.copy(
            branchState = BranchState(
                checkpoint = state.messages,
                branches = emptyMap(),
                activeBranch = "",
            ),
        )
        save()
    }

    fun createBranch(name: String) {
        val currentBranches = state.branchState.branches
        state = state.copy(
            branchState = state.branchState.copy(
                branches = currentBranches + (name to emptyList()),
                activeBranch = if (state.branchState.activeBranch.isBlank()) name else state.branchState.activeBranch,
            ),
        )
        save()
    }

    fun switchBranch(name: String): Boolean {
        if (name !in state.branchState.branches) return false
        state = state.copy(branchState = state.branchState.copy(activeBranch = name))
        save()
        return true
    }

    fun branchNames(): Set<String> = state.branchState.branches.keys

    fun clear() {
        state = AgentState()
        factsUpdatePromptTokens = 0
        stateStore?.clear()
    }

    fun buildCurrentContext(): ContextBuildResult {
        return strategies.getValue(strategyName).buildContext(state)
    }

    private fun appendMessage(message: ChatMessage) {
        state = if (strategyName == StrategyName.BRANCHING && state.branchState.activeBranch.isNotBlank()) {
            val active = state.branchState.activeBranch
            val currentBranchMessages = state.branchState.branches[active].orEmpty()
            state.copy(
                branchState = state.branchState.copy(
                    branches = state.branchState.branches + (active to (currentBranchMessages + message)),
                ),
            )
        } else {
            state.copy(messages = state.messages + message)
        }
    }

    private fun save() {
        stateStore?.save(state)
    }
}
