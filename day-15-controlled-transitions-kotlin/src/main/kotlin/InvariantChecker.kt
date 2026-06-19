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
            val beforeChar = lower.getOrNull(index - 1)
            val afterChar = lower.getOrNull(index + needle.length)
            val insideIdentifier = beforeChar == '_' || afterChar == '_'
            if (insideIdentifier) {
                index = lower.indexOf(needle, index + needle.length)
                continue
            }
            val before = lower.substring(maxOf(0, index - 60), index)
            val negated = listOf("не ", "нет ", "без ", "no ", "not ", "нельзя ", "запрещено ", "отсутств")
                .any { marker -> before.trimEnd().endsWith(marker.trimEnd()) || marker in before }
            if (!negated) return true
            index = lower.indexOf(needle, index + needle.length)
        }
        return false
    }
}
