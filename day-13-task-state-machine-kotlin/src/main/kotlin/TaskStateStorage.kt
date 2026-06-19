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
        Files.createDirectories(path.parent)
        path.writeText(appJson.encodeToString(state))
    }

    fun reset() {
        if (path.exists()) Files.delete(path)
    }

    fun render(): String = load()?.let { appJson.encodeToString(it) } ?: "No task_state.json yet."
}
