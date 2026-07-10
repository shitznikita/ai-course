import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.HttpStatusCode

fun Application.notesWebModule(config: AppConfig, analyzer: NotesAnalyzer) {
    install(ContentNegotiation) {
        json(AppJson.instance)
    }
    install(StatusPages) {
        exception<ApiProblem> { call, problem ->
            call.respond(
                problem.status,
                ApiError(code = problem.code, message = problem.message, hint = problem.hint),
            )
        }
        exception<Throwable> { call, _ ->
            call.application.log.error("Unexpected note-analysis request failure")
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    code = "internal_error",
                    message = "Не удалось обработать заметку.",
                    hint = "Повторите попытку. Содержимое заметки не сохранялось.",
                ),
            )
        }
    }

    routing {
        get("/api/health") {
            call.respond(analyzer.diagnose())
        }
        post("/api/analyze") {
            val note = call.readUploadedNote(config)
            call.respond(analyzer.analyze(note))
        }
        staticResources("/", "web", index = "index.html")
    }
}
