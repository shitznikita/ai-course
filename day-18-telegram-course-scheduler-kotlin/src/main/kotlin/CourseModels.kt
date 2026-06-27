import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class TelegramReadRequest(
    val chat: String,
    val limit: Int,
    val onlyLocal: Boolean,
    val includeSender: Boolean,
)

data class TelegramMessage(
    val id: Long,
    val dateIso: String,
    val sender: String?,
    val text: String,
)

data class TelegramReadResult(
    val backend: String,
    val chat: String,
    val requestedLimit: Int,
    val onlyLocal: Boolean,
    val includeSender: Boolean,
    val messages: List<TelegramMessage>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("backend", JsonPrimitive(backend))
        put("chat", JsonPrimitive(chat))
        put("requestedLimit", JsonPrimitive(requestedLimit))
        put("returned", JsonPrimitive(messages.size))
        put("onlyLocal", JsonPrimitive(onlyLocal))
        put("includeSender", JsonPrimitive(includeSender))
        put("messages", JsonArray(messages.map { it.toJson() }))
    }
}

data class CourseDayPromptRequest(
    val courseDay: String,
    val chat: String,
    val limit: Int,
    val persist: Boolean,
)

data class DayMarker(
    val day: Int,
    val messageIndex: Int,
    val messageId: Long,
    val dateIso: String,
    val textPreview: String,
)

data class CourseDayWindow(
    val requestedCourseDay: String,
    val detectedDay: Int,
    val sourceMessages: List<TelegramMessage>,
    val selectedMessages: List<TelegramMessage>,
    val markers: List<DayMarker>,
    val startMarker: DayMarker,
    val nextMarker: DayMarker?,
    val ignoredBefore: Int,
    val ignoredAfter: Int,
)

data class GeneratedPrompt(
    val mode: String,
    val highlights: List<String>,
    val prompt: String,
)

data class StoragePaths(
    val runJson: String,
    val latestRunJson: String,
    val latestPromptMarkdown: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("runJson", JsonPrimitive(runJson))
        put("latestRunJson", JsonPrimitive(latestRunJson))
        put("latestPromptMarkdown", JsonPrimitive(latestPromptMarkdown))
    }
}

data class CourseDayPromptResult(
    val generatedAtIso: String,
    val requestedCourseDay: String,
    val detectedDay: Int,
    val telegram: TelegramReadResult,
    val selectedMessages: List<TelegramMessage>,
    val markers: List<DayMarker>,
    val ignoredBefore: Int,
    val ignoredAfter: Int,
    val nextMarkerDay: Int?,
    val llmMode: String,
    val highlights: List<String>,
    val prompt: String,
    val storage: StoragePaths?,
) {
    fun withStorage(paths: StoragePaths): CourseDayPromptResult = copy(storage = paths)

    fun toJson(): JsonObject = buildJsonObject {
        put("generatedAtIso", JsonPrimitive(generatedAtIso))
        put("requestedCourseDay", JsonPrimitive(requestedCourseDay))
        put("detectedDay", JsonPrimitive(detectedDay))
        put("chat", JsonPrimitive(telegram.chat))
        put("backend", JsonPrimitive(telegram.backend))
        put("requestedLimit", JsonPrimitive(telegram.requestedLimit))
        put("returnedMessages", JsonPrimitive(telegram.messages.size))
        put("selectedMessages", JsonPrimitive(selectedMessages.size))
        put("ignoredBefore", JsonPrimitive(ignoredBefore))
        put("ignoredAfter", JsonPrimitive(ignoredAfter))
        put("nextMarkerDay", JsonPrimitive(nextMarkerDay?.toString() ?: "none"))
        put("llmMode", JsonPrimitive(llmMode))
        put(
            "selectedRange",
            buildJsonObject {
                put("startMessageId", JsonPrimitive(selectedMessages.firstOrNull()?.id?.toString() ?: "none"))
                put("startDateIso", JsonPrimitive(selectedMessages.firstOrNull()?.dateIso ?: "none"))
                put("endMessageId", JsonPrimitive(selectedMessages.lastOrNull()?.id?.toString() ?: "none"))
                put("endDateIso", JsonPrimitive(selectedMessages.lastOrNull()?.dateIso ?: "none"))
            },
        )
        put("markers", JsonArray(markers.map { it.toJson() }))
        put("highlights", JsonArray(highlights.map { JsonPrimitive(it) }))
        put("messages", JsonArray(selectedMessages.map { it.toJson() }))
        put("prompt", JsonPrimitive(prompt))
        storage?.let { put("storage", it.toJson()) }
    }
}

data class LatestPromptResult(
    val latestRunJson: String,
    val latestPromptMarkdown: String,
    val prompt: String,
)

object CourseResultFormatter {
    fun format(result: CourseDayPromptResult): String = buildString {
        appendLine("Course day prompt generated")
        appendLine("day: ${result.detectedDay} (requested ${result.requestedCourseDay})")
        appendLine("backend: ${result.telegram.backend}")
        appendLine("chat: ${result.telegram.chat}")
        appendLine("messages: selected ${result.selectedMessages.size} / returned ${result.telegram.messages.size}")
        appendLine("ignored: before ${result.ignoredBefore}, after ${result.ignoredAfter}")
        appendLine("next day marker excluded: ${result.nextMarkerDay ?: "none"}")
        appendLine("llm mode: ${result.llmMode}")
        result.storage?.let {
            appendLine("run json: ${it.runJson}")
            appendLine("latest prompt: ${it.latestPromptMarkdown}")
        }
        appendLine()
        appendLine("Highlights")
        if (result.highlights.isEmpty()) {
            appendLine("- No discussion highlights were detected.")
        } else {
            result.highlights.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Generated prompt")
        appendLine(result.prompt)
    }

    fun formatLatest(result: LatestPromptResult): String = buildString {
        appendLine("Latest saved course day prompt")
        appendLine("run json: ${result.latestRunJson}")
        appendLine("prompt markdown: ${result.latestPromptMarkdown}")
        appendLine()
        appendLine(result.prompt)
    }
}

fun TelegramMessage.toJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("dateIso", JsonPrimitive(dateIso))
    put("sender", JsonPrimitive(sender ?: "redacted"))
    put("text", JsonPrimitive(text))
}

private fun DayMarker.toJson(): JsonObject = buildJsonObject {
    put("day", JsonPrimitive(day))
    put("messageIndex", JsonPrimitive(messageIndex))
    put("messageId", JsonPrimitive(messageId))
    put("dateIso", JsonPrimitive(dateIso))
    put("textPreview", JsonPrimitive(textPreview))
}

fun String.shortPreview(maxLength: Int = 220): String {
    val compact = lineSequence().joinToString(" ") { it.trim() }.replace(Regex("""\s+"""), " ").trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength - 1).trimEnd() + "..."
}
