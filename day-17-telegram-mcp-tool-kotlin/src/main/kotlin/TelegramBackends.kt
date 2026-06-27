import java.time.Instant

interface TelegramMessageReader {
    fun readMessages(request: TelegramReadRequest): TelegramReadResult
    fun listChats(request: TelegramListChatsRequest): TelegramListChatsResult
}

object TelegramBackends {
    fun create(config: AppConfig): TelegramMessageReader =
        when (config.telegramBackend.lowercase()) {
            "fixture", "mock", "offline" -> FixtureTelegramMessageReader
            "tdlib", "mtproto", "telegram" -> TdlibTelegramMessageReader(config)
            else -> error("Unsupported TELEGRAM_BACKEND=${config.telegramBackend}. Use fixture or tdlib.")
        }
}

object FixtureTelegramMessageReader : TelegramMessageReader {
    private val messages = listOf(
        TelegramMessage(
            id = 101,
            dateIso = "2026-06-27T09:00:00Z",
            sender = "student-1",
            text = "Day 17 task: implement our own MCP server around an API and expose one tool with input schema.",
        ),
        TelegramMessage(
            id = 102,
            dateIso = "2026-06-27T09:04:00Z",
            sender = "mentor",
            text = "A local server is acceptable for the first version. Keep the tool read-only and validate arguments.",
        ),
        TelegramMessage(
            id = 103,
            dateIso = "2026-06-27T09:08:00Z",
            sender = "student-2",
            text = "Telegram is possible through MTProto/TDLib, but Bot API would be simpler. For user history use TDLib.",
        ),
        TelegramMessage(
            id = 104,
            dateIso = "2026-06-27T09:13:00Z",
            sender = "student-1",
            text = "The demo should show tools/list, tools/call, and an agent summary based on returned messages.",
        ),
    )

    private val chats = listOf(
        TelegramChatSummary(
            id = -1001700000001,
            title = "fixture-course-chat",
            type = "supergroup",
            unreadCount = 2,
            lastMessageDateIso = "2026-06-27T09:13:00Z",
        ),
        TelegramChatSummary(
            id = 420000001,
            title = "Private fixture dialog",
            type = "private",
            unreadCount = 0,
            lastMessageDateIso = "2026-06-27T08:30:00Z",
        ),
    )

    override fun readMessages(request: TelegramReadRequest): TelegramReadResult {
        val selected = messages.take(request.limit).map {
            if (request.includeSender) it else it.copy(sender = null)
        }
        return TelegramReadResult(
            backend = "fixture",
            chat = request.chat,
            requestedLimit = request.limit,
            onlyLocal = request.onlyLocal,
            includeSender = request.includeSender,
            messages = selected,
        )
    }

    override fun listChats(request: TelegramListChatsRequest): TelegramListChatsResult =
        TelegramListChatsResult(
            backend = "fixture",
            requestedLimit = request.limit,
            chats = chats.take(request.limit),
        )
}

fun unixSecondsToIso(seconds: Long): String =
    runCatching { Instant.ofEpochSecond(seconds).toString() }.getOrDefault("1970-01-01T00:00:00Z")
