import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RequestSecurity(private val config: AppConfig) {
    fun authorize(call: ApplicationCall): String {
        if (config.allowInsecureNoAuth) return "loopback-development"
        val header = call.request.headers[HttpHeaders.Authorization]
        val supplied = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')?.trim()
        val expected = config.apiToken.orEmpty()
        if (supplied.isNullOrBlank() || !constantTimeEquals(supplied, expected)) {
            throw ApiProblem(
                HttpStatusCode.Unauthorized,
                "unauthorized",
                "Нужен действующий Bearer token.",
                "Передайте Authorization: Bearer <APP_API_TOKEN>.",
            )
        }
        return MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )
}

class FixedWindowRateLimiter(
    private val limit: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Counter(var startedAt: Long, var count: Int)
    private val counters = ConcurrentHashMap<String, Counter>()

    fun check(key: String) {
        val now = clock.millis()
        val windowMillis = window.toMillis()
        val counter = counters.computeIfAbsent(key) { Counter(now, 0) }
        synchronized(counter) {
            if (now - counter.startedAt >= windowMillis) {
                counter.startedAt = now
                counter.count = 0
            }
            if (counter.count >= limit) {
                val retry = ((windowMillis - (now - counter.startedAt)).coerceAtLeast(1) + 999) / 1000
                throw ApiProblem(
                    HttpStatusCode.TooManyRequests,
                    "rate_limit_exceeded",
                    "Слишком много запросов за короткое время.",
                    "Повторите запрос после паузы.",
                    retryAfterSeconds = retry,
                )
            }
            counter.count += 1
        }
    }
}

class InferenceGate(
    maxConcurrent: Int,
    private val maxQueued: Int,
    private val queueTimeout: Duration = Duration.ofSeconds(30),
) {
    private val semaphore = Semaphore(maxConcurrent, true)
    private val queued = AtomicInteger(0)

    suspend fun <T> withPermit(block: suspend () -> T): T {
        var acquired = semaphore.tryAcquire()
        if (!acquired) {
            val waitingNow = queued.incrementAndGet()
            if (waitingNow > maxQueued) {
                queued.decrementAndGet()
                throw ApiProblem(
                    HttpStatusCode.TooManyRequests,
                    "inference_queue_full",
                    "Локальная модель занята, а очередь заполнена.",
                    "Повторите запрос через несколько секунд.",
                    retryAfterSeconds = 10,
                )
            }
            try {
                acquired = withContext(Dispatchers.IO) {
                    semaphore.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS)
                }
            } finally {
                queued.decrementAndGet()
            }
            if (!acquired) {
                throw ApiProblem(
                    HttpStatusCode.TooManyRequests,
                    "inference_queue_timeout",
                    "Запрос слишком долго ожидал локальную модель.",
                    "Повторите запрос позже.",
                    retryAfterSeconds = 10,
                )
            }
        }
        return try {
            block()
        } finally {
            if (acquired) semaphore.release()
        }
    }
}
