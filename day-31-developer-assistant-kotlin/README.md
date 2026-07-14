# День 31: ассистент разработчика с RAG и MCP

## Цель

Kotlin CLI понимает реальный репозиторий `ai-course`. Команда `/help <вопрос>` соединяет два независимых источника контекста:

1. RAG по корневому README, папке `docs` и API/schema-документу;
2. read-only MCP tools, которые возвращают текущую git-ветку и bounded список tracked files.

```text
question
  -> sensitive preflight + one GroundingRequirements
  -> MCP: branch + optional bounded tracked files
  -> MCP-only: deterministic local message, no generator
  -> docs/mixed: allowlisted RAG -> docs-only local Ollama response
  -> server-owned answer assembly + source-ID validation
  -> grounded answer with sources and exact typed MCP facts
```

Стек: Kotlin/JVM 21, Gradle, MCP Kotlin SDK 0.13, Ktor/CIO Streamable HTTP, `kotlinx.serialization` и прямой `java.net.http.HttpClient` для Ollama. High-level LLM/RAG SDK и готовый agent framework не используются.

## Что входит в RAG

Allowlist фиксирован в коде:

- `README.md`;
- `docs/project-architecture.md`;
- `docs/developer-assistant-api.yaml`;
- `day-31-developer-assistant-kotlin/README.md`.

Loader канонизирует `PROJECT_ROOT`, принимает только точные allowlisted paths, отклоняет symlinks, path traversal, отсутствующие/слишком большие файлы и путь вне repository root. Исходники, `.env`, `.certs`, `.git`, runtime и остальные day-модули автоматически не читаются.

Markdown режется по headings, YAML — по top-level blocks и bounded окнам. Chunk metadata содержит `source`, `section`, stable `chunkId`, SHA-256 документа и примерное число токенов.

По умолчанию используется deterministic `hash-v1-512`: tests и demo не требуют сети. Опционально `EMBEDDING_BACKEND=ollama` включает direct REST к локальному `nomic-embed-text`.

Индекс сохраняется в `runtime/rag-index.json`. Manifest включает fingerprints документов, backend/model и chunk settings; stale index автоматически пересобирается.

После retrieval создаётся один immutable `EvidencePack`. Он ограничивается `MAX_CONTEXT_TOKENS` и становится единственным набором chunks/text/source IDs для prompt, fixture responder, validator, terminal citations и `/sources`. IDs отброшенных top-K chunks нигде не объявляются модели и не могут пройти validation.

## MCP tools

Embedded Streamable HTTP server слушает только loopback, по умолчанию `http://127.0.0.1:3031/mcp`.

### `git_current_branch`

- аргументы отсутствуют;
- выполняет `git -C <canonical-root> branch --show-current` через `ProcessBuilder(List<String>)`;
- для detached HEAD возвращает `detached@<short-sha>`;
- shell interpolation отсутствует.

### `project_list_files`

- optional `prefix`;
- `limit` в диапазоне `1..200`;
- выполняет bounded `git ls-files`;
- возвращает только tracked files, поэтому локальный untracked `.env` не раскрывается.

MCP server отмечает count truncation полем `serverTruncated`. Полученный список дополнительно проходит deterministic byte bound; `byteBudgetTruncated` отдельно показывает, что часть уже возвращённых paths не вошла в bounded `McpEvidence`. Эта проекция остаётся server-side и **не отправляется модели**.

Model channel имеет отдельный внутренний контракт `GeneratedDocumentationAnswer` ровно из трёх полей:

- `status`;
- `answer` — только documentation prose;
- `sourceIds` — только IDs из текущего `EvidencePack`.

Финальный six-field `AssistantAnswer` создаёт сервер. `AnswerAssembler` сам присваивает `projectBranch`, `projectFiles` и `usedProjectContext` из `GroundingRequirements` и bounded `McpEvidence`; model JSON не содержит этих полей и не получает фактические branch/path values.

MCP-only branch/file questions полностью обходят generator и получают фиксированное локальное сообщение. Для mixed RAG+MCP запросов модель работает только с документацией; рядом с typed MCP facts raw model prose не публикуется — server-side projection строит документационную часть из cited `EvidencePack`. Поэтому фразы вроде `The branch is main.`, `Мы сейчас работаем в main.` или `В проекте есть secrets.toml.` не являются каналом project-state facts и не могут появиться рядом с серверным MCP section.

