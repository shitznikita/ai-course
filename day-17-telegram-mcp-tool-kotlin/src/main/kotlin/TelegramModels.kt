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

data class TelegramListChatsRequest(
    val limit: Int,
)

data class TelegramChatSummary(
    val id: Long,
    val title: String,
    val type: String,
    val unreadCount: Int,
    val lastMessageDateIso: String?,
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

data class TelegramListChatsResult(
    val backend: String,
    val requestedLimit: Int,
    val chats: List<TelegramChatSummary>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("backend", JsonPrimitive(backend))
        put("requestedLimit", JsonPrimitive(requestedLimit))
        put("returned", JsonPrimitive(chats.size))
        put(
            "chats",
            JsonArray(
                chats.map { chat ->
                    buildJsonObject {
                        put("id", JsonPrimitive(chat.id))
                        put("title", JsonPrimitive(chat.title))
                        put("type", JsonPrimitive(chat.type))
                        put("unreadCount", JsonPrimitive(chat.unreadCount))
                        put("lastMessageDateIso", JsonPrimitive(chat.lastMessageDateIso ?: "unknown"))
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

    fun formatChats(result: TelegramListChatsResult): String = buildString {
        appendLine("Telegram chats")
        appendLine("backend: ${result.backend}")
        appendLine("returned: ${result.chats.size} / requested ${result.requestedLimit}")
        appendLine()
        if (result.chats.isEmpty()) {
            appendLine("No chats returned.")
            return@buildString
        }
        result.chats.forEachIndexed { index, chat ->
            appendLine("${index + 1}. ${chat.title}")
            appendLine("   id=${chat.id}")
            appendLine("   type=${chat.type}, unread=${chat.unreadCount}, last=${chat.lastMessageDateIso ?: "unknown"}")
        }
    }
}
