# Day 25: RAG Mini-Chat With Task Memory

## Цель

День 25 собирает production-like мини-чат поверх RAG:

```text
new user message -> persisted history -> task state -> RAG retrieval -> grounded answer -> saved session
```

Чат хранит историю диалога, на каждый новый вопрос ищет контекст в локальной базе, отвечает с учетом найденных chunks и всегда выводит источники/цитаты. Дополнительно он ведет task state: цель диалога, уточнения, ограничения и термины.

Стек: Kotlin CLI, Gradle, `java.net.http.HttpClient`, `kotlinx.serialization`, PDFBox, JSON index, Eliza/OpenRouter-compatible chat completions. High-level LLM/RAG SDKs не используются.

## Как Работает Chat Pipeline

1. Загружается `state/chat-session.json`.
2. Новый user message добавляется в историю.
3. `TaskMemoryUpdater` локально обновляет:
   - `goal`;
   - `clarifications`;
   - `constraints`;
   - `terms`;
   - `openQuestions`.
4. Retrieval query собирается из текущего вопроса, task state и последних сообщений.
5. Day 24 style RAG делает query rewrite, retrieval, rerank/filter, quote extraction и relevance gate.
6. Live prompt получает `TASK_STATE`, `RECENT_HISTORY`, `QUOTE_CANDIDATES` и `CONTEXT`.
7. Ответ возвращается в strict JSON с `status`, `answer`, `sources`, `quotes`, `clarifyingQuestion`.
8. Assistant turn сохраняется в session history вместе с sources, quotes и validation summary.

Если релевантность ниже порога, агент возвращает `status="unknown"`, `answer="не знаю"` и уточняющий вопрос.

## Настройка

Offline режимы работают без `.env`.

```text
DOCUMENTS_DIR=..
INDEX_DIR=index
STATE_DIR=state
SESSION_FILE=state/chat-session.json
REPORTS_DIR=reports
SCENARIOS_FILE=eval/scenarios.json

EMBEDDING_BACKEND=hash
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_EMBED_MODEL=nomic-embed-text

RAG_CHUNK_STRATEGY=structured
RETRIEVAL_TOP_K_BEFORE=10
RERANK_TOP_K_AFTER=4
RERANK_MIN_SCORE=0.55
ANSWER_MIN_RELEVANCE=0.55
MIN_QUOTES=1
MAX_QUOTES=4
QUOTE_MAX_CHARS=260
QUERY_REWRITE_MODE=local
RERANK_MODE=heuristic
RAG_MAX_CONTEXT_TOKENS=2200
CHAT_RECENT_MESSAGES_IN_PROMPT=8
CHAT_HISTORY_MAX_MESSAGES=40
CORPUS_MAX_FILES=400
STRUCTURED_MAX_TOKENS=700

LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

For live modes add:

```text
LLM_API_KEY=...
```

`scripts/run-chat.sh` сначала читает `day-01-llm-rest-kotlin/.env`, затем локальный `.env`, а переменные окружения из shell имеют самый высокий приоритет. Truststore из Day 1 используется автоматически.

Важно: live режимы отправляют retrieved chunks, quotes, recent history и task state во внешний LLM endpoint. Запускайте их только если такой export разрешен.

## Запуск

Build:

```bash
./gradlew :day-25-rag-memory-chat-kotlin:build
```

Offline chat demo:

```bash
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="fixture-demo"
```

One dry-run turn with saved history:

```bash
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="ask-dry-run \"как запустить Day 21 offline demo?\""
```

Two long scenarios:

```bash
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="scenario-dry-run"
```

Interactive live chat:

```bash
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="chat"
```

Live one-turn answer:

```bash
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="ask \"как запустить Day 21 offline demo?\""
```

Optional real local embeddings:

```bash
ollama pull nomic-embed-text
EMBEDDING_BACKEND=ollama day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="scenario-dry-run"
```

## CLI Commands

Interactive `chat` supports:

```text
/state
/history
/clear
/exit
```

`/state` and `/history` print the persisted session, task goal, constraints, terms, open questions and recent turns.

## Ожидаемый Вывод

`fixture-demo` показывает несколько turn подряд:

```text
TASK STATE
goal: подготовить видео по RAG days 21-24
constraints: отвечай только с источниками и цитатами; live chunks нельзя отправлять...
terms: rag, day-21, day-24, kotlin, cli

RAG QUERY
...

ANSWER JSON
{
  "status": "answered",
  "answer": "...",
  "sources": [...],
  "quotes": [...]
}

SESSION
messages saved: ...
```

`scenario-dry-run` проверяет два длинных сценария:

```text
answers with sources: 20/20
quotes match chunks: 20/20
RAG calls: 20/20
goals retained: 2/2
constraints retained: 2/2
terms retained: 2/2
```

Runtime artifacts:

```text
index/structured-index.json
state/chat-session.json
reports/latest-chat-demo.md
reports/scenario-dry-run.json
reports/scenario-live-<timestamp>.json
```

Они игнорируются и не коммитятся.

## Видео

Сценарий:

1. Показать README и схему pipeline.
2. Запустить `fixture-demo`.
3. Показать, как после первого turn появляется `goal`, `constraints`, `terms`.
4. Показать, что каждый ответ содержит `sources` и `quotes`.
5. Показать `state/chat-session.json` как persisted history.
6. Запустить `scenario-dry-run` и показать 2 длинных сценария по 10 сообщений.
7. При разрешенном export chunks запустить live `ask` или `chat`.

## Проверка Требований

- Мини-чат CLI: да.
- История диалога сохраняется: да, `state/chat-session.json`.
- RAG вызывается на каждый вопрос: да, retrieval query строится для каждого turn.
- Ответ учитывает найденную информацию: да, prompt получает retrieved chunks и quote candidates.
- Ответ всегда выводит sources/quotes для supported turns: да.
- Task state хранит цель, уточнения, ограничения и термины: да.
- Проверка на 2 длинных сценариях по 10 сообщений: да, `scenario-dry-run` и `scenario-live`.
