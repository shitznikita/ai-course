import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.decodeFromString

fun Application.cosmeticsWebModule(
    config: AppConfig,
    useCases: CosmeticsUseCases,
    security: RequestSecurity = RequestSecurity(config),
    rateLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(config.rateLimitRequests, config.rateLimitWindow),
) {
    monitor.subscribe(ApplicationStopped) {
        (useCases as? AutoCloseable)?.close()
    }
    install(ContentNegotiation) { json(AppJson.strict) }
    install(StatusPages) {
        exception<ApiProblem> { call, problem ->
            problem.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
            if (problem.status == HttpStatusCode.Unauthorized) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            }
            call.respond(problem.status, ApiError(problem.code, problem.message, problem.hint))
        }
        exception<Throwable> { call, error ->
            call.application.log.error("Unexpected cosmetics-service request failure: ${error::class.simpleName}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("internal_error", "Не удалось обработать запрос.", "Содержимое запроса не сохранялось."),
            )
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.response.headers.append(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
        )
        if (call.request.local.uri.startsWith("/api/")) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        }
    }

    routing {
        get("/api/health/live") {
            call.respond(LivenessResponse())
        }
        get("/api/health") {
            guard(call, security, rateLimiter)
            call.respond(useCases.health())
        }
        post("/api/analyze/text") {
            guard(call, security, rateLimiter)
            val request = call.receiveBoundedJson<AnalyzeTextRequest>(config.maxInciChars * 4 + 16_384)
            call.respond(useCases.analyzeText(request))
        }
        post("/api/analyze/name") {
            guard(call, security, rateLimiter)
            val request = call.receiveBoundedJson<AnalyzeNameRequest>(16_384)
            call.respond(useCases.analyzeName(request))
        }
        post("/api/ocr") {
            guard(call, security, rateLimiter)
            call.respond(useCases.recognizePhoto(call.readUploadedPhoto(config)))
        }
        post("/api/chat") {
            guard(call, security, rateLimiter)
            val request = call.receiveBoundedJson<ChatRequest>(config.maxChatChars * 4 + 8_192)
            call.respond(useCases.chat(request))
        }
        delete("/api/sessions/{id}") {
            guard(call, security, rateLimiter)
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank() || !useCases.deleteSession(id)) {
                throw ApiProblem(HttpStatusCode.NotFound, "session_not_found", "Сессия анализа не найдена или уже удалена.")
            }
            call.respond(DeleteSessionResponse(deleted = true))
        }
        staticResources("/", "web", index = "index.html")
    }
}

private fun guard(call: ApplicationCall, security: RequestSecurity, rateLimiter: FixedWindowRateLimiter) {
    val principal = security.authorize(call)
    rateLimiter.check(principal)
}

private suspend inline fun <reified T> ApplicationCall.receiveBoundedJson(maxBytes: Int): T {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiProblem(HttpStatusCode.UnsupportedMediaType, "json_required", "Ожидается Content-Type: application/json.")
    }
    val bytes = receiveChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
    if (bytes.size > maxBytes) {
        throw ApiProblem(HttpStatusCode.PayloadTooLarge, "json_too_large", "JSON превышает допустимый размер.")
    }
    val text = bytes.toString(Charsets.UTF_8)
    return try {
        AppJson.strict.decodeFromString(text)
    } catch (_: Exception) {
        throw ApiProblem(HttpStatusCode.BadRequest, "invalid_json", "JSON не соответствует контракту API.")
    }
}
