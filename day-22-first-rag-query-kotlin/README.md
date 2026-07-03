# Day 22: Первый RAG-запрос

## Цель

День 22 собирает первый полный RAG loop поверх локальной базы знаний:

```text
вопрос -> поиск релевантных чанков -> RAG prompt -> direct REST request к LLM
```

Программа сравнивает два режима на одной модели:

- `no-rag`: вопрос отправляется в LLM напрямую;
- `rag`: сначала ищутся top-k chunks, затем chunks добавляются в prompt с metadata и требованием ссылаться на источники.

Стек: Kotlin CLI, Gradle, `java.net.http.HttpClient`, `kotlinx.serialization`, PDFBox, JSON index, Eliza/OpenRouter-compatible chat completions. High-level LLM/RAG SDKs не используются.

## Связь С Day 21

Day 22 самодостаточный: он адаптирует нужные части Day 21 indexing/search code внутри своей папки и не требует runtime artifacts из `day-21-document-indexing-kotlin/index/`.

При первом запуске Day 22 строит собственный:

```text
index/structured-index.json
```

Если index уже существует и совместим с текущим embedding backend/model, он переиспользуется.

## Control Questions

Файл `eval/control-questions.json` содержит 10 вопросов:

- сам вопрос;
- ожидаемые пункты ответа;
- ожидаемые источники, которые retrieval должен найти.

`eval-dry-run` проверяет retrieval/source hits без LLM calls. `eval-live` делает live comparison `no-rag` и `rag` для всех 10 вопросов.

## Настройка

Локальный `.env` опционален для offline режимов:

```text
DOCUMENTS_DIR=..
INDEX_DIR=index
REPORTS_DIR=reports
CONTROL_QUESTIONS_FILE=eval/control-questions.json
EMBEDDING_BACKEND=hash
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_EMBED_MODEL=nomic-embed-text
RAG_CHUNK_STRATEGY=structured
RETRIEVAL_TOP_K=4
RAG_MAX_CONTEXT_TOKENS=2200
CORPUS_MAX_FILES=400
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

For live LLM modes:

```text
LLM_API_KEY=...
```

`scripts/run-rag.sh` сначала читает `day-01-llm-rest-kotlin/.env`, затем локальный `.env`, а переменные из командной строки имеют самый высокий приоритет.

Важно: `rag` live modes отправляют найденные chunks из локального корпуса в LLM endpoint. Запускайте `compare-demo`, `ask rag` и `eval-live` только если экспорт этих фрагментов в выбранный endpoint разрешен.

## Запуск

Build:

```bash
./gradlew :day-22-first-rag-query-kotlin:build
```

Offline prompt preview:

```bash
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="fixture-demo"
```

Offline evaluation over 10 questions:

```bash
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="eval-dry-run"
```

Live no-RAG vs RAG comparison:

```bash
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="compare-demo"
```

Ask one question:

```bash
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="ask no-rag \"как запустить Day 21 offline demo?\""
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="ask rag \"как запустить Day 21 offline demo?\""
```

Optional real local embeddings:

```bash
ollama pull nomic-embed-text
EMBEDDING_BACKEND=ollama day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="compare-demo"
```

## Ожидаемый Вывод

`fixture-demo` показывает:

```text
Day 22: First RAG request
mode: fixture-demo
embedding backend: hash
question: ...

RETRIEVED CHUNKS
1. score=... source=...
...

RAG PROMPT PREVIEW
SYSTEM:
...
CONTEXT:
...

CHECK: question -> search relevant chunks -> build RAG prompt preview
```

`compare-demo` показывает два блока:

```text
NO-RAG
...

RAG
...
sources:
- score=... source / section / chunk_id
```

Runtime artifacts:

```text
index/structured-index.json
reports/latest-rag-comparison.md
reports/eval-live-<timestamp>.json
```

Они игнорируются и не коммитятся.

## Видео

Сценарий:

1. Показать `eval/control-questions.json`.
2. Запустить `fixture-demo` и показать найденные chunks.
3. Показать RAG prompt: вопрос + context + metadata.
4. Запустить `eval-dry-run` и показать source hits по 10 вопросам.
5. Запустить `compare-demo`.
6. Сравнить `WITHOUT RAG` и `WITH RAG`: у RAG есть источники и chunk metadata.
7. Показать `reports/latest-rag-comparison.md`.

## Проверка Требований

- Вопрос -> поиск релевантных чанков -> объединение с вопросом -> LLM: да.
- Ответ без RAG и с RAG: да, `compare-demo` и `ask`.
- Агент с двумя режимами: да, `no-rag` и `rag`.
- 10 контрольных вопросов: да, `eval/control-questions.json`.
- Для каждого вопроса есть ожидания и источники: да.
- Сравнение качества: да, `eval-dry-run` для source hits и `eval-live` для live report.
