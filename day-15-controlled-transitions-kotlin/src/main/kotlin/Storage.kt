import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TaskStateStorage(private val path: Path) {
    fun load(): TaskState? {
        if (!path.exists()) return null
        return appJson.decodeFromString(TaskState.serializer(), path.readText())
    }

    fun save(state: TaskState) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(appJson.encodeToString(state))
    }

    fun reset() {
        if (path.exists()) Files.delete(path)
    }

    fun render(): String = load()?.let { appJson.encodeToString(it) } ?: "No task_state.json yet."
}

class InvariantStore(private val path: Path) {
    fun ensureSeed(reset: Boolean = false) {
        if (reset || !path.exists()) save(defaultInvariants)
    }

    fun load(): InvariantsFile {
        ensureSeed()
        return appJson.decodeFromString(InvariantsFile.serializer(), path.readText())
    }

    fun active(): List<Invariant> = load().invariants.filter { it.enabled }

    fun save(file: InvariantsFile) {
        Files.createDirectories(path.parent)
        path.writeText(appJson.encodeToString(file))
    }

    companion object {
        val defaultInvariants = InvariantsFile(
            listOf(
                Invariant(
                    id = "kotlin_only",
                    title = "Только Kotlin",
                    description = "Не предлагать Java для реализации.",
                    forbiddenKeywords = listOf("Java"),
                ),
                Invariant(
                    id = "no_backend_mvp",
                    title = "Без backend для MVP",
                    description = "Первый релиз работает локально, без backend и серверной синхронизации.",
                    forbiddenKeywords = listOf("backend", "сервер", "cloud sync"),
                ),
                Invariant(
                    id = "api_keys_env_only",
                    title = "API-ключи только через env",
                    description = "Не хардкодить API-ключи.",
                    severity = "critical",
                    forbiddenKeywords = listOf("прямо в строке", "секрет в коде"),
                ),
            ),
        )
    }
}
