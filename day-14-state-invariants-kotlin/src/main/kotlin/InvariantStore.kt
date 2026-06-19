import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class InvariantStore(private val path: Path) {
    fun ensureSeed(reset: Boolean = false) {
        if (reset || !path.exists()) save(defaultInvariants)
    }

    fun load(): InvariantsFile {
        ensureSeed()
        return appJson.decodeFromString(InvariantsFile.serializer(), path.readText())
    }

    fun save(value: InvariantsFile) {
        Files.createDirectories(path.parent)
        path.writeText(appJson.encodeToString(value))
    }

    fun active(): List<Invariant> = load().active()

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = load()
        var found = false
        fun update(list: List<Invariant>) = list.map {
            if (it.id == id) {
                found = true
                it.copy(enabled = enabled)
            } else {
                it
            }
        }
        save(
            current.copy(
                project = update(current.project),
                security = update(current.security),
                communication = update(current.communication),
                userDefined = update(current.userDefined),
            ),
        )
        return found
    }

    fun show(id: String): Invariant? = load().all().firstOrNull { it.id == id }

    fun addUserDefined(text: String): Invariant {
        val normalized = text.trim()
        val id = "user_${(load().userDefined.size + 1).toString().padStart(3, '0')}"
        val invariant = Invariant(
            id = id,
            title = normalized.take(60),
            description = normalized,
            type = "user_defined",
            severity = "medium",
            enabled = true,
            forbiddenKeywords = extractForbiddenKeywords(normalized),
        )
        val current = load()
        save(current.copy(userDefined = current.userDefined + invariant))
        return invariant
    }

    private fun extractForbiddenKeywords(text: String): List<String> {
        val lower = text.lowercase()
        return buildList {
            if ("java" in lower) add("Java")
            if ("backend" in lower || "бэкенд" in lower) add("backend")
            if ("платн" in lower) add("платн")
        }
    }

    companion object {
        val defaultInvariants = InvariantsFile(
            project = listOf(
                Invariant(
                    id = "architecture_clean",
                    title = "Clean Architecture",
                    description = "Все технические решения должны соответствовать Clean Architecture.",
                    type = "architecture",
                    severity = "high",
                    enabled = true,
                ),
                Invariant(
                    id = "stack_kotlin_only",
                    title = "Только Kotlin",
                    description = "Для кода и архитектурных примеров использовать Kotlin. Не предлагать Java.",
                    type = "tech_stack",
                    severity = "high",
                    enabled = true,
                    forbiddenKeywords = listOf("Java", "Spring Boot"),
                ),
                Invariant(
                    id = "ui_compose_only",
                    title = "Только Jetpack Compose",
                    description = "UI должен быть на Jetpack Compose. Не предлагать XML layout.",
                    type = "tech_stack",
                    severity = "medium",
                    enabled = true,
                    forbiddenKeywords = listOf("XML layout", "Android Views"),
                ),
                Invariant(
                    id = "no_backend_mvp",
                    title = "MVP без backend",
                    description = "В первом релизе нельзя предлагать backend, серверную авторизацию или облачную синхронизацию.",
                    type = "business_rule",
                    severity = "high",
                    enabled = true,
                    forbiddenKeywords = listOf("backend", "server", "сервер", "облачная синхронизация", "cloud sync"),
                ),
                Invariant(
                    id = "local_room_storage",
                    title = "Локальное хранение через Room",
                    description = "Данные MVP должны храниться локально через Room.",
                    type = "architecture",
                    severity = "medium",
                    enabled = true,
                ),
            ),
            security = listOf(
                Invariant(
                    id = "no_hardcoded_api_keys",
                    title = "Не хардкодить API-ключи",
                    description = "API-ключи должны храниться только в .env или переменных окружения.",
                    type = "security",
                    severity = "critical",
                    enabled = true,
                    forbiddenKeywords = listOf("прямо в строке", "вставь API-ключ", "секрет в коде"),
                    forbiddenPatterns = listOf("api_key = \"", "OPENAI_API_KEY = \"", "Authorization: Bearer sk-"),
                ),
            ),
            communication = listOf(
                Invariant(
                    id = "no_profanity",
                    title = "Без мата",
                    description = "Ассистент не должен генерировать мат, оскорбления или токсичный текст.",
                    type = "communication",
                    severity = "high",
                    enabled = true,
                    forbiddenKeywords = listOf("матный", "матом", "оскорб"),
                ),
            ),
        )
    }
}
