package ru.ai.course.day32.codereview

import java.nio.file.Path

fun testConfig(root: Path, overrides: Map<String, String> = emptyMap()): AppConfig =
    AppConfig.fromValues(
        mapOf(
            "REPOSITORY_ROOT" to root.toString(),
            "GITHUB_REPOSITORY" to "owner/repo",
            "PR_NUMBER" to "7",
            "PR_BASE_SHA" to "a".repeat(40),
            "PR_HEAD_SHA" to "b".repeat(40),
            "GITHUB_TOKEN" to "github-test-token",
            "LLM_API_KEY" to "llm-test-token",
        ) + overrides,
    )

fun testBatch(): ReviewBatch = Evaluation.evaluationBatch()
fun testPrepared(maxBytes: Int = 12_000): PreparedReviewPrompt = PromptBuilder(maxBytes).build(testBatch())
fun testInput(): TransmittedReviewInput = testPrepared().input

fun validFindingJson(
    category: String = "BUG",
    severity: String = "HIGH",
    path: String = "src/Service.kt",
    line: Int = 10,
    evidenceIds: String = "\"SRC-doc\"",
): String = """
    {
      "findings": [{
        "category": "$category",
        "severity": "$severity",
        "path": "$path",
        "line": $line,
        "title": "Конкретная проблема в изменении",
        "detail": "Это описание объясняет воспроизводимый риск нового изменения.",
        "recommendation": "Исправить изменение типизированной обработкой результата.",
        "evidenceIds": [$evidenceIds]
      }]
    }
""".trimIndent()
