# Day 17: Первый MCP-инструмент для Telegram

## Цель

День 17 реализует свой MCP server вокруг внешнего API. В качестве API выбран Telegram через MTProto/TDLib: server публикует read-only tool `read_telegram_chat_messages`, agent подключается к MCP server, вызывает tool и использует результат для краткой сводки.

Для воспроизводимой записи и проверки без Telegram-логина есть `fixture` backend. Live backend `tdlib` включается только явно через `.env` или environment variables.

Стек: Kotlin CLI, Gradle, MCP Kotlin SDK server/client, Ktor CIO Streamable HTTP, direct REST к Eliza для optional summary, TDLib JSON interface через JNA.

## MCP Tool

Tool names:

```text
read_telegram_chat_messages
list_telegram_chats
```

`read_telegram_chat_messages` input schema:

```text
chat: required string, numeric chat id / public @username / fixture chat
limit: optional integer, default 10, max 50
onlyLocal: optional boolean, default false
includeSender: optional boolean, default false
```

`list_telegram_chats` input schema:

```text
limit: optional integer, default 20, max 100
```

Поведение:

- читает последние сообщения;
- показывает список чатов аккаунта с `id/title/type`, чтобы можно было читать приватные чаты без public username;
- не отправляет, не удаляет, не помечает прочитанным и не меняет состояние чата;
- по умолчанию скрывает sender id;
- ограничивает `limit` диапазоном `1..50`;
- возвращает readable text и structured MCP content.

## Настройка

Offline fixture mode не требует секретов:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="fixture-demo"
```

Для live Telegram нужно получить собственные Telegram `api_id` и `api_hash`, подготовить TDLib native library `libtdjson`, затем создать локальный `.env`:

```bash
cp day-17-telegram-mcp-tool-kotlin/.env.example day-17-telegram-mcp-tool-kotlin/.env
```

Минимальные live-переменные:

```text
TELEGRAM_BACKEND=tdlib
TELEGRAM_API_ID=...
TELEGRAM_API_HASH=...
TELEGRAM_PHONE=+...
TELEGRAM_CHAT=<numeric-chat-id-or-public-username>
TDLIB_LIBRARY_PATH=/absolute/path/to/libtdjson.dylib
```

Если Telegram запросит login code или 2FA password, передайте их только через local env:

```bash
TELEGRAM_CODE=12345 day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="agent-demo"
TELEGRAM_PASSWORD=... day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="agent-demo"
```

Если код не приходит, сначала посмотрите диагностику способа доставки:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="auth-check"
```

Она печатает `current delivery`, `next delivery` и `resend timeout seconds`. Повторную отправку можно запросить после timeout:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="auth-resend"
```

TDLib разрешает resend только в состоянии `authorizationStateWaitCode`, когда `next delivery` не `none` и серверный timeout уже прошёл.

Если delivery показывает `Telegram service message`, но сообщение не приходит и `next delivery` равно `none`, используйте QR-login:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="auth-qr"
```

Команда напечатает `tg://` link для подтверждения входа на другом уже залогиненном Telegram-устройстве. В мобильном Telegram путь обычно такой: Settings -> Devices -> Link Desktop Device.
Важно: держите `auth-qr` команду запущенной, пока сканируете QR. Если остановить процесс, Telegram login token протухнет и при сканировании будет `AUTH_TOKEN_EXPIRED`.
Время ожидания настраивается через `TELEGRAM_QR_WAIT_SECONDS`, по умолчанию 180 секунд.

Не коммитьте `.env`, `telegram-session/`, `telegram-files/`, коды авторизации и пароли.

## Запуск

Build:

```bash
./gradlew :day-17-telegram-mcp-tool-kotlin:build
```

Main offline demo:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="fixture-demo"
```

Raw protocol smoke:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="raw-check"
```

List private chat ids:

```bash
TELEGRAM_BACKEND=tdlib TELEGRAM_LIMIT=50 day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="list-chats"
```

Для непубличного чата возьмите `id` из вывода `list-chats` и передайте его как `TELEGRAM_CHAT`:

```bash
TELEGRAM_BACKEND=tdlib TELEGRAM_CHAT=-1001234567890 day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="agent-demo"
```

Если TDLib падает с `database disk image is malformed`, локальная TDLib database повреждена. Удалите только runtime state и авторизуйтесь заново:

```bash
rm -rf day-17-telegram-mcp-tool-kotlin/telegram-session day-17-telegram-mcp-tool-kotlin/telegram-files
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="auth-qr"
```

Run server only:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="server"
```

Telegram login diagnostics:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="auth-check"
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="auth-resend"
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="auth-qr"
```

Live TDLib demo:

```bash
TELEGRAM_BACKEND=tdlib TELEGRAM_CHAT=<chat> day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="agent-demo"
```

## Ожидаемый Вывод

`fixture-demo` показывает:

```text
Day 17: Telegram MCP tool
MCP SERVER: http://127.0.0.1:3017/mcp
TELEGRAM BACKEND: fixture

AGENT CONNECTING
TOOLS RETURNED: 2
1. read_telegram_chat_messages
2. list_telegram_chats

TOOL RESULT
Telegram messages
backend: fixture
returned: 4 / requested 10

AGENT SUMMARY
Local summary is used because LLM_API_KEY is not configured.
...

CHECK: MCP tool call ok, result used by agent
```

Если `LLM_API_KEY` настроен, summary делает Eliza/OpenRouter прямым REST-запросом. Если ключа нет, используется локальная deterministic summary, чтобы demo всегда запускалось.

## Видео

Сценарий:

1. Показать `build.gradle.kts`: есть MCP server/client SDK, Ktor и JNA для TDLib JSON.
2. Показать регистрацию `read_telegram_chat_messages`: name, description, input schema, required `chat`.
3. Показать регистрацию `list_telegram_chats`: tool возвращает `chat_id` для приватных чатов.
4. Показать validation: `limit` ограничен, sender скрывается по умолчанию.
5. Запустить `fixture-demo` и показать `TOOLS RETURNED: 2`, `TOOL RESULT`, `AGENT SUMMARY`.
6. Запустить `list-chats` после live login и показать, что приватные чаты читаются через numeric `chat_id`.
7. Запустить `raw-check`, чтобы увидеть `initialize`, `tools/list`, `tools/call`.
8. Кратко показать `.env.example` для live TDLib без реальных секретов.

## Проверка Требований

- Свой MCP server реализован: да.
- Tools зарегистрированы: да, `read_telegram_chat_messages` и `list_telegram_chats`.
- Входные параметры описаны schema: да.
- Tool возвращает результат: да, text + structured content.
- Агент подключается и вызывает tool: да, `fixture-demo` и `agent-demo`.
- Приватные чаты поддержаны: да, через `list-chats` -> numeric `chat_id` -> `TELEGRAM_CHAT`.
- Telegram MTProto path есть через TDLib JSON/JNA: да, включается `TELEGRAM_BACKEND=tdlib`.
- Offline проверка не требует секретов: да.
