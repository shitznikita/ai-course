class InvariantChecker {
    fun check(text: String, invariants: List<Invariant>): ValidationResult {
        val violations = invariants.flatMap { invariant ->
            invariant.forbiddenKeywords
                .filter { hasForbiddenOccurrence(text, it, invariant) }
                .map { "Violation ${invariant.id}: forbidden keyword '$it'. ${invariant.description}" }
        }
        return ValidationResult(violations.isEmpty(), violations)
    }

    private fun hasForbiddenOccurrence(text: String, keyword: String, invariant: Invariant): Boolean {
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
                .substringAfterLast("—")
            val localContext = before.takeLast(100)
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
                "исключает ",
                "исключены ",
                "исключено ",
                "не входит ",
                "out_of_scope",
                "out of scope",
                "forbidden stack absent",
                "запрещенный стек отсутствует",
                "avoid ",
                "do not ",
                "without ",
            ).any { marker -> marker in sameClause.takeLast(100) || marker in localContext }
            if (!negated && isActualViolation(lower, index, needle, sameClause, invariant)) return true
            index = lower.indexOf(needle, index + needle.length)
        }
        return false
    }

    private fun isActualViolation(text: String, index: Int, keyword: String, sameClause: String, invariant: Invariant): Boolean {
        if (invariant.id == "kotlin_only" && keyword == "java") {
            return isJavaLanguageProposal(text, index, sameClause)
        }
        return true
    }

    private fun isJavaLanguageProposal(text: String, index: Int, sameClause: String): Boolean {
        val window = text.substring(maxOf(0, index - 80), minOf(text.length, index + 120))
        val proposalMarkers = listOf(
            "на java",
            "in java",
            "java code",
            "java-код",
            "java class",
            "java implementation",
            "пример на java",
            "код на java",
            "реализац",
            "использовать java",
            "use java",
            "write java",
            "spring boot",
        )
        val safeTechnicalMentions = listOf(
            "java nio",
            "java.nio",
            "java/kotlin nio",
            "kotlin/jvm",
            "jvm",
        )
        if (safeTechnicalMentions.any { it in window }) return false
        return proposalMarkers.any { it in window } || sameClause.trim().startsWith("java:")
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
