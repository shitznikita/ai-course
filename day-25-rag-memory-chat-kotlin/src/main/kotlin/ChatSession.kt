import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ChatSessionStore(private val config: AppConfig) {
    fun load(): ChatSession {
        val now = Instant.now().toString()
        if (!config.sessionFile.exists()) {
            return ChatSession(createdAtIso = now, updatedAtIso = now)
        }
        return AppJson.compact.decodeFromString(config.sessionFile.readText())
    }

    fun save(session: ChatSession) {
        config.sessionFile.parent?.createDirectories()
        config.sessionFile.writeText(AppJson.pretty.encodeToString(session))
    }

    fun clear() {
        config.sessionFile.deleteIfExists()
    }
}

class TaskMemoryUpdater {
    fun update(previous: TaskStateMemory, userMessage: String, turn: Int): TaskStateMemory {
        val normalized = userMessage.trim()
        if (normalized.isBlank()) return previous

        val goal = detectGoal(previous.goal, normalized)
        val constraints = merge(previous.constraints, detectConstraints(normalized), limit = 12)
        val terms = merge(previous.terms, detectTerms(normalized), limit = 24)
        val clarifications = if (previous.goal != null) {
            merge(previous.clarifications, listOf(normalized.shortPreview(180)), limit = 18)
        } else {
            previous.clarifications
        }
        val openQuestions = if (looksLikeQuestion(normalized)) {
            merge(previous.openQuestions, listOf(normalized.shortPreview(180)), limit = 12)
        } else {
            previous.openQuestions
        }

        return previous.copy(
            goal = goal,
            clarifications = clarifications,
            constraints = constraints,
            terms = terms,
            openQuestions = openQuestions,
            updatedAtTurn = turn,
        )
    }

    private fun detectGoal(previousGoal: String?, message: String): String? {
        if (previousGoal != null && !message.contains(Regex("""\b(цель|задача|goal)\b""", RegexOption.IGNORE_CASE))) {
            return previousGoal
        }
        val explicit = Regex("""(цель|задача|goal)\s*[:\-]\s*(.+)""", RegexOption.IGNORE_CASE)
            .find(message)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { rawGoal ->
                rawGoal
                    .substringBefore("Ограничение:")
                    .substringBefore("Уточнение:")
                    .substringBefore("?")
                    .trim()
                    .trimEnd('.', ';')
            }
            ?.takeIf { it.isNotBlank() }
        return (explicit ?: previousGoal ?: message).shortPreview(220)
    }

    private fun detectConstraints(message: String): List<String> {
        val markers = listOf("огранич", "нельзя", "без ", "только", "не отправ", "секрет", "offline", "источник", "цитат")
        if (markers.none { message.contains(it, ignoreCase = true) }) return emptyList()
        return message
            .split(Regex("""[.;\n]"""))
            .map { it.trim() }
            .filter { part -> markers.any { part.contains(it, ignoreCase = true) } }
            .map { it.shortPreview(180) }
    }

    private fun detectTerms(message: String): List<String> {
        val regex = Regex("""(?i)\b(day[\s-]?\d{1,2}|день\s*\d{1,2}|RAG|MCP|README|Kotlin|CLI|Eliza|Ollama|sources?|источники?|цитаты?|chunking|rerank|task state|память)\b""")
        return regex.findAll(message)
            .map { it.value.lowercase().replace(Regex("""\s+"""), " ").replace("day ", "day-") }
            .map {
                when {
                    it.startsWith("день ") -> "day-${it.substringAfter("день ").trim()}"
                    else -> it
                }
            }
            .distinct()
            .toList()
    }

    private fun looksLikeQuestion(message: String): Boolean =
        message.contains('?') || message.lowercase().trimStart().startsWith("как ") ||
            message.lowercase().trimStart().startsWith("что ") ||
            message.lowercase().trimStart().startsWith("какие ")

