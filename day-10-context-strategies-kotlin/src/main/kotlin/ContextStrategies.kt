interface ContextStrategy {
    val name: StrategyName

    fun buildContext(state: AgentState): ContextBuildResult
}

class SlidingWindowStrategy(
    private val systemPrompt: String,
    private val recentMessagesLimit: Int,
    private val tokenCounter: ApproximateTokenCounter,
) : ContextStrategy {
    override val name: StrategyName = StrategyName.SLIDING

    override fun buildContext(state: AgentState): ContextBuildResult {
        val recent = state.messages.takeLast(recentMessagesLimit)
        val messagesForApi = listOf(ChatMessage("system", systemPrompt)) + recent
        return ContextBuildResult(
            strategy = name,
            messagesForApi = messagesForApi,
            promptTokens = tokenCounter.countMessages(messagesForApi),
            recentMessagesSent = recent.size,
            droppedMessages = (state.messages.size - recent.size).coerceAtLeast(0),
        )
    }
}

class FactsStrategy(
    private val systemPrompt: String,
    private val recentMessagesLimit: Int,
    private val tokenCounter: ApproximateTokenCounter,
) : ContextStrategy {
    override val name: StrategyName = StrategyName.FACTS

    override fun buildContext(state: AgentState): ContextBuildResult {
        val recent = state.messages.takeLast(recentMessagesLimit)
        val factsBlock = state.facts.toPromptBlock()
        val messagesForApi = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage(
                "system",
                """
                Sticky facts memory in key-value format.
                Use these facts as stable context. They are not a summary.

                $factsBlock
                """.trimIndent(),
            ),
        ) + recent

        return ContextBuildResult(
            strategy = name,
            messagesForApi = messagesForApi,
            promptTokens = tokenCounter.countMessages(messagesForApi),
            recentMessagesSent = recent.size,
            droppedMessages = (state.messages.size - recent.size).coerceAtLeast(0),
            factsBlock = factsBlock,
        )
    }
}

class BranchingStrategy(
    private val systemPrompt: String,
    private val tokenCounter: ApproximateTokenCounter,
) : ContextStrategy {
    override val name: StrategyName = StrategyName.BRANCHING

    override fun buildContext(state: AgentState): ContextBuildResult {
        val branchState = state.branchState
        val branchMessages = when {
            branchState.branches.isEmpty() -> state.messages
            branchState.activeBranch.isBlank() -> branchState.checkpoint
            else -> branchState.checkpoint + branchState.branches.getValue(branchState.activeBranch)
        }
        val messagesForApi = listOf(ChatMessage("system", systemPrompt)) + branchMessages
        val branchInfo = if (branchState.branches.isEmpty()) {
            "no checkpoint yet"
        } else {
            "active=${branchState.activeBranch}, branches=${branchState.branches.keys.joinToString()}"
        }

        return ContextBuildResult(
            strategy = name,
            messagesForApi = messagesForApi,
            promptTokens = tokenCounter.countMessages(messagesForApi),
            recentMessagesSent = branchMessages.size,
            droppedMessages = 0,
            branchInfo = branchInfo,
        )
    }
}
