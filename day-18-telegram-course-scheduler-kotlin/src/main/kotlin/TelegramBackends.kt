import java.time.Instant

interface TelegramMessageReader {
    fun readMessages(request: TelegramReadRequest): TelegramReadResult
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
    private val messagesLatestFirst = listOf(
        TelegramMessage(
            id = 208,
            dateIso = "2026-06-28T09:15:00Z",
            sender = "student-3",
            text = "После выдачи 19 дня это сообщение уже не должно попадать в prompt для 18 дня.",
        ),
        TelegramMessage(
            id = 207,
            dateIso = "2026-06-28T09:00:00Z",
            sender = "mentor",
            text = "🔥 День 19. Следующее задание курса. Это marker конца окна для Day 18.",
        ),
        TelegramMessage(
            id = 206,
            dateIso = "2026-06-27T09:35:00Z",
            sender = "student-2",
            text = "Результат лучше сохранять в JSON, а рядом держать latest-prompt.md для вставки в GPT 5.5/Codex.",
        ),
        TelegramMessage(
            id = 205,
            dateIso = "2026-06-27T09:28:00Z",
            sender = "mentor",
            text = "VPS не обязателен. Для видео достаточно показать, что задача срабатывает по расписанию.",
        ),
        TelegramMessage(
            id = 204,
            dateIso = "2026-06-27T09:20:00Z",
            sender = "student-1",
            text = "В моей картине мира scheduler дергает агента, агент вызывает MCP, а MCP server пассивно обрабатывает tools/call.",
        ),
        TelegramMessage(
            id = 203,
            dateIso = "2026-06-27T09:13:00Z",
            sender = "mentor",
            text = "MCP tool должен сохранять данные, выполняться по расписанию и возвращать агрегированный результат.",
        ),
        TelegramMessage(
            id = 202,
            dateIso = "2026-06-27T09:00:00Z",
            sender = "course-bot",
            text = "🔥 День 18. Планировщик и фоновые задачи. Сделайте MCP-инструмент с отложенным или периодическим выполнением: reminder, периодический сбор данных или регулярный summary.",
        ),
        TelegramMessage(
            id = 201,
            dateIso = "2026-06-27T08:50:00Z",
            sender = "student-4",
            text = "Это обычный разговор до задания нового дня, его нужно игнорировать.",
        ),
    )

    override fun readMessages(request: TelegramReadRequest): TelegramReadResult {
        val selected = messagesLatestFirst.take(request.limit).map {
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
}

fun unixSecondsToIso(seconds: Long): String =
    runCatching { Instant.ofEpochSecond(seconds).toString() }.getOrDefault("1970-01-01T00:00:00Z")
