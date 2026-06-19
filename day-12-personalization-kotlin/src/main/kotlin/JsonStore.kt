import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class JsonStore<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
) {
    fun load(): T {
        if (!path.exists()) return defaultValue
        return appJson.decodeFromString(serializer, path.readText())
    }

    fun save(value: T) {
        Files.createDirectories(path.parent)
        path.writeText(appJson.encodeToString(serializer, value))
    }

    fun clear() {
        save(defaultValue)
    }
}
