import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AnalysisSessionStore(
    private val maxSessions: Int,
    private val ttl: Duration,
    private val maxHistoryMessages: Int,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, AnalysisSession>()
    private val cleanupIntervalMillis = minOf(ttl.toMillis(), 60_000L).coerceAtLeast(1_000L)
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cosmetics-session-cleaner").apply { isDaemon = true }
    }.also { executor ->
        executor.scheduleAtFixedRate(
            { removeExpired() },
            cleanupIntervalMillis,
            cleanupIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    fun create(
        input: AnalysisInputSummary,
        profile: SkinProfile,
        report: CosmeticsReport,
        cards: List<IngredientCard>,
        sources: List<KnowledgeSource>,
    ): AnalysisSession {
        removeExpired()
        if (sessions.size >= maxSessions) {
            sessions.values.minByOrNull { it.updatedAtMillis }?.let { sessions.remove(it.id) }
        }
        val now = clock.millis()
        val session = AnalysisSession(
            id = UUID.randomUUID().toString(),
            createdAtMillis = now,
            updatedAtMillis = now,
            input = input,
            profile = profile,
            report = report,
            cards = cards,
            sources = sources,
            history = emptyList(),
        )
        sessions[session.id] = session
        return session
    }

    fun require(id: String): AnalysisSession {
        val session = sessions[id] ?: throw sessionMissing()
        if (isExpired(session)) {
            sessions.remove(id)
            throw sessionMissing()
        }
        return session
    }

    fun append(id: String, user: String, assistant: String): AnalysisSession {
        var updated: AnalysisSession? = null
        sessions.compute(id) { _, existing ->
            if (existing == null || isExpired(existing)) return@compute null
            val history = (existing.history + StoredChatMessage("user", user) + StoredChatMessage("assistant", assistant))
                .takeLast(maxHistoryMessages)
            existing.copy(updatedAtMillis = clock.millis(), history = history).also { updated = it }
        }
        return updated ?: throw sessionMissing()
    }

    fun delete(id: String): Boolean = sessions.remove(id) != null

    internal fun removeExpired() {
        sessions.entries.removeIf { isExpired(it.value) }
    }

    private fun isExpired(session: AnalysisSession): Boolean = clock.millis() - session.updatedAtMillis >= ttl.toMillis()

    private fun sessionMissing() = ApiProblem(
        HttpStatusCode.NotFound,
        "session_not_found",
        "Сессия анализа не найдена или уже удалена.",
        "Повторите анализ продукта; сессии хранятся только временно в RAM.",
    )

    override fun close() {
        cleanupExecutor.shutdownNow()
        sessions.clear()
    }
}
