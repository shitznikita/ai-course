package ru.ai.course.day35.releaseprep
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
data class WrittenReport(val path: java.nio.file.Path, val sha256: String)
object ReleaseReportRenderer {
    fun render(facts: ReleaseFacts, plan: ValidatedPlan, readiness: String): String = buildString {
        appendLine("# Release readiness")
        appendLine()
        appendLine("- Source: `${escape(facts.source)}`")
        appendLine("- Branch: `${escape(facts.branch)}`")
        appendLine("- Base: `${escape(facts.base)}`")
        appendLine("- Merge base: `${facts.mergeBase}`")
        appendLine("- HEAD: `${facts.head}`")
        appendLine("- Snapshot fingerprint: `${facts.snapshot.fingerprint}`")
        appendLine("- Manifest fingerprint: `${facts.manifest.fingerprint}`")
        appendLine("- Prompt fingerprint: `${facts.prompt.fingerprint}`")
        appendLine("- Manifest coverage: `${facts.manifest.items.size}/${facts.manifest.items.size}`")
        appendLine("- AI evidence coverage: `${facts.prompt.evidence.items.size}/${facts.manifest.items.size}`")
        appendLine("- Server readiness: **$readiness**")
        appendLine("- AI recommendation: `${plan.value.recommendation}` (advisory)")
        appendLine()
        appendLine("## Deterministic checks")
        facts.checks.forEach {
            appendLine("- `${escape(it.name)}`: ${if (it.passed) "PASS" else "FAIL"}; exit `${it.exitCode}`; `${escape(it.command)}`")
        }
        appendAi("AI summary", plan.value.summary)
        appendAi("Release notes", plan.value.releaseNotes)
        appendLine()
        appendLine("## Release risks")
        if (plan.value.risks.isEmpty()) appendLine("- None reported.")
        plan.value.risks.forEach { risk ->
            appendLine("- **${risk.severity}** ${escape(risk.text)}")
            appendLine("  - Mitigation: ${escape(risk.mitigation)}")
            appendLine("  - Evidence: ${risk.evidencePaths.joinToString { "`${escape(it)}`" }}")
        }
        appendAi("Video steps", plan.value.videoSteps)
        appendLine()
        appendLine("> AI prose is advisory. Git identities, checks, coverage and readiness are server-owned.")
    }
    fun console(plan: ValidatedPlan): String = buildString {
        appendLine("AI SUMMARY:")
        plan.value.summary.forEach { appendLine("- ${it.text} [${it.evidencePaths.joinToString()}]") }
        appendLine("RELEASE NOTES:")
        plan.value.releaseNotes.forEach { appendLine("- ${it.text} [${it.evidencePaths.joinToString()}]") }
        appendLine("RISKS:")
        plan.value.risks.forEach { appendLine("- ${it.severity}: ${it.text} | ${it.mitigation}") }
        appendLine("VIDEO STEPS:")
        plan.value.videoSteps.forEach { appendLine("- ${it.text}") }
        append("AI RECOMMENDATION: ${plan.value.recommendation} (advisory)")
    }
    private fun StringBuilder.appendAi(title: String, items: List<AiText>) {
        appendLine()
        appendLine("## $title")
        items.forEach {
            appendLine("- ${escape(it.text)}")
            appendLine("  - Evidence: ${it.evidencePaths.joinToString { path -> "`${escape(path)}`" }}")
        }
    }
    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            if (char in "\\`*_{}[]<>()#+-.!|") append('\\')
            append(char)
        }
    }
}
class ReleaseReportWriter(private val repositoryInput: java.nio.file.Path) {
    private val repository = repositoryInput.toRealPath()
    private val module = repository.resolve("day-35-ai-release-prep-kotlin")
    fun writeFixture(content: String): WrittenReport = write("fixture-release-readiness.md", content)
    fun writeLive(content: String, validateSnapshot: () -> Unit): WrittenReport =
        write("release-readiness.md", content, validateSnapshot)
    private fun write(name: String, content: String, beforeReplace: () -> Unit = {}) : WrittenReport {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= Limits.FIXTURE_REPORT_BYTES) { "Report exceeds cap" }
        val destination = destination(name)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(destination)) {
                "Unsafe report destination"
            }
        }
        val temp = destination.parent.resolve(".$name.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeReplace()
            require(!Files.isSymbolicLink(destination)) { "Report destination changed to symlink" }
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return WrittenReport(destination, sha256Bytes(bytes))
        } finally {
            Files.deleteIfExists(temp)
        }
    }
    private fun destination(name: String): java.nio.file.Path {
        require(name in setOf("release-readiness.md", "fixture-release-readiness.md"))
        require(Files.isDirectory(module, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(module)) {
            "Day 35 module must be a real directory"
        }
        val reports = module.resolve("reports")
        if (Files.exists(reports, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(reports)) {
                "Reports path must be a real directory"
            }
        } else {
            Files.createDirectory(reports)
        }
        return reports.resolve(name)
    }
}
