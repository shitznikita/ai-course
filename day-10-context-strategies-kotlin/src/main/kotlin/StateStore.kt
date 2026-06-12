import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class StateStore(private val path: Path) {
    fun load(): AgentState {
        if (!path.exists()) return AgentState()
        return runCatching {
            appJson.decodeFromString<AgentState>(Files.readString(path))
        }.getOrElse { error ->
            System.err.println("Failed to read state file: ${error.message}")
            AgentState()
        }
    }

    fun save(state: AgentState) {
        path.parent?.createDirectories()
        Files.writeString(path, appJson.encodeToString(state))
    }

    fun clear() {
        if (path.exists()) Files.delete(path)
    }
}
