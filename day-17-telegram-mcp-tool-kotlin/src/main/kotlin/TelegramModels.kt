import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
        put(
            "messages",
            JsonArray(
                messages.map { message ->
                    buildJsonObject {
                        put("id", JsonPrimitive(message.id))
                        put("dateIso", JsonPrimitive(message.dateIso))
                        put("sender", JsonPrimitive(message.sender ?: "redacted"))
                        put("text", JsonPrimitive(message.text))
                    }
                },
            ),
        )
    }
}

object TelegramResultFormatter {
    fun format(result: TelegramReadResult): String = buildString {
        appendLine("Telegram messages")
        appendLine("backend: ${result.backend}")
        appendLine("chat: ${result.chat}")
        appendLine("returned: ${result.messages.size} / requested ${result.requestedLimit}")
        appendLine("onlyLocal: ${result.onlyLocal}")
        appendLine("includeSender: ${result.includeSender}")
        appendLine()
        if (result.messages.isEmpty()) {
            appendLine("No messages returned.")
            return@buildString
        }
        result.messages.forEachIndexed { index, message ->
            val sender = if (result.includeSender) " sender=${message.sender ?: "unknown"}" else ""
            appendLine("${index + 1}. [${message.dateIso}]$sender")
            appendLine("   ${message.text.lineSequence().joinToString(" ").take(500)}")
        }
    }
}
