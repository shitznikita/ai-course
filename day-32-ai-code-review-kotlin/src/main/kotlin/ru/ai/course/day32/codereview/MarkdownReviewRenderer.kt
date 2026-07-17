package ru.ai.course.day32.codereview

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MarkdownReviewRenderer {
    fun render(
        metadata: PullRequestMetadata,
        findings: List<ValidatedFinding>,
        coverage: ReviewCoverage,
        model: String,
    ): String = buildString {
        appendLine(MARKER)
        appendLine("# 🤖 AI Code Review")
        appendLine()
        appendLine(
            "**Коммиты:** `${metadata.baseSha.take(12)}` → `${metadata.headSha.take(12)}`  \n" +
                "**Модель:** `${escapeInline(model)}`  \n" +
                "**Покрытие:** ${coverage.reviewedFiles}/${coverage.reportedChangedFiles} изменённых файлов",
        )
        appendLine()
        appendSection("Потенциальные баги / Potential bugs", findings, FindingCategory.BUG, metadata)
        appendSection(
            "Архитектурные проблемы / Architectural problems",
            findings,
            FindingCategory.ARCHITECTURE,
            metadata,
        )
        appendSection("Рекомендации / Recommendations", findings, FindingCategory.RECOMMENDATION, metadata)
        appendLine("## Покрытие и ограничения")
        appendLine()
        appendLine("- GitHub сообщил файлов: `${coverage.reportedChangedFiles}`; получено: `${coverage.fetchedChangedFiles}`.")
        appendLine("- Бинарных файлов (только метаданные): `${coverage.binaryFiles}`.")
        appendLine(
            "- Неполный patch: `${coverage.patchTruncatedFiles}`; неполное содержимое: " +
                "`${coverage.contentTruncatedFiles}`.",
        )
        appendLine("- Полный diff endpoint: `${if (coverage.diffEndpointAvailable) "доступен" else "недоступен/обрезан"}`.")
        appendLine(
            "- RAG: `${coverage.corpusMetrics.includedFiles}` файлов, " +
                "`${coverage.corpusMetrics.includedBytes}` байт; truncated=`${coverage.corpusMetrics.truncated}`.",
        )
        coverage.notes.forEach { appendLine("- ${escapeText(it)}") }
        appendLine()
        appendLine("_Комментарий обновляется при новом push. Ассистент не approve-ит PR и не запускает код из ветки PR._")
    }.trimEnd() + "\n"

    fun renderDiagnostic(message: String): String = """
        $MARKER
        # 🤖 AI Code Review

        ⚠️ Ревью не опубликовано: ${escapeText(message)}

        Детали diff, prompt, ответ модели и секреты намеренно не выводятся. Проверьте Actions summary и конфигурацию.
    """.trimIndent() + "\n"

    private fun StringBuilder.appendSection(
        title: String,
        findings: List<ValidatedFinding>,
        category: FindingCategory,
        metadata: PullRequestMetadata,
    ) {
        appendLine("## $title")
        appendLine()
        val selected = findings.filter { it.category == category }
        if (selected.isEmpty()) {
            appendLine("Высокоуверенных проблем не найдено.")
            appendLine()
            return
        }
        selected.forEach { finding ->
            appendLine(
                "### `${finding.severity}` — ${escapeText(finding.title)} " +
                    "([`${escapeText(finding.path)}:${finding.line}`](${blobLink(metadata, finding.path, finding.line)}))",
            )
            appendLine()
            appendLine(escapeText(finding.detail))
            appendLine()
            appendLine("**Что сделать:** ${escapeText(finding.recommendation)}")
            appendLine()
            appendLine(
                "**RAG-источники:** " + finding.evidence.joinToString(", ") { source ->
                    "[`${escapeText(source.path)}:${source.startLine}-${source.endLine}`]" +
                        "(${blobLink(metadata.copy(headSha = metadata.baseSha), source.path, source.startLine, source.endLine)})"
                },
            )
            appendLine()
        }
    }

    private fun blobLink(metadata: PullRequestMetadata, path: String, start: Int, end: Int = start): String {
        val encodedPath = path.split('/').joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20")
        }
        val anchor = if (start == end) "#L$start" else "#L$start-L$end"
        return "https://github.com/${metadata.repository}/blob/${metadata.headSha}/$encodedPath$anchor"
    }

    private fun escapeInline(value: String): String = value.replace("`", "'").take(200)

    private fun escapeText(value: String): String =
        value
            .replace("\u0000", "")
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("#", "\\#")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("@", "&#64;")
            .trim()

    companion object {
        const val MARKER = "<!-- ai-code-review:day-32 -->"
    }
}
