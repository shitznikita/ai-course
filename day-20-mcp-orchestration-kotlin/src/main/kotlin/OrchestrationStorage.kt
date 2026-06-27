import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class OrchestrationStorage(private val config: AppConfig) {
    private val runsDir = config.stateDir.resolve("runs")
    private val latestOrchestration = config.stateDir.resolve("latest-orchestration.json")
    private val latestReport = config.stateDir.resolve("latest-report.md")

    fun save(prompt: CodexPromptResult): SaveOrchestrationResult {
        config.stateDir.createDirectories()
        runsDir.createDirectories()

        val savedAt = Instant.now().toString()
        val runFile = runsDir.resolve("${safeTimestamp(savedAt)}-day-${prompt.detectedDay}-orchestration.json")
        val paths = OrchestrationStoragePaths(
            runJson = runFile.toAbsolutePath().toString(),
            latestOrchestrationJson = latestOrchestration.toAbsolutePath().toString(),
            latestReportMarkdown = latestReport.toAbsolutePath().toString(),
        )
        val result = SaveOrchestrationResult(
            savedAtIso = savedAt,
            prompt = prompt,
            storage = paths,
        )
        val serialized = PipelineJson.prettyString(result.toJson())
        runFile.writeText(serialized)
        latestOrchestration.writeText(serialized)
        latestReport.writeText(prompt.reportMarkdown + "\n\n## Codex Execution Prompt\n\n```text\n${prompt.codexPrompt.trim()}\n```\n")
        return result
    }

    fun readLatest(): LatestOrchestrationArtifactResult {
        val readAt = Instant.now().toString()
        val exists = latestOrchestration.exists() && latestReport.exists()
        if (!exists) {
            return LatestOrchestrationArtifactResult(
                readAtIso = readAt,
                exists = false,
                latestOrchestrationJson = latestOrchestration.toAbsolutePath().toString(),
                latestReportMarkdown = latestReport.toAbsolutePath().toString(),
                detectedDay = null,
                reportPreview = "No latest orchestration artifact saved yet.",
            )
        }

        val json = PipelineJson.parseObject(latestOrchestration.readText())
        val detectedDay = runCatching {
            json.objectValue("prompt").intValue("detectedDay")
        }.getOrNull()
        return LatestOrchestrationArtifactResult(
            readAtIso = readAt,
            exists = true,
            latestOrchestrationJson = latestOrchestration.toAbsolutePath().toString(),
            latestReportMarkdown = latestReport.toAbsolutePath().toString(),
            detectedDay = detectedDay,
            reportPreview = latestReport.readText().shortPreview(700),
        )
    }

    private fun safeTimestamp(value: String): String =
        value.replace(':', '-').replace('.', '-')
}
