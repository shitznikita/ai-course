class InvariantChecker {
    fun check(text: String, where: String, invariants: List<Invariant>): ValidationResult {
        val violations = invariants.flatMap { invariant ->
            checkInvariant(text, where, invariant)
        }
        return ValidationResult(valid = violations.isEmpty(), violations = violations)
    }

    private fun checkInvariant(text: String, where: String, invariant: Invariant): List<Violation> {
        val keywordViolations = invariant.forbiddenKeywords
            .filter { it.isNotBlank() && hasForbiddenOccurrence(text, it) }
            .map { keyword ->
                Violation(
                    invariantId = invariant.id,
                    severity = invariant.severity,
                    where = where,
                    message = "Text contains forbidden keyword '$keyword'. ${invariant.description}",
                )
            }
        val patternViolations = invariant.forbiddenPatterns
            .filter { it.isNotBlank() && text.contains(it, ignoreCase = true) }
            .map { pattern ->
                Violation(
                    invariantId = invariant.id,
                    severity = invariant.severity,
                    where = where,
                    message = "Text contains forbidden pattern '$pattern'. ${invariant.description}",
                )
            }
        return keywordViolations + patternViolations
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
            val before = lower.substring(maxOf(0, index - 80), index)
            val sameClause = before.substringAfterLast(".").substringAfterLast("\n").substringAfterLast(";")
            val negated = listOf(
                "не ",
                "без ",
                "нет ",
                "no ",
                "not ",
                "нельзя ",
                "запрещено ",
                "не использовать ",
                "не предлагать ",
                "не содержит ",
                "не могу ",
                "не буду ",
                "нет упоминаний ",
                "нет упоминания ",
                "без упоминания ",
                "отсутств",
                "откажусь от ",
                "исключить ",
                "avoid ",
                "do not ",
                "without ",
            ).any { marker -> marker in sameClause.takeLast(64) }
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
