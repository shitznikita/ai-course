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
            id = 320,
            dateIso = "2026-06-29T09:15:00Z",
            sender = "student-9",
            text = "После Day 20 это сообщение уже не должно попадать в отчет для Day 19.",
        ),
        TelegramMessage(
            id = 319,
            dateIso = "2026-06-29T09:00:00Z",
            sender = "course-bot",
            text = "🔥 День 20. Следующее задание курса. Это marker конца окна для Day 19.",
        ),
        TelegramMessage(
            id = 318,
            dateIso = "2026-06-28T12:10:00Z",
            sender = "mentor",
            text = "Да, главное чтобы прошла цепочка вызовов MCP tool: агент выбирает следующий tool в зависимости от запроса пользователя.",
        ),
        TelegramMessage(
            id = 317,
            dateIso = "2026-06-28T12:03:00Z",
            sender = "student-3",
            text = "То есть search, итоговый отчет и saveToFile должны быть на MCP server, а агент только оркестрирует вызовы?",
        ),
        TelegramMessage(
            id = 316,
            dateIso = "2026-06-28T11:58:00Z",
            sender = "mentor",
            text = "Суммаризация здесь неудачный термин. Имелся в виду просто отчет или итог, не обязательно LLM summary.",
        ),
        TelegramMessage(
            id = 315,
            dateIso = "2026-06-28T11:51:00Z",
            sender = "mentor",
            text = "Нет, все tools должны быть на MCP. Не надо часть логики оставлять только в агенте.",
        ),
        TelegramMessage(
            id = 314,
            dateIso = "2026-06-28T11:45:00Z",
            sender = "mentor",
            text = "Например, пользователь просит: найди лучший ресторан в Питере и сохрани в заметки. MCP ищет, делает итог, отвечает и сохраняет в файл.",
        ),
        TelegramMessage(
            id = 313,
            dateIso = "2026-06-28T11:40:00Z",
            sender = "mentor",
            text = "Не надо делать 3 разных MCP server. Здесь речь про несколько tools на одном MCP server.",
        ),
        TelegramMessage(
            id = 312,
            dateIso = "2026-06-28T11:30:00Z",
            sender = "course-bot",
            text = "🔥 День 19. Композиция MCP-инструментов. Создайте несколько MCP-инструментов, например search, summarize, saveToFile. Реализуйте пайплайн: первый инструмент получает данные, второй обрабатывает, третий сохраняет результат. Проверьте автоматическое выполнение цепочки и корректность передачи данных между инструментами.",
        ),
        TelegramMessage(
            id = 311,
            dateIso = "2026-06-28T11:20:00Z",
            sender = "student-1",
            text = "Это разговор до выдачи Day 19, он должен быть проигнорирован extractor.",
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
