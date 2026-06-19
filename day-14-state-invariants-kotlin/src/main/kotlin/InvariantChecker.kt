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
            val before = lower.substring(maxOf(0, index - 24), index)
            val negated = listOf("не ", "без ", "no ", "not ", "нельзя ", "запрещено ")
                .any { marker -> before.trimEnd().endsWith(marker.trimEnd()) || marker in before.takeLast(14) }
            if (!negated) return true
            index = lower.indexOf(needle, index + needle.length)
        }
        return false
    }
}
