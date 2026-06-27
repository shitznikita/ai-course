import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PipelineStorage(private val config: AppConfig) {
    private val runsDir = config.stateDir.resolve("runs")
    private val latestPipeline = config.stateDir.resolve("latest-pipeline.json")
    private val latestReport = config.stateDir.resolve("latest-report.md")

    fun save(summary: CourseDiscussionSummaryResult): SavePipelineResult {
        config.stateDir.createDirectories()
        runsDir.createDirectories()

        val savedAt = Instant.now().toString()
        val runFile = runsDir.resolve("${safeTimestamp(savedAt)}-day-${summary.detectedDay}-pipeline.json")
        val paths = PipelineStoragePaths(
            runJson = runFile.toAbsolutePath().toString(),
            latestPipelineJson = latestPipeline.toAbsolutePath().toString(),
            latestReportMarkdown = latestReport.toAbsolutePath().toString(),
        )
        val result = SavePipelineResult(
            savedAtIso = savedAt,
            summary = summary,
            storage = paths,
        )
        val serialized = PipelineJson.prettyString(result.toJson())
        runFile.writeText(serialized)
        latestPipeline.writeText(serialized)
        latestReport.writeText(summary.reportMarkdown)
        return result
    }

    private fun safeTimestamp(value: String): String =
        value.replace(':', '-').replace('.', '-')
}
