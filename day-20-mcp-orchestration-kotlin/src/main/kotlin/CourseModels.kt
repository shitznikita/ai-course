import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PipelineJson {
    val compact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun compactString(value: JsonObject): String = compact.encodeToString(JsonObject.serializer(), value)
    fun prettyString(value: JsonObject): String = pretty.encodeToString(JsonObject.serializer(), value)
    fun parseObject(value: String): JsonObject = compact.parseToJsonElement(value).jsonObject
}

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
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("dateIso", JsonPrimitive(dateIso))
        put("sender", JsonPrimitive(sender ?: "redacted"))
        put("text", JsonPrimitive(text))
    }

    companion object {
        fun fromJson(value: JsonObject): TelegramMessage =
            TelegramMessage(
                id = value.longValue("id"),
                dateIso = value.stringValue("dateIso"),
                sender = value.optionalStringValue("sender")?.takeUnless { it == "redacted" },
                text = value.stringValue("text"),
            )
    }
}

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

    companion object {
        fun fromJson(value: JsonObject): TelegramReadResult =
            TelegramReadResult(
                backend = value.stringValue("backend"),
                chat = value.stringValue("chat"),
                requestedLimit = value.intValue("requestedLimit"),
                onlyLocal = value.booleanValue("onlyLocal"),
                includeSender = value.booleanValue("includeSender"),
                messages = value.arrayValue("messages").map { TelegramMessage.fromJson(it.jsonObject) },
            )
    }
}

data class SourceMessagesResult(
    val generatedAtIso: String,
    val telegram: TelegramReadResult,
) {
    fun handoffJson(): String = PipelineJson.compactString(toJson(includeHandoff = false))

    fun toJson(includeHandoff: Boolean = true): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("source"))
        put("generatedAtIso", JsonPrimitive(generatedAtIso))
        put("telegram", telegram.toJson())
        if (includeHandoff) put("handoffJson", JsonPrimitive(handoffJson()))
    }

    companion object {
        fun fromHandoffJson(value: String): SourceMessagesResult {
            val root = PipelineJson.parseObject(value)
            return SourceMessagesResult(
                generatedAtIso = root.stringValue("generatedAtIso"),
                telegram = TelegramReadResult.fromJson(root.objectValue("telegram")),
            )
        }
    }
}

data class DayMarker(
    val day: Int,
    val messageIndex: Int,
    val messageId: Long,
    val dateIso: String,
    val textPreview: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("day", JsonPrimitive(day))
        put("messageIndex", JsonPrimitive(messageIndex))
        put("messageId", JsonPrimitive(messageId))
        put("dateIso", JsonPrimitive(dateIso))
        put("textPreview", JsonPrimitive(textPreview))
    }

    companion object {
        fun fromJson(value: JsonObject): DayMarker =
            DayMarker(
                day = value.intValue("day"),
                messageIndex = value.intValue("messageIndex"),
                messageId = value.longValue("messageId"),
                dateIso = value.stringValue("dateIso"),
                textPreview = value.stringValue("textPreview"),
            )
    }
}

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

data class CourseDayWindowResult(
    val generatedAtIso: String,
    val requestedCourseDay: String,
    val detectedDay: Int,
    val telegram: TelegramReadResult,
    val selectedMessages: List<TelegramMessage>,
    val markers: List<DayMarker>,
    val ignoredBefore: Int,
    val ignoredAfter: Int,
    val nextMarkerDay: Int?,
) {
    fun handoffJson(): String = PipelineJson.compactString(toJson(includeHandoff = false))

    fun toJson(includeHandoff: Boolean = true): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("window"))
        put("generatedAtIso", JsonPrimitive(generatedAtIso))
        put("requestedCourseDay", JsonPrimitive(requestedCourseDay))
        put("detectedDay", JsonPrimitive(detectedDay))
        put("telegram", telegram.toJson())
        put("selectedMessages", JsonArray(selectedMessages.map { it.toJson() }))
        put("selectedMessageCount", JsonPrimitive(selectedMessages.size))
        put("ignoredBefore", JsonPrimitive(ignoredBefore))
        put("ignoredAfter", JsonPrimitive(ignoredAfter))
        put("nextMarkerDay", nextMarkerDay?.let { JsonPrimitive(it) } ?: JsonNull)
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
        if (includeHandoff) put("handoffJson", JsonPrimitive(handoffJson()))
    }

    companion object {
        fun fromHandoffJson(value: String): CourseDayWindowResult {
            val root = PipelineJson.parseObject(value)
            return CourseDayWindowResult(
                generatedAtIso = root.stringValue("generatedAtIso"),
                requestedCourseDay = root.stringValue("requestedCourseDay"),
                detectedDay = root.intValue("detectedDay"),
                telegram = TelegramReadResult.fromJson(root.objectValue("telegram")),
                selectedMessages = root.arrayValue("selectedMessages").map { TelegramMessage.fromJson(it.jsonObject) },
                markers = root.arrayValue("markers").map { DayMarker.fromJson(it.jsonObject) },
                ignoredBefore = root.intValue("ignoredBefore"),
                ignoredAfter = root.intValue("ignoredAfter"),
                nextMarkerDay = root.optionalIntValue("nextMarkerDay"),
            )
        }
    }
}

