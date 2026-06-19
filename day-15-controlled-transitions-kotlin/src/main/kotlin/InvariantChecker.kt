class InvariantChecker {
    fun check(text: String, invariants: List<Invariant>): ValidationResult {
        val violations = invariants.flatMap { invariant ->
            invariant.forbiddenKeywords
                .filter { hasForbiddenOccurrence(text, it) }
                .map { "Violation ${invariant.id}: forbidden keyword '$it'. ${invariant.description}" }
        }
        return ValidationResult(violations.isEmpty(), violations)
    }

    private fun hasForbiddenOccurrence(text: String, keyword: String): Boolean {
        val lower = text.lowercase()
        val needle = keyword.lowercase()
        var index = lower.indexOf(needle)
        while (index >= 0) {
            if (isAsciiKeyword(needle) && isInsideIdentifier(lower, index, needle.length)) {
                index = lower.indexOf(needle, index + needle.length)
                continue
            }
            val before = lower.substring(maxOf(0, index - 100), index)
            val sameClause = before
                .substringAfterLast(".")
                .substringAfterLast("\n")
                .substringAfterLast(";")
                .substringAfterLast(":")
                .substringAfterLast("—")
            val negated = listOf(
                "не ",
                "без ",
                "нет ",
                "никаких ",
                "никакой ",
                "никакого ",
                "никакая ",
                "no ",
                "not ",
                "нельзя ",
                "запрещено ",
                "не использовать ",
                "не предлагать ",
                "не содержит ",
                "не содержит упоминаний ",
                "не содержит упоминания ",
                "нет упоминаний ",
                "нет упоминания ",
                "без упоминания ",
                "без упоминаний ",
                "отсутств",
                "исключить ",
                "avoid ",
                "do not ",
                "without ",
            ).any { marker -> marker in sameClause.takeLast(80) }
            if (!negated) return true
            index = lower.indexOf(needle, index + needle.length)
        }
        return false
    }

    private fun isAsciiKeyword(value: String): Boolean = value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    private fun isInsideIdentifier(text: String, index: Int, length: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + length)
        return before.isAsciiIdentifierPart() || after.isAsciiIdentifierPart()
    }

    private fun Char?.isAsciiIdentifierPart(): Boolean {
        if (this == null) return false
        return this == '_' || this in 'a'..'z' || this in '0'..'9'
    }
}
