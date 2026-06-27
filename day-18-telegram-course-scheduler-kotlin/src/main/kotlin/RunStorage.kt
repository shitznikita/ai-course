import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CourseRunStorage(private val config: AppConfig) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val runsDir = config.stateDir.resolve("runs")
    private val latestRun = config.stateDir.resolve("latest-run.json")
    private val latestPrompt = config.stateDir.resolve("latest-prompt.md")

    fun save(result: CourseDayPromptResult): CourseDayPromptResult {
        config.stateDir.createDirectories()
        runsDir.createDirectories()

        val runFile = runsDir.resolve("${safeTimestamp(result.generatedAtIso)}-day-${result.detectedDay}.json")
        val paths = StoragePaths(
            runJson = runFile.toAbsolutePath().toString(),
            latestRunJson = latestRun.toAbsolutePath().toString(),
            latestPromptMarkdown = latestPrompt.toAbsolutePath().toString(),
        )
        val stored = result.withStorage(paths)
        val serialized = json.encodeToString(JsonObject.serializer(), stored.toJson())
        runFile.writeText(serialized)
        latestRun.writeText(serialized)
        latestPrompt.writeText(stored.prompt)
        return stored
    }

    fun readLatest(): LatestPromptResult {
        require(latestRun.exists() && latestPrompt.exists()) {
            "No saved prompt found. Run generate_course_day_prompt first."
        }
        return LatestPromptResult(
            latestRunJson = latestRun.toAbsolutePath().toString(),
            latestPromptMarkdown = latestPrompt.toAbsolutePath().toString(),
            prompt = latestPrompt.readText(),
        )
    }

    private fun safeTimestamp(value: String): String =
        value.replace(':', '-').replace('.', '-')
}