data class DiscussionChunk(
    val index: Int,
    val messageCount: Int,
    val startMessageId: Long,
    val endMessageId: Long,
    val text: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("index", JsonPrimitive(index))
        put("messageCount", JsonPrimitive(messageCount))
        put("startMessageId", JsonPrimitive(startMessageId))
        put("endMessageId", JsonPrimitive(endMessageId))
        put("text", JsonPrimitive(text))
    }

    companion object {
        fun fromJson(value: JsonObject): DiscussionChunk =
            DiscussionChunk(
                index = value.intValue("index"),
                messageCount = value.intValue("messageCount"),
                startMessageId = value.longValue("startMessageId"),
                endMessageId = value.longValue("endMessageId"),
                text = value.stringValue("text"),
            )
    }
}

data class DiscussionChunksResult(
    val generatedAtIso: String,
    val requestedCourseDay: String,
    val detectedDay: Int,
    val chat: String,
    val backend: String,
    val sourceMessageCount: Int,
    val selectedMessageCount: Int,
    val ignoredBefore: Int,
    val ignoredAfter: Int,
    val nextMarkerDay: Int?,
    val selectedMessages: List<TelegramMessage>,
    val chunks: List<DiscussionChunk>,
) {
    fun handoffJson(): String = PipelineJson.compactString(toJson(includeHandoff = false))

    fun toJson(includeHandoff: Boolean = true): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("chunks"))
        put("generatedAtIso", JsonPrimitive(generatedAtIso))
        put("requestedCourseDay", JsonPrimitive(requestedCourseDay))
        put("detectedDay", JsonPrimitive(detectedDay))
        put("chat", JsonPrimitive(chat))
        put("backend", JsonPrimitive(backend))
        put("sourceMessageCount", JsonPrimitive(sourceMessageCount))
        put("selectedMessageCount", JsonPrimitive(selectedMessageCount))
        put("ignoredBefore", JsonPrimitive(ignoredBefore))
        put("ignoredAfter", JsonPrimitive(ignoredAfter))
        put("nextMarkerDay", nextMarkerDay?.let { JsonPrimitive(it) } ?: JsonNull)
        put("selectedMessages", JsonArray(selectedMessages.map { it.toJson() }))
        put("chunks", JsonArray(chunks.map { it.toJson() }))
        if (includeHandoff) put("handoffJson", JsonPrimitive(handoffJson()))
    }

    companion object {
        fun fromHandoffJson(value: String): DiscussionChunksResult {
            val root = PipelineJson.parseObject(value)
            return DiscussionChunksResult(
                generatedAtIso = root.stringValue("generatedAtIso"),
                requestedCourseDay = root.stringValue("requestedCourseDay"),
                detectedDay = root.intValue("detectedDay"),
                chat = root.stringValue("chat"),
                backend = root.stringValue("backend"),
                sourceMessageCount = root.intValue("sourceMessageCount"),
                selectedMessageCount = root.intValue("selectedMessageCount"),
                ignoredBefore = root.intValue("ignoredBefore"),
                ignoredAfter = root.intValue("ignoredAfter"),
                nextMarkerDay = root.optionalIntValue("nextMarkerDay"),
                selectedMessages = root.arrayValue("selectedMessages").map { TelegramMessage.fromJson(it.jsonObject) },
                chunks = root.arrayValue("chunks").map { DiscussionChunk.fromJson(it.jsonObject) },
            )
        }
    }
}

