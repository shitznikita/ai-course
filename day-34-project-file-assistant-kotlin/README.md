# День 34. Ассистент для работы с файлами проекта

Kotlin CLI-ассистент получает **цель**, сам обнаруживает нужные файлы, ищет и анализирует код и документацию, а затем создаёт или изменяет файлы через пять MCP tools. Результат подтверждается server-owned списком файлов и deterministic unified diff.

Пример правильного запроса:

```text
Найди все места, где используется LegacyPaymentsApi, и подготовь отчёт миграции
```

Пользователь не пишет «открой `CheckoutService.kt`». Ассистент сам выполняет:

```text
goal
  -> project_list_files
  -> project_search_text
  -> project_read_files
  -> analysis
  -> project_write_file
  -> project_unified_diff
  -> finish
```

## Что показывает решение

- работа минимум с 2–3 файлами в одном запуске;
- поиск literal text по нескольким проектным файлам;
- чтение выбранных файлов с SHA-256;
- создание нового файла и безопасное обновление существующих;
- preview overlay для произвольного проекта и явный `--apply`;
- unified diff без зависимости от состояния Git;
- два полностью офлайн-сценария;
- повторяемость tool trace, набора изменений и diff fingerprint;
- live planner через прямой REST к локальному Ollama без отправки исходников в облако.

## Стек

- Kotlin/JVM `2.3.21`;
- Java toolchain `21`;
- kotlinx serialization `1.9.0`;
- coroutines `1.10.2`;
- официальный MCP Kotlin SDK client/server `0.13.0`;
- Ktor/CIO `3.4.3`;
- локальный Ollama `/api/chat`;
- модель по умолчанию `qwen3:4b`;
- прямой `java.net.http.HttpClient` для LLM-запроса;
- JUnit 5 / Kotlin Test.

High-level LLM SDK и готовый agent framework не используются.

## Архитектура

```text
┌──────────────────────────────┐
│ CLI: goal-level request      │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│ ProjectFileAssistant         │
│ max 12 steps / one action    │
│ strict validation / retries  │
└──────────────┬───────────────┘
               │ next action
      ┌────────▼────────┐
      │ AgentPlanner    │
      ├─────────────────┤
      │ FixturePlanner  │ deterministic offline
      │ OllamaPlanner   │ direct loopback REST
      └────────┬────────┘
               │ validated tool_call
┌──────────────▼───────────────┐
│ FileMcpClient                │
│ tools/list + exact registry  │
└──────────────┬───────────────┘
               │ Streamable HTTP
               │ http://127.0.0.1:3034/mcp
┌──────────────▼───────────────┐
│ Embedded FileMcpServer       │
│ exactly five project tools   │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│ ProjectWorkspace             │
│ baseline + preview overlay   │
│ read SHA + atomic apply      │
│ server-owned session state   │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│ ProjectFilePolicy            │
│ path / symlink / secret /    │
│ binary / size guards         │
└──────────────────────────────┘
```

`FixturePlanner` и `OllamaPlanner` используют один и тот же `ProjectFileAssistant` loop и настоящие MCP-вызовы. Различается только источник следующего решения:

- fixture mode детерминированно планирует committed demo goals и даёт acceptance oracle без Ollama;
- live mode отправляет локальной модели цель и bounded observations, после чего приложение валидирует JSON-action и само исполняет MCP tool.

Модель не является источником истины для итоговых файлов: `FILES READ`, `FILES WRITTEN`, changed paths и diff берутся из `ProjectWorkspace`.

## MCP endpoint и tools

Embedded server слушает только:

```text
http://127.0.0.1:3034/mcp
```

Это default для `mcp-smoke`, live и произвольного workspace. Внутренние
`fixture-demo`, `eval-dry-run` и `repro-check` на каждый scenario lifecycle
запрашивают у ОС отдельный ephemeral loopback port, поэтому последовательные
сценарии не зависят от освобождения `3034`.

При каждом CLI run `AppConfig` создаёт случайный in-memory session token.
Встроенный client добавляет его в `X-Day34-Session`; запрос без правильного
token получает `401`. Token не читается из `.env`, не передаётся модели и не
печатается. Loopback остаётся сетевой границей, а token не позволяет другому
локальному процессу незаметно подключиться к активной write-session.

Клиент сначала вызывает `tools/list` и принимает только точный набор:

```text
project_list_files
project_search_text
project_read_files
project_write_file
project_unified_diff
```

Лишний или отсутствующий tool завершает запуск fail-closed.

### 1. `project_list_files`

Обнаруживает разрешённые текстовые файлы. Excluded paths, symlinks, binary/NUL и слишком большие файлы не возвращаются.

```json
{
  "type": "object",
  "properties": {
    "prefix": {
      "type": "string",
      "maxLength": 240
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 200
    }
  }
}
```

