import kotlinx.serialization.encodeToString

class LLMInvariantJudge(
    private val llmClient: LlmClient,
) {
    fun judge(text: String, where: String, invariants: List<Invariant>): ValidationResult {
        val response = llmClient.chat(
            listOf(
                ChatMessage(
                    "system",
                    """
                    Ты проверяющий агент. Проверь, нарушает ли текст список инвариантов.
                    Не решай задачу пользователя.
                    Верни короткий JSON: {"valid": true|false, "violations":[{"invariant_id":"...","reason":"..."}]}.
                    Учитывай смысл, а не только ключевые слова.
                    """.trimIndent(),
                ),
                ChatMessage(
                    "user",
                    """
                    WHERE: $where
                    INVARIANTS:
                    ${appJson.encodeToString(invariants)}

                    TEXT:
                    $text
                    """.trimIndent(),
                ),
            ),
        )
        val content = response.warningOrError ?: response.content
        val saysInvalid = content.contains("\"valid\"\\s*:\\s*false".toRegex(RegexOption.IGNORE_CASE)) ||
            content.contains("valid: false", ignoreCase = true)
        return if (saysInvalid) {
            ValidationResult(
                valid = false,
                violations = listOf(
                    Violation(
                        invariantId = "llm_judge",
                        severity = "medium",
                        where = where,
                        message = content.take(500),
                    ),
                ),
            )
        } else {
            ValidationResult(valid = true)
        }
    }
}
