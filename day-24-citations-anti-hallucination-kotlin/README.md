# Day 24: Citations, Sources, Anti-Hallucination

## Цель

День 24 дорабатывает RAG так, чтобы каждый уверенный ответ был grounded:

```text
question -> rewrite -> rerank/filter -> quote candidates -> strict JSON answer -> validation
```

Ответ обязан содержать:

- `answer`;
- `sources` с `source`, `section`, `chunk_id`;
- `quotes` с дословными фрагментами из найденных chunks.

Если релевантность ниже порога, ассистент не вызывает LLM и возвращает `не знаю` + уточняющий вопрос.

Стек: Kotlin CLI, Gradle, `java.net.http.HttpClient`, `kotlinx.serialization`, PDFBox, JSON index, Eliza/OpenRouter-compatible chat completions. High-level LLM/RAG SDKs не используются.

## Как Работает Grounding

Day 24 переиспользует Day 23 retrieval:

1. Локальный query rewrite.
2. Retrieval `RETRIEVAL_TOP_K_BEFORE=10`.
3. Heuristic reranking/filtering с `RERANK_MIN_SCORE=0.55`.
4. Anti-hallucination gate `ANSWER_MIN_RELEVANCE=0.55`.
5. Quote extraction: 1-2 коротких фрагмента из каждого selected chunk.
6. Prompt передает `QUOTE_CANDIDATES` с quote IDs и metadata.
7. LLM должна вернуть только JSON:

```json
{
  "status": "answered",
  "answer": "...",
  "sources": [{"source": "...", "section": "...", "chunk_id": "..."}],
  "quotes": [{"quote_id": "q1.1", "source": "...", "section": "...", "chunk_id": "...", "text": "..."}],
  "clarifyingQuestion": null
}
```

Для weak context:

```json
{
  "status": "unknown",
  "answer": "не знаю",
  "sources": [],
  "quotes": [],
  "clarifyingQuestion": "..."
}
```

Validator проверяет, что sources были реально retrieved, а quotes являются фрагментами найденных chunks.

## Control Sets

`eval/control-questions.json` содержит 10 supported вопросов из Day 22/23.

`eval/unknown-questions.json` содержит вопросы вне локальной базы знаний. Они нужны для проверки режима `не знаю`.

`eval-dry-run` проверяет:

- есть ли sources у supported answers;
- есть ли quotes у supported answers;
- совпадают ли quotes с retrieved chunks;
- покрываются ли expected answer points ответом/цитатами;
- возвращает ли weak context `status=unknown`, `answer=не знаю`, `clarifyingQuestion`.

## Настройка

Локальный `.env` опционален для offline режимов:

```text
DOCUMENTS_DIR=..
INDEX_DIR=index
REPORTS_DIR=reports
CONTROL_QUESTIONS_FILE=eval/control-questions.json
UNKNOWN_QUESTIONS_FILE=eval/unknown-questions.json
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

`scripts/run-citations.sh` сначала читает `day-01-llm-rest-kotlin/.env`, затем локальный `.env`, а переменные из командной строки имеют самый высокий приоритет.

Важно: live режимы отправляют найденные chunks и цитаты из локального корпуса в LLM endpoint. Запускайте `ask` и `eval-live` только если экспорт этих фрагментов в выбранный endpoint разрешен.

## Запуск

Build:

```bash
./gradlew :day-24-citations-anti-hallucination-kotlin:build
```

Offline demo:

```bash
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="fixture-demo"
```

Prompt preview for one question:

```bash
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="ask-dry-run \"как запустить Day 21 offline demo?\""
```

Offline evaluation:

```bash
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="eval-dry-run"
```

Live grounded answer:

```bash
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="ask \"как запустить Day 21 offline demo?\""
```

Optional real local embeddings:

```bash
ollama pull nomic-embed-text
EMBEDDING_BACKEND=ollama day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="eval-dry-run"
```

## Ожидаемый Вывод

`fixture-demo` показывает два кейса:

```text
SUPPORTED QUESTION
ANSWER JSON
{
  "status": "answered",
  "answer": "...",
  "sources": [...],
  "quotes": [...]
}
VALIDATION
sourcesPresent=true
quotesPresent=true
quotesMatchChunks=true

LOW CONFIDENCE QUESTION
ANSWER JSON
{
  "status": "unknown",
  "answer": "не знаю",
  "sources": [],
  "quotes": [],
  "clarifyingQuestion": "..."
}
```

Runtime artifacts:

```text
index/structured-index.json
reports/latest-citation-demo.md
reports/eval-dry-run.json
reports/eval-live-<timestamp>.json
```

Они игнорируются и не коммитятся.

## Видео

Сценарий:

1. Показать strict JSON schema в README.
2. Запустить `fixture-demo`.
3. Показать supported answer: `answer`, `sources`, `quotes`.
4. Показать validation: quotes реально совпадают с chunks.
5. Показать low-confidence question: `status=unknown`, `answer=не знаю`, уточняющий вопрос.
6. Запустить `eval-dry-run` и показать counts `10/10`.
7. При разрешенном export chunks запустить live `ask`.

## Проверка Требований

- Ответ содержит answer: да.
- Ответ содержит source + section/chunk_id: да.
- Ответ содержит цитаты из найденных chunks: да.
- Проверка 10 вопросов на sources/quotes/meaning: да, `eval-dry-run` и `eval-live`.
- Режим “не знаю” при слабом контексте: да, anti-hallucination gate.
