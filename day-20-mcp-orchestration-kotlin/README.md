# Day 20: Multi-Server MCP Orchestration

## Цель

День 20 показывает orchestration поверх нескольких MCP servers. В отличие от Day 19, где несколько tools жили на одном server, здесь agent регистрирует четыре разных MCP endpoint, делает discovery, выбирает нужные tools и выполняет длинный flow с передачей `handoffJson` между серверами.

Стек: Kotlin CLI, Gradle, MCP Kotlin SDK server/client, Ktor CIO Streamable HTTP, TDLib JSON/JNA для live Telegram, direct REST к Eliza для optional agent planner/analysis.

## MCP Servers

```text
source-mcp   http://127.0.0.1:3020/mcp
window-mcp   http://127.0.0.1:3021/mcp
brief-mcp    http://127.0.0.1:3022/mcp
storage-mcp  http://127.0.0.1:3023/mcp
```

Tools:

```text
source/read_course_chat_messages
window/extract_course_day_window
window/chunk_course_discussion
brief/build_execution_brief
brief/build_codex_execution_prompt
storage/save_orchestration_artifacts
storage/read_latest_orchestration_artifact
```

Agent flow:

```text
read -> extract -> chunk -> agent analysis -> brief -> prompt -> save -> read latest
```

LLM-логика находится в agent, а не внутри MCP tools. MCP servers остаются адаптерами: чтение данных, выделение окна, chunking, сборка artifacts и storage.

## Настройка

Offline fixture mode не требует секретов:

```bash
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="fixture-demo"
```

`run-orchestration.sh` переиспользует уже настроенные env-файлы:

- Eliza/LLM из `day-01-llm-rest-kotlin/.env`;
- Telegram/TDLib из `day-17-telegram-mcp-tool-kotlin/.env`;
- локальный `day-20-mcp-orchestration-kotlin/.env` переопределяет оба файла;
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

Для LLM planner/analysis:

```text
LLM_API_KEY=...
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

Runtime state сохраняется в:

```text
state/runs/<timestamp>-day-XX-orchestration.json
state/latest-orchestration.json
state/latest-report.md
```

Не коммитьте `.env`, `.certs`, `state/`, `telegram-session/`, `telegram-files/`, коды авторизации и пароли.

## Запуск

Build:

```bash
./gradlew :day-20-mcp-orchestration-kotlin:build
```

Offline orchestration:

```bash
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="fixture-demo"
```

Raw MCP protocol smoke:

```bash
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="raw-check"
```

Live/configured Telegram orchestration:

```bash
TELEGRAM_BACKEND=tdlib TELEGRAM_CHAT=<chat-id> TELEGRAM_LIMIT=100 COURSE_DAY=20 \
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="agent-demo"
```

Telegram auth helpers:

```bash
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="auth-check"
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="auth-resend"
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="auth-qr"
```

## Ожидаемый Вывод

`fixture-demo` показывает:

```text
REGISTERED MCP SERVERS: 4
source-mcp: http://127.0.0.1:3020/mcp
TOOLS RETURNED: 1
window-mcp: http://127.0.0.1:3021/mcp
TOOLS RETURNED: 2
brief-mcp: http://127.0.0.1:3022/mcp
TOOLS RETURNED: 2
storage-mcp: http://127.0.0.1:3023/mcp
TOOLS RETURNED: 2

GLOBAL TOOL REGISTRY: 7 tools

ROUTING STEP 1
server: source
tool: read_course_chat_messages
...
ROUTING STEP 7
server: storage
tool: read_latest_orchestration_artifact

CHECK: multi-server MCP orchestration executed source -> window -> chunks -> brief -> prompt -> save -> read
```

Это доказывает, что agent подключил несколько MCP servers, выбрал tools из разных endpoints, выполнил длинный flow и сохранил результат.

## Видео

Сценарий:

1. Показать `.env.example` с четырьмя MCP ports и без секретов.
2. Показать список servers/tools в README.
3. Запустить `fixture-demo`.
4. Показать `REGISTERED MCP SERVERS: 4` и `GLOBAL TOOL REGISTRY: 7 tools`.
5. Показать routing steps: source, window, window, brief, brief, storage, storage.
6. Показать `AGENT ANALYSIS`, чтобы подчеркнуть: анализ на стороне agent/LLM, MCP tools только предоставляют данные.
7. Показать `state/latest-report.md` и `state/latest-orchestration.json`.
8. Кратко показать live-команду с `TELEGRAM_BACKEND=tdlib`.

## Проверка Требований

- Несколько MCP servers: да, 4 локальных Streamable HTTP endpoints.
- Agent выбирает нужный tool: да, через planner и registry; при наличии `LLM_API_KEY` planner может спросить Eliza.
- Корректная маршрутизация: да, каждый step печатает `server`, `tool`, `arguments preview`.
- Длинный flow: да, 7 MCP tool calls плюс agent analysis step.
- Tools с разных servers: да, используются source, window, brief и storage.
- Анализ на стороне LLM/agent: да, MCP tools не вызывают LLM.
