# Day 18: Telegram Course Scheduler MCP

## Цель

День 18 реализует MCP-инструмент с периодическим выполнением поверх Telegram-чата курса. Scheduler-agent по расписанию вызывает MCP tool, tool читает последние сообщения, выделяет окно одного дня курса, сохраняет JSON/Markdown state и возвращает агрегированный prompt для GPT-5.5/Codex.

Архитектура гибридная:

- agent владеет расписанием и работает 24/7;
- MCP server остается adapter/tool слоем;
- tool выполняет сбор, фильтрацию, persistence и генерацию результата.
- interval/daily scheduler держит один MCP client session на весь loop, чтобы live TDLib backend переиспользовал один Telegram session lock.

Стек: Kotlin CLI, Gradle, MCP Kotlin SDK server/client, Ktor CIO Streamable HTTP, TDLib JSON/JNA для live Telegram, direct REST к Eliza для optional prompt generation.

## MCP Tools

```text
generate_course_day_prompt
get_latest_course_day_prompt
```

`generate_course_day_prompt` input schema:

```text
courseDay: optional string, number or auto, default COURSE_DAY
chat: optional string, default TELEGRAM_CHAT
limit: optional integer, default TELEGRAM_LIMIT, max 500
persist: optional boolean, default true
```

`get_latest_course_day_prompt` не принимает аргументы и возвращает последний сохраненный prompt без нового Telegram/LLM вызова.

Default `MCP_TIMEOUT_SECONDS` равен `120`, потому что live Telegram + Eliza prompt generation может занимать заметно больше 30 секунд.

## Поведение

- читает Telegram через fixture или TDLib backend;
- сортирует сообщения хронологически по `dateIso/id`;
- ищет marker задания вида `День N`, `Day N`, `🔥 День N`;
- при `COURSE_DAY=auto` берет последний найденный marker;
- при `COURSE_DAY=18` берет диапазон от задания 18 дня до marker 19 дня, не включая 19 день;
- сохраняет результат в `state/runs/<timestamp>-day-XX.json`, `state/latest-run.json`, `state/latest-prompt.md`;
- если `LLM_API_KEY` настроен, prompt формируется через Eliza direct REST;
- если ключа нет, используется deterministic local prompt builder;
- Telegram sender скрывается по умолчанию, write/delete/mark-read операций нет.

## Настройка

Offline fixture mode не требует секретов:

```bash
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="fixture-demo"
```

Для live Telegram можно использовать тот же набор переменных, что в Day 17:

```bash
cp day-18-telegram-course-scheduler-kotlin/.env.example day-18-telegram-course-scheduler-kotlin/.env
```

`run-scheduler.sh` также умеет переиспользовать уже настроенные env-файлы:

- Eliza/LLM берется из `day-01-llm-rest-kotlin/.env`;
- Telegram/TDLib берется из `day-17-telegram-mcp-tool-kotlin/.env`;
- если Day 17 `.env` найден, TDLib session/files по умолчанию переиспользуются из `day-17-telegram-mcp-tool-kotlin/telegram-session` и `day-17-telegram-mcp-tool-kotlin/telegram-files`;
- локальный `day-18-telegram-course-scheduler-kotlin/.env` переопределяет оба файла;
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

Если нужна генерация prompt через Eliza, добавьте:

```text
LLM_API_KEY=...
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

Для production scheduler:

```text
MCP_TIMEOUT_SECONDS=120
SCHEDULE_TIME=13:00
SCHEDULE_ZONE=Europe/Moscow
```

Для видео/smoke scheduler:

```text
SCHEDULE_INTERVAL_SECONDS=5
SCHEDULER_RUNS=2
```

Не коммитьте `.env`, `state/`, `telegram-session/`, `telegram-files/`, коды авторизации и пароли.

## Запуск

Build:

```bash
./gradlew :day-18-telegram-course-scheduler-kotlin:build
```

Offline fixture demo:

```bash
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="fixture-demo"
```

Один live/configured run прямо сейчас:

```bash
TELEGRAM_BACKEND=tdlib TELEGRAM_CHAT=<chat-id> TELEGRAM_LIMIT=100 \
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="once"
```

Ускоренный scheduler для видео:

```bash
LLM_API_KEY= COURSE_DAY=18 SCHEDULE_INTERVAL_SECONDS=5 SCHEDULER_RUNS=2 \
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="scheduler-demo"
```

В live TDLib-режиме не запускайте несколько scheduler-процессов одновременно с одной `telegram-session`: TDLib блокирует локальную database. Внутри одного scheduler loop client/session переиспользуется.

Daily scheduler:

```bash
TELEGRAM_BACKEND=tdlib TELEGRAM_CHAT=<chat-id> TELEGRAM_LIMIT=100 \
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="scheduler"
```

Telegram auth helpers:

```bash
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="auth-check"
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="auth-resend"
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="auth-qr"
```

## Ожидаемый Вывод

`fixture-demo` показывает:

```text
Day 18: Telegram course scheduler MCP
MCP SERVER: http://127.0.0.1:3018/mcp
TELEGRAM BACKEND: fixture
COURSE DAY: 18

TOOLS RETURNED: 2
1. generate_course_day_prompt
2. get_latest_course_day_prompt

TOOL RESULT
Course day prompt generated
day: 18 (requested 18)
messages: selected 5 / returned 8
ignored: before 1, after 2
next day marker excluded: 19
llm mode: local-deterministic
run json: .../state/runs/...-day-18.json
latest prompt: .../state/latest-prompt.md
```

Это доказывает boundary rule: сообщение до задания игнорируется, Day 19 marker и сообщения после него не попадают в prompt для Day 18.

## Видео

Сценарий:

1. Показать `generate_course_day_prompt` и `get_latest_course_day_prompt`: tool schema, параметры `courseDay/chat/limit/persist`.
2. Показать fixture messages: до Day 18, Day 18 assignment, дискуссия, Day 19 marker.
3. Запустить `fixture-demo` и показать `selected 5 / returned 8`, `ignored before 1, after 2`, `next day marker excluded: 19`.
4. Показать `state/latest-prompt.md` и `state/latest-run.json`.
5. Запустить `scheduler-demo` с `SCHEDULE_INTERVAL_SECONDS=5 SCHEDULER_RUNS=2`, чтобы видно было периодическое выполнение.
6. Кратко показать live config для TDLib и daily config `13:00 Europe/Moscow`.

## Проверка Требований

- MCP-инструмент с периодическим выполнением: да, scheduler-agent периодически вызывает MCP tool.
- Данные сохраняются: да, JSON history и latest Markdown prompt.
- Выполняется по расписанию: да, `scheduler` daily и `scheduler-demo` interval.
- Возвращает агрегированный результат: да, tool возвращает counts, highlights, prompt и storage paths.
- Агент работает 24/7 локально: да, `scheduler` держит процесс и планирует следующий daily run.
- Deployment не обязателен: да, задание закрывается локальным scheduler и воспроизводимым demo.
