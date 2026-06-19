import java.time.Instant

class UserProfileManager(config: AppConfig) {
    private val profilesStore = JsonStore(
        config.profilesRoot.resolve("profiles.json"),
        ProfilesFile.serializer(),
        ProfilesFile(defaultProfiles),
    )
    private val activeStore = JsonStore(
        config.profilesRoot.resolve("active_profile.json"),
        ActiveProfileFile.serializer(),
        ActiveProfileFile("beginner"),
    )

    fun ensureSeedProfiles(reset: Boolean = false) {
        if (reset) {
            profilesStore.save(ProfilesFile(defaultProfiles))
            activeStore.save(ActiveProfileFile("beginner"))
            return
        }
        if (profilesStore.load().profiles.isEmpty()) profilesStore.save(ProfilesFile(defaultProfiles))
        val active = activeStore.load().activeProfileId
        if (profilesStore.load().profiles.none { it.id == active }) activeStore.save(ActiveProfileFile("beginner"))
    }

    fun listProfiles(): List<UserProfile> {
        ensureSeedProfiles()
        return profilesStore.load().profiles
    }

    fun activeProfile(): UserProfile {
        ensureSeedProfiles()
        val activeId = activeStore.load().activeProfileId
        return listProfiles().firstOrNull { it.id == activeId } ?: defaultProfiles.first()
    }

    fun useProfile(id: String): Boolean {
        ensureSeedProfiles()
        if (listProfiles().none { it.id == id }) return false
        activeStore.save(ActiveProfileFile(id))
        return true
    }

    fun createTemplate(): UserProfile {
        ensureSeedProfiles()
        val id = "custom_${Instant.now().epochSecond}"
        val profile = UserProfile(
            id = id,
            name = "Никита",
            role = "custom user",
            experienceLevel = "middle",
            style = "кратко и структурно",
            format = "чек-лист",
            domainOrStack = "AI agents",
            constraints = listOf("не хранить секреты в профиле"),
            preferences = listOf("показывать следующий шаг"),
            language = "ru",
        )
        profilesStore.save(ProfilesFile(listProfiles() + profile))
        activeStore.save(ActiveProfileFile(id))
        return profile
    }

    fun updateActive(key: String, value: String): Boolean {
        val active = activeProfile()
        val updated = when (key) {
            "name" -> active.copy(name = value)
            "role" -> active.copy(role = value)
            "experience_level" -> active.copy(experienceLevel = value)
            "style" -> active.copy(style = value)
            "format" -> active.copy(format = value)
            "domain_stack" -> active.copy(domainOrStack = value)
            "constraint" -> active.copy(constraints = active.constraints.appendUnique(value))
            "habit" -> active.copy(habits = active.habits.appendUnique(value))
            "preference" -> active.copy(preferences = active.preferences.appendUnique(value))
            "language" -> active.copy(language = value)
            "do_example" -> active.copy(doExamples = active.doExamples.appendUnique(value))
            "avoid_example" -> active.copy(avoidExamples = active.avoidExamples.appendUnique(value))
            else -> return false
        }
        profilesStore.save(ProfilesFile(listProfiles().map { if (it.id == active.id) updated else it }))
        return true
    }

    fun showActive(): String = appJson.encodeToString(UserProfile.serializer(), activeProfile())

    private fun List<String>.appendUnique(value: String): List<String> {
        val normalized = value.trim()
        if (normalized.isBlank() || any { it.equals(normalized, ignoreCase = true) }) return this
        return this + normalized
    }

    companion object {
        val defaultProfiles = listOf(
            UserProfile(
                id = "beginner",
                name = "Никита",
                role = "начинающий AI-agent developer",
                experienceLevel = "beginner",
                style = "объясняй простыми словами",
                format = "пошаговый чек-лист",
                domainOrStack = "Kotlin CLI, AI agents",
                constraints = listOf("не использовать сложные фреймворки без необходимости", "объяснять новые термины"),
                habits = listOf("лучше воспринимает короткие шаги"),
                preferences = listOf("давать короткий итог в конце", "показывать команды запуска"),
                language = "ru",
                doExamples = listOf("Пиши как чек-лист с простыми словами."),
                avoidExamples = listOf("Не начинай с терминов без объяснения."),
            ),
            UserProfile(
                id = "senior_mobile_dev",
                name = "Никита",
                role = "mobile developer",
                experienceLevel = "senior",
                style = "кратко и технически",
                format = "архитектурные решения и trade-offs",
                domainOrStack = "Kotlin, Android, Jetpack Compose, CLI agents",
                constraints = listOf("предпочитать Kotlin/Android примеры", "не объяснять базовые термины"),
                habits = listOf("быстро сканирует списки рисков"),
                preferences = listOf("указывать риски", "давать варианты реализации"),
                language = "ru",
                doExamples = listOf("Дай структуру модулей, trade-offs и риски."),
                avoidExamples = listOf("Не объясняй, что такое JSON или HTTP."),
            ),
            UserProfile(
                id = "product_manager",
                name = "Никита",
                role = "product manager",
                experienceLevel = "middle",
                style = "практично и бизнес-ориентированно",
                format = "цель, пользователи, риски, MVP",
                domainOrStack = "mobile product discovery",
                constraints = listOf("не уходить глубоко в код", "фокусироваться на ценности для пользователя"),
                habits = listOf("принимает решения через гипотезы и риски"),
                preferences = listOf("формулировать гипотезы", "выделять метрики успеха"),
                language = "ru",
                doExamples = listOf("Начни с цели и MVP-ценности."),
                avoidExamples = listOf("Не перегружай кодом и библиотеками."),
            ),
        )
    }
}