data class AgentAnalysisResult(
    val generatedAtIso: String,
    val requestedCourseDay: String,
    val detectedDay: Int,
    val chat: String,
    val backend: String,
    val analysisMode: String,
    val finalConclusion: String,
    val chunkInsights: List<String>,
    val importantDiscussionPoints: List<String>,
    val risks: List<String>,
    val acceptanceCriteria: List<String>,
    val selectedMessages: List<TelegramMessage>,
    val chunks: List<DiscussionChunk>,
) {
    fun handoffJson(): String = PipelineJson.compactString(toJson(includeHandoff = false))

    fun toJson(includeHandoff: Boolean = true): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("agent-analysis"))
        put("generatedAtIso", JsonPrimitive(generatedAtIso))
        put("requestedCourseDay", JsonPrimitive(requestedCourseDay))
        put("detectedDay", JsonPrimitive(detectedDay))
        put("chat", JsonPrimitive(chat))
        put("backend", JsonPrimitive(backend))
        put("analysisMode", JsonPrimitive(analysisMode))
        put("finalConclusion", JsonPrimitive(finalConclusion))
        putStringArray("chunkInsights", chunkInsights)
        putStringArray("importantDiscussionPoints", importantDiscussionPoints)
        putStringArray("risks", risks)
        putStringArray("acceptanceCriteria", acceptanceCriteria)
        put("selectedMessages", JsonArray(selectedMessages.map { it.toJson() }))
        put("chunks", JsonArray(chunks.map { it.toJson() }))
        if (includeHandoff) put("handoffJson", JsonPrimitive(handoffJson()))
    }

    companion object {
        fun fromHandoffJson(value: String): AgentAnalysisResult {
            val root = PipelineJson.parseObject(value)
            return AgentAnalysisResult(
                generatedAtIso = root.stringValue("generatedAtIso"),
                requestedCourseDay = root.stringValue("requestedCourseDay"),
                detectedDay = root.intValue("detectedDay"),
                chat = root.stringValue("chat"),
                backend = root.stringValue("backend"),
                analysisMode = root.stringValue("analysisMode"),
                finalConclusion = root.stringValue("finalConclusion"),
                chunkInsights = root.stringArray("chunkInsights"),
                importantDiscussionPoints = root.stringArray("importantDiscussionPoints"),
                risks = root.stringArray("risks"),
                acceptanceCriteria = root.stringArray("acceptanceCriteria"),
                selectedMessages = root.arrayValue("selectedMessages").map { TelegramMessage.fromJson(it.jsonObject) },
                chunks = root.arrayValue("chunks").map { DiscussionChunk.fromJson(it.jsonObject) },
            )
        }
    }
}

data class ExecutionBriefResult(
    val generatedAtIso: String,
    val detectedDay: Int,
    val chat: String,
    val backend: String,
    val analysisMode: String,
    val finalConclusion: String,
    val importantDiscussionPoints: List<String>,
    val risks: List<String>,
    val acceptanceCriteria: List<String>,
    val reportMarkdown: String,
    val selectedMessages: List<TelegramMessage>,
) {
    fun handoffJson(): String = PipelineJson.compactString(toJson(includeHandoff = false))

    fun toJson(includeHandoff: Boolean = true): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("brief"))
        put("generatedAtIso", JsonPrimitive(generatedAtIso))
        put("detectedDay", JsonPrimitive(detectedDay))
        put("chat", JsonPrimitive(chat))
        put("backend", JsonPrimitive(backend))
        put("analysisMode", JsonPrimitive(analysisMode))
        put("finalConclusion", JsonPrimitive(finalConclusion))
        putStringArray("importantDiscussionPoints", importantDiscussionPoints)
        putStringArray("risks", risks)
        putStringArray("acceptanceCriteria", acceptanceCriteria)
        put("reportMarkdown", JsonPrimitive(reportMarkdown))
        put("selectedMessages", JsonArray(selectedMessages.map { it.toJson() }))
        if (includeHandoff) put("handoffJson", JsonPrimitive(handoffJson()))
    }

    companion object {
        fun fromHandoffJson(value: String): ExecutionBriefResult {
            val root = PipelineJson.parseObject(value)
            return ExecutionBriefResult(
                generatedAtIso = root.stringValue("generatedAtIso"),
                detectedDay = root.intValue("detectedDay"),
                chat = root.stringValue("chat"),
                backend = root.stringValue("backend"),
                analysisMode = root.stringValue("analysisMode"),
                finalConclusion = root.stringValue("finalConclusion"),
                importantDiscussionPoints = root.stringArray("importantDiscussionPoints"),
                risks = root.stringArray("risks"),
                acceptanceCriteria = root.stringArray("acceptanceCriteria"),
                reportMarkdown = root.stringValue("reportMarkdown"),
                selectedMessages = root.arrayValue("selectedMessages").map { TelegramMessage.fromJson(it.jsonObject) },
            )
        }
    }
}