Результат:

```json
{
  "files": ["README.md", "docs/api.md"],
  "truncated": false
}
```

### 2. `project_search_text`

Ищет literal string по нескольким разрешённым файлам.

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "minLength": 1,
      "maxLength": 128
    },
    "prefix": {
      "type": "string",
      "maxLength": 240
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100
    }
  },
  "required": ["query"]
}
```

Результат содержит `path`, номер строки и bounded matching text:

```json
{
  "query": "LegacyPaymentsApi",
  "hits": [
    {
      "path": "src/main/kotlin/com/acme/payments/CheckoutService.kt",
      "line": 4,
      "text": "private val legacyPaymentsApi: LegacyPaymentsApi,"
    }
  ],
  "searchedFiles": 6,
  "truncated": false
}
```

### 3. `project_read_files`

Читает 1–6 выбранных файлов.

```json
{
  "type": "object",
  "properties": {
    "paths": {
      "type": "array",
      "minItems": 1,
      "maxItems": 6,
      "uniqueItems": true,
      "items": {
        "type": "string",
        "minLength": 1,
        "maxLength": 240
      }
    }
  },
  "required": ["paths"]
}
```

Для каждого файла возвращаются:

```json
{
  "path": "README.md",
  "content": "...",
  "sha256": "64 lowercase hex characters",
  "bytes": 210
}
```

### 4. `project_write_file`

Создаёт или полностью заменяет один разрешённый UTF-8 text file.

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "minLength": 1,
      "maxLength": 240
    },
    "content": {
      "type": "string",
      "maxLength": 131072
    },
    "expectedSha256": {
      "type": "string",
      "minLength": 64,
      "maxLength": 64
    }
  },
  "required": ["path", "content"]
}
```

Для существующего файла обязательны:

1. чтение в текущей session;
2. `expectedSha256` из результата чтения;
3. совпадение SHA с текущим содержимым непосредственно перед записью.

Новый файл создаётся без `expectedSha256`.

### 5. `project_unified_diff`

Аргументы отсутствуют:

```json
{
  "type": "object",
  "properties": {}
}
```

Результат:

```json
{
  "changedPaths": ["CHANGELOG.md", "README.md", "docs/api.md"],
  "diff": "--- /dev/null\n+++ b/CHANGELOG.md\n...",
  "sha256": "..."
}
```

Diff строится локально из session baseline и новых версий. Наличие `.git` не требуется.

## Bounded agent loop

`ProjectFileAssistant` ограничивает запуск:

| Ограничение | Значение |
|---|---:|
| planner steps | 12 |
| изменённых файлов | 4 |
| discovered files | 200 |
| scanned filesystem entries | 10 000 |
| discovery depth | 64 |
| cumulative search hits | 100 |
| files per read | 6 |
| один читаемый файл | 256 KiB |
| новое содержимое файла | 128 KiB |
| cumulative tool observations | 4 000 000 chars |
| Ollama context | 8 192 tokens |
| Ollama output reserve | 1 200 tokens |
| chat/template framing reserve | 512 tokens |
| admitted system + user content | ≤ 6 480 UTF-8 bytes |
| planner retry | 1 retry после invalid action |

Перед MCP call проверяются:

- action type `tool_call` или `finish`;
- tool из обнаруженного registry;
- точный набор аргументов;
- типы, размеры и ranges;
- SHA-256 format;
- отсутствие идентичного повторного call.

MCP handlers независимо отклоняют unknown argument keys. `project_read_files`
принимает только paths, уже возвращённые discovery/search в этой session.
После изменений planner обязан вызвать `project_unified_diff` до `finish`. Невалидный второй action завершает запуск без исполнения этого action.

Live planner не обрезает raw JSON и file content. Каждая read-observation
строится как typed whole item с полными `path`, `bytes`, `SHA-256` и content.
Admission использует консервативную worst-case границу: один UTF-8 byte
считается максимум одним input token. Поэтому выполняется уравнение
`chatContentUtf8Bytes + 512 framing + 1200 output <= 8192 context`, то есть
system + user content ограничены 6480 bytes. Если все complete items не
помещаются, запуск останавливается **до Ollama HTTP call** с `LLM CALLS: 0`.
MCP может читать файл до 256 KiB для fixture/local processing, но live-модель
получает его только целиком — partial file или SHA-less fragment никогда не
отправляется.

## Workspace safety

`ProjectFilePolicy` — единая граница всех файловых операций.

`SecureProjectFiles` открывает nested directories и файлы относительно
directory handles через `SecureDirectoryStream`, если provider это
поддерживает. На provider без этой JVM-функции используются `NOFOLLOW_LINKS`
channels и снимки identity каждого parent/file до и после I/O; любое
обнаруженное изменение завершает operation fail-closed.

Запрещены:

