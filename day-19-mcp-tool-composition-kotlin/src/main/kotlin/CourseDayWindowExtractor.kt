import java.time.Instant

object CourseDayWindowExtractor {
    private val dayMarkerRegex = Regex("""(?iu)(?:🔥\s*)?(?:день|day)\s*[:#№.\-]?\s*(\d{1,3})""")
    private val assignmentKeywords = listOf(
        "🔥",
        "задани",
        "task",
        "сделайте",
        "реализ",
        "планировщик",
        "фоновые задачи",
        "mcp",
        "инструмент",
    )

    fun extract(messages: List<TelegramMessage>, requestedCourseDay: String): CourseDayWindow {
        val chronological = messages.sortedWith(compareBy<TelegramMessage> { parseInstant(it.dateIso) }.thenBy { it.id })
        val markers = chronological.flatMapIndexed { index, message -> markersIn(index, message) }
        require(markers.isNotEmpty()) {
            "No course day marker found. Expected text like 'День 19' or 'Day 19' in the last messages."
        }

        val targetDay = if (requestedCourseDay.equals("auto", ignoreCase = true)) {
            markers.last().day
        } else {
            requestedCourseDay.toIntOrNull()
                ?: throw IllegalArgumentException("courseDay must be 'auto' or a number.")
        }
        require(targetDay in 1..999) { "courseDay must be between 1 and 999." }

        val startMarker = markers.firstOrNull { it.day == targetDay }
            ?: throw IllegalArgumentException("No marker for course day $targetDay found in returned Telegram messages.")
        val nextMarker = markers.firstOrNull { it.messageIndex > startMarker.messageIndex && it.day > targetDay }
        val endExclusive = nextMarker?.messageIndex ?: chronological.size
        val selected = chronological.subList(startMarker.messageIndex, endExclusive)

        return CourseDayWindow(
            requestedCourseDay = requestedCourseDay,
            detectedDay = targetDay,
            sourceMessages = chronological,
            selectedMessages = selected,
            markers = markers,
            startMarker = startMarker,
            nextMarker = nextMarker,
            ignoredBefore = startMarker.messageIndex,
            ignoredAfter = chronological.size - endExclusive,
        )
    }

    private fun markersIn(index: Int, message: TelegramMessage): List<DayMarker> {
        val text = message.text
        if (!looksLikeAssignmentMarker(text)) return emptyList()
        val match = dayMarkerRegex.find(text) ?: return emptyList()
        val prefix = text.substring(0, match.range.first).trim()
        if (prefix.isNotEmpty()) return emptyList()
        val day = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        return listOf(
            DayMarker(
                day = day,
                messageIndex = index,
                messageId = message.id,
                dateIso = message.dateIso,
                textPreview = message.text.shortPreview(),
            ),
        )
    }

    private fun looksLikeAssignmentMarker(text: String): Boolean {
        val lower = text.lowercase()
        val startsWithMarker = lower.trimStart().let { it.startsWith("день ") || it.startsWith("day ") || it.startsWith("🔥") }
        return startsWithMarker || assignmentKeywords.any { lower.contains(it) }
    }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)
}