data class CodexPromptResult(
    val generatedAtIso: String,
    val detectedDay: Int,
    val chat: String,
    val backend: String,
    val reportMarkdown: String,
    val codexPrompt: String,
    val selectedMessages: List<TelegramMessage>,
) {
    fun handoffJson(): String = PipelineJson.compactString(toJson(includeHandoff = false))

    fun toJson(includeHandoff: Boolean = true): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("prompt"))
        put("generatedAtIso", JsonPrimitive(generatedAtIso))
        put("detectedDay", JsonPrimitive(detectedDay))
        put("chat", JsonPrimitive(chat))
        put("backend", JsonPrimitive(backend))
        put("reportMarkdown", JsonPrimitive(reportMarkdown))
        put("codexPrompt", JsonPrimitive(codexPrompt))
        put("selectedMessages", JsonArray(selectedMessages.map { it.toJson() }))
        if (includeHandoff) put("handoffJson", JsonPrimitive(handoffJson()))
    }

    companion object {
        fun fromHandoffJson(value: String): CodexPromptResult {
            val root = PipelineJson.parseObject(value)
            return CodexPromptResult(
                generatedAtIso = root.stringValue("generatedAtIso"),
                detectedDay = root.intValue("detectedDay"),
                chat = root.stringValue("chat"),
                backend = root.stringValue("backend"),
                reportMarkdown = root.stringValue("reportMarkdown"),
                codexPrompt = root.stringValue("codexPrompt"),
                selectedMessages = root.arrayValue("selectedMessages").map { TelegramMessage.fromJson(it.jsonObject) },
            )
        }
    }
}

data class OrchestrationStoragePaths(
    val runJson: String,
    val latestOrchestrationJson: String,
    val latestReportMarkdown: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("runJson", JsonPrimitive(runJson))
        put("latestOrchestrationJson", JsonPrimitive(latestOrchestrationJson))
        put("latestReportMarkdown", JsonPrimitive(latestReportMarkdown))
    }
}

data class SaveOrchestrationResult(
    val savedAtIso: String,
    val prompt: CodexPromptResult,
    val storage: OrchestrationStoragePaths,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("save"))
        put("savedAtIso", JsonPrimitive(savedAtIso))
        put("prompt", prompt.toJson(includeHandoff = false))
        put("storage", storage.toJson())
    }
}

data class LatestOrchestrationArtifactResult(
    val readAtIso: String,
    val exists: Boolean,
    val latestOrchestrationJson: String,
    val latestReportMarkdown: String,
    val detectedDay: Int?,
    val reportPreview: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive("latest-read"))
        put("readAtIso", JsonPrimitive(readAtIso))
        put("exists", JsonPrimitive(exists))
        put("latestOrchestrationJson", JsonPrimitive(latestOrchestrationJson))
        put("latestReportMarkdown", JsonPrimitive(latestReportMarkdown))
        put("detectedDay", detectedDay?.let { JsonPrimitive(it) } ?: JsonNull)
        put("reportPreview", JsonPrimitive(reportPreview))
    }
}

object OrchestrationResultFormatter {
    fun formatSource(result: SourceMessagesResult): String = buildString {
        appendLine("Source tool result")
        appendLine("backend: ${result.telegram.backend}")
        appendLine("chat: ${result.telegram.chat}")
        appendLine("returned: ${result.telegram.messages.size} / requested ${result.telegram.requestedLimit}")
        appendLine("handoffJson chars: ${result.handoffJson().length}")
    }