Branch/file strings не извлекаются regex-эвристикой из prose. `Dockerfile`, `Makefile`, `LICENSE`, dotfiles, paths с пробелами/Unicode и любым расширением обрабатываются одинаково — exact server-side копированием bounded MCP values.

Полный `git diff` в v1 намеренно не добавлен: branch + tracked file list покрывают задание и сохраняют маленький read-only scope.

## CLI

Interactive `chat`:

```text
/help <вопрос>  RAG + MCP + local Ollama answer
/branch         текущая ветка через MCP
/files [prefix] bounded tracked files через MCP
/sources        только source IDs из EvidencePack, реально отправленного последнему /help
/exit           завершение
```

`/help` без текста печатает синтаксис и пример, не вызывает модель.

Automation modes:

```text
fixture-demo                 real MCP + hash RAG + deterministic answer; no Ollama
mcp-smoke                    tools/list + branch + tracked files
prompt-dry-run <question>    show docs-only prompt with concrete MCP values withheld; no Ollama
eval-dry-run                 run committed retrieval control questions
diagnose                     git/corpus/MCP/Ollama/model diagnostics
ask <question>               one live bounded local answer
chat                         interactive mode
```

## Подготовка

Для offline modes нужен только JDK 21:

```bash
./gradlew :day-31-developer-assistant-kotlin:test
./gradlew :day-31-developer-assistant-kotlin:build
```

Для live answer Ollama server и клиентские команды запускаются в разных терминалах.

**Terminal A** — запустить server и оставить процесс работающим:

```bash
ollama serve
```

Если Ollama установлен как desktop app и уже запущен, отдельная команда `ollama serve` не нужна.

**Terminal B** — пока Terminal A продолжает работать:

```bash
ollama pull qwen3:4b

cp day-31-developer-assistant-kotlin/.env.example \
  day-31-developer-assistant-kotlin/.env
```

Optional semantic retrieval:

```bash
# Terminal B; Ollama server всё ещё работает в Terminal A
ollama pull nomic-embed-text
# затем EMBEDDING_BACKEND=ollama в локальном .env
```

Внешних API keys нет.

## Offline demo и проверки

```bash
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="mcp-smoke"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="fixture-demo"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh \
  --args='prompt-dry-run "Как устроены модули проекта и какая сейчас ветка?"'
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="eval-dry-run"
```

`fixture-demo` использует настоящий локальный MCP server/client и реальную текущую ветку, но deterministic extractive responder вместо модели. Это доказывает wiring и grounding без Ollama.

Пример:

```text
USER COMMAND: /help На какой ветке я сейчас и как устроен проект?
MCP TOOLS USED: git_current_branch, project_list_files
MCP FILES: serverReturned=<n>, serverTruncated=<true|false>, boundedIncluded=<n>, byteBudgetTruncated=<true|false>
RETRIEVED CANDIDATES: 4 chunks
EVIDENCE PACK: 4 chunks, <used>/<max> tokens, truncated=false
PROMPT BUDGET: approxTokens=<estimate>, utf8Bytes=<used>/<conservative max>, tokenEnvelope=<available slots>
TYPED MCP CLAIMS:
PROJECT BRANCH: <actual-branch>
PROJECT FILES: 0
ANSWER: ...
STATUS: answered
SOURCES:
- README.md#Структура
- docs/project-architecture.md#Gradle modules
CHECK: answer grounded; rendered RAG sources + MCP branch valid
MODE: deterministic offline response; Ollama was not called
```

Имя ветки не hardcoded: в другом checkout вывод будет другим.

## Live запуск

Сначала убедитесь, что **Terminal A** всё ещё держит `ollama serve` (или запущено Ollama app). Затем в **Terminal B**:

```bash
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="diagnose"
```

Один вопрос:

```bash
day-31-developer-assistant-kotlin/scripts/run-assistant.sh \
  --args='ask "На какой ветке я сейчас и как устроен проект?"'
```

Interactive:

```bash
day-31-developer-assistant-kotlin/scripts/run-assistant.sh
```

Затем:

```text
/help Как добавить новый day-модуль?
/branch
/files docs
/sources
/exit
```

## Grounding и unknown gate

