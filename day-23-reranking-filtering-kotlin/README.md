# Day 23: Reranking, Filtering, Query Rewrite

## Цель

День 23 улучшает RAG loop из Day 22 вторым этапом после первичного поиска:

```text
question -> query rewrite -> retrieve top-K before -> rerank/filter -> top-K after -> RAG prompt -> LLM
```

Программа сравнивает:

- `baseline-rag`: обычный retrieval по исходному вопросу;
- `reranked-rag`: локальный query rewrite, top-K candidates, heuristic reranker, threshold filter.

Стек: Kotlin CLI, Gradle, `java.net.http.HttpClient`, `kotlinx.serialization`, PDFBox, JSON index, Eliza/OpenRouter-compatible chat completions. High-level LLM/RAG SDKs и внешние reranker SDKs не используются.

## Как Работает Улучшение

`baseline` берет `RERANK_TOP_K_AFTER=4` чанка обычным поиском.

`reranked` делает:

1. Расширяет вопрос локальными hints: `day-21`, `README.md`, `.gitignore`, `fixture-demo`, `chunk_id`, `MCP` и похожими project-specific словами.
2. Ищет `RETRIEVAL_TOP_K_BEFORE=10` кандидатов.
3. Считает rerank score:

```text
base retrieval score + lexical overlap + metadata/source/title/section boosts
```

4. Отсекает кандидатов ниже `RERANK_MIN_SCORE=0.55`.
5. Передает в RAG prompt максимум `RERANK_TOP_K_AFTER=4` чанка.

Если threshold отсекает все, demo оставляет лучший fallback chunk и помечает результат как `low confidence`.

Production-вариант такого шага обычно делают CrossEncoder-моделью. Здесь выбран heuristic reranker, чтобы Day 23 был review-friendly, офлайн-воспроизводимым и не отправлял приватные chunks во внешний reranker.

## Control Questions

`eval/control-questions.json` содержит 10 вопросов из Day 22:

- вопрос;
- ожидаемые пункты ответа;
- expected sources.

`compare-dry-run` считает для каждого вопроса:

- baseline sources и reranked sources;
- expected source hits до/после;
- false positives до/после;
- first-hit rank;
- сколько chunks было до фильтра и после фильтра.

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
RETRIEVAL_TOP_K_BEFORE=10
RERANK_TOP_K_AFTER=4
RERANK_MIN_SCORE=0.55
QUERY_REWRITE_MODE=local
RERANK_MODE=heuristic
RAG_MAX_CONTEXT_TOKENS=2200
CORPUS_MAX_FILES=400
STRUCTURED_MAX_TOKENS=700
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

For live LLM modes:

```text
LLM_API_KEY=...
```

`scripts/run-rerank.sh` сначала читает `day-01-llm-rest-kotlin/.env`, затем локальный `.env`, а переменные из командной строки имеют самый высокий приоритет.

Важно: live режимы отправляют найденные chunks из локального корпуса в LLM endpoint. Запускайте `compare-demo`, `ask reranked` и `eval-live` только если экспорт этих фрагментов в выбранный endpoint разрешен.

## Запуск

Build:

```bash
./gradlew :day-23-reranking-filtering-kotlin:build
```

Offline demo:

```bash
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="fixture-demo"
```

Offline comparison over 10 questions:

```bash
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="compare-dry-run"
```

Live baseline RAG vs reranked RAG:

```bash
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="compare-demo"
```

Ask one question:

```bash
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="ask baseline \"как запустить Day 21 offline demo?\""
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="ask reranked \"как запустить Day 21 offline demo?\""
```

Optional real local embeddings:

```bash
ollama pull nomic-embed-text
EMBEDDING_BACKEND=ollama day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="compare-dry-run"
```

## Ожидаемый Вывод

`fixture-demo` показывает:

```text
Day 23: Reranking, filtering, query rewrite
mode: fixture-demo
embedding backend: hash
topK before filter: 10
topK after filter: 4
threshold: 0.550

BASELINE RAG RETRIEVAL
...

RERANKED RETRIEVAL
ORIGINAL QUERY
...
REWRITTEN QUERY
...
CANDIDATES BEFORE FILTER: 10
AFTER RERANK/FILTER: 4
filtered out: ...

RERANKED RAG PROMPT PREVIEW
...
```

Runtime artifacts:

```text
index/structured-index.json
reports/latest-rerank-comparison.md
reports/eval-rerank-dry-run.json
reports/eval-live-<timestamp>.json
```

Они игнорируются и не коммитятся.

## Видео

Сценарий:

1. Показать `eval/control-questions.json`.
2. Запустить `fixture-demo` и показать original query, rewritten query, candidates before filter.
3. Показать rerank scores: base, lexical, boost, passed threshold.
4. Показать `topK before=10`, `topK after=4`, `threshold=0.55`.
5. Запустить `compare-dry-run`.
6. Сравнить source hits и false positives baseline vs reranked.
7. Показать `reports/eval-rerank-dry-run.json`.
8. При разрешенном export chunks запустить `compare-demo` и показать `latest-rerank-comparison.md`.

## Проверка Требований

- Второй этап после поиска: да, heuristic reranker/filter.
- Порог отсечения нерелевантных результатов: да, `RERANK_MIN_SCORE`.
- Top-K до и после фильтрации: да, `RETRIEVAL_TOP_K_BEFORE` и `RERANK_TOP_K_AFTER`.
- Query rewrite: да, локальное расширение запроса project hints.
- Сравнение без фильтра/rewrite и с фильтром: да, `compare-dry-run`, `compare-demo`, `eval-live`.
- Улучшенный RAG с filtering/reranking: да.