    fun formatWindow(result: CourseDayWindowResult): String = buildString {
        appendLine("Window tool result")
        appendLine("day: ${result.detectedDay} (requested ${result.requestedCourseDay})")
        appendLine("messages: selected ${result.selectedMessages.size} / returned ${result.telegram.messages.size}")
        appendLine("ignored: before ${result.ignoredBefore}, after ${result.ignoredAfter}")
        appendLine("next day marker excluded: ${result.nextMarkerDay ?: "none"}")
        appendLine("handoffJson chars: ${result.handoffJson().length}")
    }

    fun formatChunks(result: DiscussionChunksResult): String = buildString {
        appendLine("Chunk tool result")
        appendLine("day: ${result.detectedDay}")
        appendLine("chunks: ${result.chunks.size}")
        appendLine("selected messages: ${result.selectedMessageCount}")
        appendLine("handoffJson chars: ${result.handoffJson().length}")
    }

    fun formatAnalysis(result: AgentAnalysisResult): String = buildString {
        appendLine("Agent analysis result")
        appendLine("mode: ${result.analysisMode}")
        appendLine("final conclusion: ${result.finalConclusion}")
        appendLine("points: ${result.importantDiscussionPoints.size}")
        appendLine("handoffJson chars: ${result.handoffJson().length}")
    }

    fun formatBrief(result: ExecutionBriefResult): String = buildString {
        appendLine("Brief tool result")
        appendLine("day: ${result.detectedDay}")
        appendLine("analysis mode: ${result.analysisMode}")
        appendLine("final conclusion: ${result.finalConclusion}")
        appendLine("handoffJson chars: ${result.handoffJson().length}")
        appendLine()
        appendLine(result.reportMarkdown.shortPreview(1400))
    }

    fun formatPrompt(result: CodexPromptResult): String = buildString {
        appendLine("Prompt tool result")
        appendLine("day: ${result.detectedDay}")
        appendLine("prompt chars: ${result.codexPrompt.length}")
        appendLine("handoffJson chars: ${result.handoffJson().length}")
        appendLine()
        appendLine(result.codexPrompt.shortPreview(1400))
    }

    fun formatSave(result: SaveOrchestrationResult): String = buildString {
        appendLine("Save tool result")
        appendLine("day: ${result.prompt.detectedDay}")
        appendLine("run json: ${result.storage.runJson}")
        appendLine("latest orchestration json: ${result.storage.latestOrchestrationJson}")
        appendLine("latest report: ${result.storage.latestReportMarkdown}")
    }

    fun formatLatest(result: LatestOrchestrationArtifactResult): String = buildString {
        appendLine("Latest artifact tool result")
        appendLine("exists: ${result.exists}")
        appendLine("detected day: ${result.detectedDay ?: "none"}")
        appendLine("latest orchestration json: ${result.latestOrchestrationJson}")
        appendLine("latest report: ${result.latestReportMarkdown}")
        appendLine("report preview: ${result.reportPreview}")
    }
}

fun JsonObject.stringValue(name: String): String =
    optionalStringValue(name) ?: error("Missing required string '$name'.")

fun JsonObject.optionalStringValue(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

fun JsonObject.intValue(name: String): Int =
    optionalIntValue(name) ?: error("Missing required integer '$name'.")

fun JsonObject.optionalIntValue(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

fun JsonObject.longValue(name: String): Long =
    this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: error("Missing required long '$name'.")

fun JsonObject.booleanValue(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: false

fun JsonObject.objectValue(name: String): JsonObject =
    this[name]?.jsonObject ?: error("Missing required object '$name'.")

fun JsonObject.arrayValue(name: String): JsonArray =
    this[name]?.jsonArray ?: JsonArray(emptyList())

fun JsonObject.stringArray(name: String): List<String> =
    arrayValue(name).mapNotNull { it.jsonPrimitive.contentOrNull }

fun JsonObject.optionalJson(name: String): JsonElement? = this[name]

fun kotlinx.serialization.json.JsonObjectBuilder.putStringArray(name: String, values: List<String>) {
    put(
        name,
        buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        },
    )
}

fun String.shortPreview(maxLength: Int = 220): String {
    val compact = lineSequence().joinToString(" ") { it.trim() }.replace(Regex("""\s+"""), " ").trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength - 1).trimEnd() + "..."
}