- absolute paths и `..`;
- выход из canonical workspace root;
- symlink files и directories;
- `.git`, `.gradle`, `.idea`, `.certs`, `.kotlin`;
- `build`, `out`, `runtime`, `reports`, `node_modules`, `target`;
- `.env*`, credentials/secrets/private-key names;
- `.pem`, `.key`, `.p12`, `.pfx`, `.jks`, `.keystore`;
- binary/NUL в любой позиции полного bounded payload или malformed UTF-8;
- неизвестные типы файлов;
- файлы больше 256 KiB;
- delete, rename, shell и arbitrary command execution.

Разрешены bounded project text formats: Kotlin, Java, Markdown, JSON, YAML, XML, TOML, Gradle, shell, SQL, GraphQL, Proto, HTML/CSS/JS/TS, Python, Go, Rust и несколько других исходных форматов.

### Preview

Для произвольного workspace это default:

- запись сохраняется только в session overlay;
- следующие read/search видят overlay;
- реальный файл на диске не меняется;
- unified diff полностью формируется.

### Apply

Требует явный `--apply`.

Запись использует:

1. повторную проверку target и parent;
2. unique temporary file через `CREATE_NEW`;
3. `FileChannel.force(true)`;
4. сохранение POSIX permissions существующего файла, когда filesystem это поддерживает;
5. secure directory-relative access либо повторную проверку parent/file identity;
6. повторную проверку current SHA;
7. atomic move с безопасным fallback;
8. optimistic concurrency через SHA-256.

Committed demo применяет изменения только к disposable copies внутри ignored `runtime/`.

## Fixture project

`fixtures/sample-project` содержит:

```text
README.md
docs/api.md
src/main/kotlin/com/acme/payments/LegacyPaymentsApi.kt
src/main/kotlin/com/acme/payments/PaymentClient.kt
src/main/kotlin/com/acme/payments/CheckoutService.kt
src/test/kotlin/com/acme/payments/CheckoutServiceTest.kt
```

Demo workspace каждый раз копируется заново в `runtime/<scenario>`. Committed fixtures не изменяются.

## Сценарий 1: usage report

Цель:

```text
Найди все места, где используется LegacyPaymentsApi, и подготовь отчёт миграции
```

Assistant:

1. обнаруживает файлы;
2. ищет `LegacyPaymentsApi`;
3. читает implementation, service и test;
4. анализирует роль каждого usage;
5. создаёт `docs/legacy-payments-usage.md`;
6. выводит diff.

Фрагмент результата:

```text
STEP 2
TOOL: project_search_text
OBSERVATION: 4 hit(s) ... CheckoutService.kt, LegacyPaymentsApi.kt, CheckoutServiceTest.kt

FILES READ (3): ...
FILES WRITTEN (1): docs/legacy-payments-usage.md
DIFF:
--- /dev/null
+++ b/docs/legacy-payments-usage.md
LLM CALLS: 0
```

## Сценарий 2: documentation sync

Цель:

```text
Проверь, соответствует ли документация публичному API проекта, и синхронизируй её
```

Assistant:

1. ищет `refund(`;
2. читает `PaymentClient.kt`, `CheckoutService.kt`, `README.md`, `docs/api.md`;
3. обнаруживает публичный `refund`, отсутствующий в документации;
4. обновляет README и API docs;
5. создаёт `CHANGELOG.md`;
6. показывает только ожидаемые documentation changes.

Фрагмент результата:

```text
FILES READ (4): README.md, docs/api.md, ...CheckoutService.kt, ...PaymentClient.kt
FILES WRITTEN (3): CHANGELOG.md, README.md, docs/api.md
DIFF:
--- /dev/null
+++ b/CHANGELOG.md
--- a/README.md
+++ b/README.md
--- a/docs/api.md
+++ b/docs/api.md
LLM CALLS: 0
```

## Подготовка

Нужны JDK 21 и Gradle Wrapper из корня репозитория.

Проверка:

```bash
./gradlew :day-34-project-file-assistant-kotlin:test
./gradlew :day-34-project-file-assistant-kotlin:build
```

Для offline-команд Ollama не нужен.

## Offline-запуск

### MCP smoke

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args="mcp-smoke"
```

Проверяет loopback endpoint, точные пять tools, list/search/read и отсутствие writes.

### Два fixture-сценария

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args="fixture-demo"
```

### Acceptance checks

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args="eval-dry-run"
```

Проверяются оба goals, path traversal, excluded paths, symlinks, read-before-write, stale SHA, preview, apply, max-step guard и diff.

### Повторяемость

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args="repro-check"
```

Ожидаемый итог:

```text
SCENARIO: legacy-usage
REPRODUCIBLE: true
SCENARIO: docs-sync
REPRODUCIBLE: true
LLM CALLS: 0
```

## Live Ollama

