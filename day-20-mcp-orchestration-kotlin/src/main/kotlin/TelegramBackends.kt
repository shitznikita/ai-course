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
            id = 430,
            dateIso = "2026-06-30T09:15:00Z",
            sender = "student-9",
            text = "После Day 21 это сообщение уже не должно попадать в отчет для Day 20.",
        ),
        TelegramMessage(
            id = 429,
            dateIso = "2026-06-30T09:00:00Z",
            sender = "course-bot",
            text = "🔥 День 21. Следующее задание курса. Это marker конца окна для Day 20.",
        ),
        TelegramMessage(
            id = 428,
            dateIso = "2026-06-29T12:45:00Z",
            sender = "mentor",
            text = "Анализ делает LLM на стороне агента, а MCP в этом кейсе тупо помощник в сборе данных и системных вызовах.",
        ),
        TelegramMessage(
            id = 427,
            dateIso = "2026-06-29T12:38:00Z",
            sender = "student-3",
            text = "Если длинный флоу большой, можно разбить данные на чанки, сделать анализ разных кусков и потом общий итог.",
        ),
        TelegramMessage(
            id = 426,
            dateIso = "2026-06-29T12:30:00Z",
            sender = "mentor",
            text = "Общее правило: не использовать ничего модельспецифичного. Завтра поменяешь модель или harness и все сломается.",
        ),
        TelegramMessage(
            id = 425,
            dateIso = "2026-06-29T12:22:00Z",
            sender = "mentor",
            text = "Желательно вообще соркестрировать 3-4 MCP, чтобы получить дополнительные инсайты.",
        ),
        TelegramMessage(
            id = 424,
            dateIso = "2026-06-29T12:16:00Z",
            sender = "mentor",
            text = "Нужно прям большой флоу. Используйте инструменты с разных серверов и проверьте выбор порядка вызовов.",
        ),
        TelegramMessage(
            id = 423,
            dateIso = "2026-06-29T12:08:00Z",
            sender = "student-4",
            text = "Можно текущий MCP разбить на несколько серверов, если tools разной природы: сбор данных, обработка, сохранение.",
        ),
        TelegramMessage(
            id = 422,
            dateIso = "2026-06-29T12:00:00Z",
            sender = "course-bot",
            text = "🔥 День 20. Orchestration MCP. Зарегистрируйте несколько MCP-серверов. Сделайте так, чтобы агент выбирал нужный инструмент, корректно маршрутизировал запросы и выполнял длинный флоу взаимодействия. Проверьте сценарий, в котором используются инструменты с разных серверов, и корректность выбора и порядка вызовов.",
        ),
        TelegramMessage(
            id = 421,
            dateIso = "2026-06-29T11:50:00Z",
            sender = "student-1",
            text = "Это разговор до выдачи Day 20, он должен быть проигнорирован extractor.",
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
