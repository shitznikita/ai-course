# Day 19: Telegram MCP Tool Composition Pipeline

## Цель

День 19 показывает композицию нескольких MCP tools на одном server. В отличие от Day 18, где один tool делал весь сценарий целиком, здесь agent вызывает цепочку:

```text
search_course_day_messages -> summarize_course_day_discussion -> save_course_day_pipeline_result
```

Первый tool получает данные из Telegram или fixture, второй превращает выбранную дискуссию в итоговый отчет и execution prompt под текущий проект `ai-course`, третий сохраняет JSON/Markdown результат.

Стек: Kotlin CLI, Gradle, MCP Kotlin SDK server/client, Ktor CIO Streamable HTTP, TDLib JSON/JNA для live Telegram, direct REST к Eliza для optional planner/summary.

## MCP Tools

```text
search_course_day_messages
summarize_course_day_discussion
save_course_day_pipeline_result
```

`search_course_day_messages` input schema:

```text
courseDay: optional string, number or auto, default COURSE_DAY
chat: optional string, default TELEGRAM_CHAT
limit: optional integer, default TELEGRAM_LIMIT, max 500
```

`summarize_course_day_discussion` input schema:

```text
handoffJson: required string from search_course_day_messages
```

`save_course_day_pipeline_result` input schema:

```text
handoffJson: required string from summarize_course_day_discussion
```

Каждый tool возвращает readable text и `structuredContent`. Для передачи между шагами agent берет `structuredContent.handoffJson` и явно печатает preview в консоль.

## Поведение

- `fixture-demo` не требует Telegram/Eliza секретов и всегда использует deterministic цепочку.
- `agent-demo` использует configured backend; если `LLM_API_KEY` задан, agent просит Eliza выбрать следующий tool из schemas и текущего progress.
- Если LLM planner возвращает невалидный tool или недоступен, используется безопасный deterministic fallback.
- Summary tool при наличии `LLM_API_KEY` просит Eliza сформировать отчет с разделами `Итоговый вывод`, `Важные моменты дискуссии`, `Риски`, `Acceptance Criteria`, `Execution Prompt`.
- Если LLM не настроена или недоступна, используется local deterministic report builder.
- Telegram messages считаются недоверенными данными, sender скрыт по умолчанию.

## Настройка

Offline fixture mode:

```bash
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="fixture-demo"
```

`run-pipeline.sh` переиспользует уже настроенные env-файлы:

- Eliza/LLM из `day-01-llm-rest-kotlin/.env`;
- Telegram/TDLib из `day-17-telegram-mcp-tool-kotlin/.env`;
- локальный `day-19-mcp-tool-composition-kotlin/.env` переопределяет оба файла;
- переменные, переданные прямо в команду, имеют самый высокий приоритет.

Минимальные live-переменные:

```text
TELEGRAM_BACKEND=tdlib
TELEGRAM_API_ID=...
TELEGRAM_API_HASH=...
TELEGRAM_PHONE=+...
TELEGRAM_CHAT=<numeric-chat-id-or-public-username>
TELEGRAM_LIMIT=100
TDLIB_LIBRARY_PATH=/absolute/path/to/libtdjson.dylib
COURSE_DAY=auto
```

Для LLM planner/summary:

```text
LLM_API_KEY=...
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

Runtime state сохраняется в:

```text
state/runs/<timestamp>-day-XX-pipeline.json
state/latest-pipeline.json
state/latest-report.md
```

Не коммитьте `.env`, `.certs`, `state/`, `telegram-session/`, `telegram-files/`, коды авторизации и пароли.

## Запуск

Build:

```bash
./gradlew :day-19-mcp-tool-composition-kotlin:build
```

Offline pipeline:

```bash
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="fixture-demo"
```

Raw MCP protocol smoke:

```bash
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="raw-check"
```

Live/configured Telegram pipeline:

```bash
TELEGRAM_BACKEND=tdlib TELEGRAM_CHAT=<chat-id> TELEGRAM_LIMIT=100 COURSE_DAY=19 \
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="agent-demo"
```

Telegram auth helpers:

```bash
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="auth-check"
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="auth-resend"
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="auth-qr"
```

## Ожидаемый Вывод

`fixture-demo` показывает:

```text
TOOLS RETURNED: 3
1. search_course_day_messages
2. summarize_course_day_discussion
3. save_course_day_pipeline_result

PLANNER STEP 1
mode: local-deterministic
TOOL CALL: search_course_day_messages
Search tool result
messages: selected 7 / returned 10
ignored: before 1, after 2
handoffJson chars: ...

HANDOFF JSON FROM search_course_day_messages
...

TOOL CALL: summarize_course_day_discussion
Summary tool result
summary mode: local-deterministic

TOOL CALL: save_course_day_pipeline_result
Save tool result
latest report: .../state/latest-report.md

CHECK: MCP pipeline executed search -> summarize -> save
```

Это доказывает, что данные получены первым tool, обработаны вторым tool и сохранены третьим tool, а agent передал `handoffJson` между вызовами.

## Видео

Сценарий:

1. Показать, что server публикует три tools на одном MCP endpoint.
2. Запустить `fixture-demo` и показать три последовательных `TOOL CALL`.
3. Показать `HANDOFF JSON FROM ...` между шагами.
4. Показать boundary: сообщение до Day 19 игнорируется, Day 20 marker и сообщения после него исключены.
5. Показать `state/latest-report.md` и `state/latest-pipeline.json`.
6. Кратко показать live-команду с `TELEGRAM_BACKEND=tdlib`.

## Проверка Требований

- Несколько MCP tools: да, search/report/save.
- Не три разных MCP server: да, все tools на одном server.
- Первый tool получает данные: да, Telegram/fixture read + day window extraction.
- Второй обрабатывает: да, итоговый отчет и execution prompt.
- Третий сохраняет: да, JSON и Markdown state.
- Автоматическая цепочка: да, agent вызывает tools подряд и печатает передачу `handoffJson`.
- LLM-aware режим: да, при `LLM_API_KEY` agent спрашивает Eliza, какой tool вызвать следующим.