Optional live mode требует установленный Ollama, запущенный daemon и локальную
модель. В первом терминале запустите server и оставьте его работающим:

```bash
ollama serve
```

Если Ollama desktop app уже запущен, отдельный `ollama serve` не нужен. Во
втором терминале один раз скачайте модель:

```bash
ollama pull qwen3:4b
```

Опциональная конфигурация:

```bash
cp day-34-project-file-assistant-kotlin/.env.example \
  day-34-project-file-assistant-kotlin/.env
```

`.env` не коммитится. API key не нужен.

Live model-driven demo на свежей disposable fixture copy:

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args="live-demo"
```

Ожидаемый marker:

```text
PLANNER MODE: ollama-direct-rest
```

Preview с произвольной high-level целью на fixture:

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args='goal-dry-run Проверь документацию и подготовь необходимые изменения'
```

`goal-dry-run` использует Ollama, но не меняет диск.

## Произвольный workspace

Default — preview:

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args='goal --workspace /absolute/path/to/project Найди устаревшие API и подготовь migration report'
```

Явный preview:

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args='goal --workspace /absolute/path/to/project --preview Синхронизируй документацию с публичным API'
```

Реальная запись только по явному согласию:

```bash
day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh \
  --args='goal --workspace /absolute/path/to/project --apply Синхронизируй документацию с публичным API'
```

Перед `--apply` рекомендуется сначала посмотреть preview diff.

## Privacy boundary

- Ollama URL обязан использовать `http` и literal `127.0.0.1`;
- MCP host обязан быть literal `127.0.0.1`;
- MCP требует случайный per-run session token;
- исходники не отправляются в Eliza, OpenRouter или другой cloud provider;
- raw model response не печатается;
- excluded file content не попадает в tool results или prompt;
- API key отсутствует.

## Матрица требований

| Требование задания | Реализация |
|---|---|
| читать файлы проекта | `project_read_files`, 1–6 files + SHA-256 |
| искать по нескольким файлам | `project_search_text` |
| анализировать содержимое | planner получает bounded tool observations и строит report/docs changes |
| создавать файлы | migration report и `CHANGELOG.md` |
| изменять файлы | README и `docs/api.md` с read-before-write |
| ассистент сам инициирует файловую работу | goal-level bounded planner loop |
| минимум два сценария | legacy usage report + documentation sync |
| минимум 2–3 файла | 3 файла в первом, 4 во втором сценарии |
| изменения сохраняются или выводятся как diff | apply в disposable workspace + `project_unified_diff` |
| повторяемый результат | committed fixtures, `FixturePlanner`, `repro-check` |
| Видео + код | CLI trace, fixtures, source, tests и video plan ниже |

## Ограничения V1

- только bounded text project files;
- literal search, без regex и semantic index;
- write заменяет полный файл, patch tool отсутствует;
- delete и rename отсутствуют;
- новые nested directories не создаются;
- shell, build и test execution не доступны агенту;
- одна process-owned session за CLI run;
- `--apply` не следует запускать параллельно с внешним процессом, который
  переименовывает directories того же workspace: no-follow, parent identity и
  SHA checks fail closed при обнаруженном race, но filesystem не предоставляет
  переносимый multi-file transaction;
- локальная 4B модель может ошибиться в плане, поэтому fixture mode остаётся acceptance oracle;
- unified diff использует один deterministic hunk на файл; для очень большого line-product включается bounded full-replacement fallback;
- порт MCP настраивается через `FILE_ASSISTANT_MCP_PORT`, если `3034` занят.

## Сценарий видео, 3–5 минут

1. Показать high-level goal и fixture project. Подчеркнуть, что пользователь не перечисляет файлы.
2. Открыть `FileMcpServer.kt` и показать пять registrations, затем `ProjectFilePolicy.kt`. Не показывать реальный `.env`.
3. Запустить:

   ```bash
   day-34-project-file-assistant-kotlin/scripts/run-file-assistant.sh --args="mcp-smoke"
   ```

   Показать loopback endpoint, `tools/list`, пять tools и zero writes.

4. Запустить `fixture-demo`. В первом сценарии показать цепочку list → search → read three files → write report → diff, затем открыть `runtime/fixture-legacy-usage/docs/legacy-payments-usage.md`.
5. Во втором сценарии показать чтение code + docs, изменения README/API/changelog и unified diff.
6. Запустить `repro-check` и показать два `REPRODUCIBLE: true`.
7. Если Ollama доступен, завершить `live-demo` и показать `PLANNER MODE: ollama-direct-rest`. Если нет — показать exact command и отметить, что offline mode использовал тот же MCP execution loop.

В кадре должны быть видны `GOAL`, `STEP`, `TOOL`, `FILES READ`, `FILES WRITTEN`, `DIFF`, `CHECK` и `LLM CALLS`.
