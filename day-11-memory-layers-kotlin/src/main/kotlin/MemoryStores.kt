import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class JsonMemoryStore<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
) {
    fun load(): T {
        if (!path.exists()) return defaultValue
        return runCatching {
            appJson.decodeFromString(serializer, Files.readString(path))
        }.getOrElse { error ->
            System.err.println("Failed to read memory file $path: ${error.message}")
            defaultValue
        }
    }

    fun save(value: T) {
        path.parent?.createDirectories()
        Files.writeString(path, appJson.encodeToString(serializer, value))
    }

    fun clear() {
        if (path.exists()) Files.delete(path)
    }
}