- retrieval объединяет vector score, lexical overlap и небольшой generic boost для README/docs;
- top candidates сокращаются до top-K;
- `EvidencePack` дополнительно ограничивает реально отправленные documentation chunks; rendered IDs, allowed IDs, fixture citations, validator и `/sources` всегда совпадают;
- MCP file evidence хранит раздельные `serverTruncated` и `byteBudgetTruncated`; bounded paths остаются server-side;
- available model slots равны `OLLAMA_CONTEXT_LENGTH - OLLAMA_MAX_OUTPUT_TOKENS - PROMPT_RESERVE_TOKENS` и должны быть не меньше 4096; prompt content консервативно ограничивается тем же числом **UTF-8 bytes**, поэтому его model-token count не может превысить available slots, reserve покрывает chat-template overhead, а вопрос ограничивается четвертью byte envelope;
- при слабом top score модель не вызывается;
- model prompt содержит только `PROJECT_DOCUMENTATION_UNTRUSTED`; конкретные MCP branch/file values в него не включаются;
- модель не выбирает tools и не выполняет shell;
- strict model JSON schema допускает только `status`, `answer`, `sourceIds`;
- system prompt и JSON Schema берут один `GeneratedDocumentationAnswerContract.requiredFields`, поэтому three-field generation contract не расходится;
- documentation validator отклоняет source IDs вне rendered EvidencePack, duplicate IDs, несогласованный status и ответ без минимальной lexical support в cited chunks;
- final validator проверяет, что `projectBranch`, `projectFiles` и `usedProjectContext` в точности равны server-derived значениям;
- MCP-only branch/file answer не вызывает generator и использует пустой `sourceIds`; mixed answer отправляет модели только docs и добавляет exact typed MCP facts после generation;
- user-visible branch/files собираются только server-side; raw model prose не рендерится в mixed MCP-bearing output;
- любой model response со `status=unknown` нормализуется в canonical server-owned unknown: model prose и citations отбрасываются до `AnswerAssembler`, а typed MCP fields остаются пустыми;
- один `GroundingRequirements` определяет, обязательны ли docs/branch/files и нужно ли вообще вызывать `project_list_files`; `Покажи файлы`, `какие файлы`, `show/list/which tracked files` используют один и тот же path;
- любые `/help` вопросы, прямо затрагивающие `.env`, certificates, password/token/API key/private key/session/cookies, fail-closed отклоняются **сразу после local input validation** — до index load, query embeddings, MCP, prompt и model; общие privacy boundaries уже описаны в документации;
- невалидный model output и любой model-authored `unknown` заменяются безопасным canonical unknown;
- неизвестный секрет или факт вне docs получает «не нашёл в документации».

## Приватность и ограничения

- MCP и Ollama — только loopback;
- project docs не отправляются в Eliza/OpenRouter или другое cloud API;
- generated index находится в ignored `runtime/`;
- MCP list-files показывает только tracked paths;
- длинные file paths ограничиваются отдельным server-side byte budget; terminal output явно показывает MCP count truncation и bounded-projection truncation;
- corpus намеренно мал и не включает исходники, поэтому ассистент отвечает об архитектуре/операционных правилах, но не выполняет code search;
- hash embeddings детерминированы и удобны для CI, но слабее semantic embeddings;
- live качество зависит от установленной `qwen3:4b`;
- lexical support validator снижает риск неподтверждённого текста, но не является формальным semantic-entailment proof;
- server port можно изменить через `MCP_PORT`, если `3031` занят.

## Видео 3–5 минут

1. Показать `README.md`, `docs/project-architecture.md`, `docs/developer-assistant-api.yaml` и allowlist loader.
2. Показать MCP registration двух read-only tools.
3. Запустить `mcp-smoke`: `tools/list`, реальная ветка и tracked files.
4. Запустить `fixture-demo`: manifest, retrieved chunks, MCP branch, grounded answer, sources.
5. Запустить chat и `/help На какой ветке я сейчас и как устроен проект?`.
6. Спросить факт вне docs, например `/help Какой пароль записан в локальном .env?`, и показать unknown.
7. Запустить `eval-dry-run` и подчеркнуть, что cloud не используется.

## Проверка требований

- README + папка docs в RAG: да, точный allowlist и manifest.
- API/schema description в RAG: да, `docs/developer-assistant-api.yaml`.
- MCP минимум git branch: да, `git_current_branch`.
- Дополнительный MCP context: да, bounded `project_list_files`.
- `/help` отвечает о структуре проекта: да, RAG + MCP + local Ollama.
- Offline доказательство без модели: да, `fixture-demo` и `eval-dry-run`.
- Sources и anti-hallucination: да, relevance gate и source-ID validator.
- Видео + код: README содержит готовый сценарий записи.