    private fun merge(existing: List<String>, additions: List<String>, limit: Int): List<String> =
        (existing + additions)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .takeLast(limit)
}

class ChatAgent(private val config: AppConfig) {
    private val store = ChatSessionStore(config)
    private val ragAgent = RagAgent(config)
    private val memoryUpdater = TaskMemoryUpdater()

    fun answerDryRun(message: String): ChatTurnRun = answer(message, live = false)

    fun answerLive(message: String): ChatTurnRun = answer(message, live = true)

    fun clear() {
        store.clear()
    }

    fun session(): ChatSession = store.load()

    private fun answer(message: String, live: Boolean): ChatTurnRun {
        val loaded = store.load()
        val userTurnNumber = loaded.history.size + 1
        val userTurn = ChatTurn(
            turn = userTurnNumber,
            role = "user",
            content = message,
        )
        val sessionWithUser = loaded.copy(
            updatedAtIso = Instant.now().toString(),
            history = trimHistory(loaded.history + userTurn),
        )
        val taskStateBefore = sessionWithUser.taskState
        val taskStateAfter = memoryUpdater.update(taskStateBefore, message, userTurnNumber)
        val recentHistory = sessionWithUser.history
            .takeLast(config.chatRecentMessagesInPrompt)
        val retrievalQuery = buildRetrievalQuery(message, taskStateAfter, recentHistory)
        val groundedRun = if (live) {
            ragAgent.askLive(
                question = message,
                retrievalQuery = retrievalQuery,
                taskState = taskStateAfter,
                recentHistory = recentHistory,
            )
        } else {
            ragAgent.askDryRun(
                question = message,
                retrievalQuery = retrievalQuery,
                taskState = taskStateAfter,
                recentHistory = recentHistory,
            )
        }
        val assistantTurn = ChatTurn(
            turn = userTurnNumber + 1,
            role = "assistant",
            content = groundedRun.answer.answer,
            status = groundedRun.answer.status,
            sources = groundedRun.answer.sources,
            quotes = groundedRun.answer.quotes,
            retrievalQuery = retrievalQuery,
            maxRelevance = groundedRun.retrieval.selected.maxOfOrNull { it.rerankScore } ?: 0.0,
            validationErrors = groundedRun.validation.errors,
        )
        val saved = sessionWithUser.copy(
            updatedAtIso = Instant.now().toString(),
            taskState = taskStateAfter,
            history = trimHistory(sessionWithUser.history + assistantTurn),
        )
        store.save(saved)
        return ChatTurnRun(
            mode = if (live) "live-chat" else "dry-run-chat",
            userMessage = message,
            retrievalQuery = retrievalQuery,
            taskStateBefore = taskStateBefore,
            taskStateAfter = taskStateAfter,
            groundedRun = groundedRun,
            session = saved,
        )
    }

    private fun buildRetrievalQuery(
        message: String,
        taskState: TaskStateMemory,
        recentHistory: List<ChatTurn>,
    ): String =
        buildString {
            appendLine(message)
            taskState.goal?.let {
                appendLine("Current task goal: $it")
            }
            if (taskState.constraints.isNotEmpty()) {
                appendLine("Constraints: ${taskState.constraints.joinToString("; ")}")
            }
            if (taskState.terms.isNotEmpty()) {
                appendLine("Terms: ${taskState.terms.joinToString(", ")}")
            }
            val recentUserMessages = recentHistory
                .filter { it.role == "user" }
                .takeLast(3)
                .joinToString(" ") { it.content }
            if (recentUserMessages.isNotBlank()) {
                appendLine("Recent user context: $recentUserMessages")
            }
            appendLine("Need grounded ai-course repository answer with sources, quotes, README, AGENTS, day folders.")
        }.trim()

    private fun trimHistory(history: List<ChatTurn>): List<ChatTurn> =
        history.takeLast(config.chatHistoryMaxMessages)
}
