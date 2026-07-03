# Day 21: Document Indexing Pipeline

## Цель

День 21 показывает первый локальный RAG-building-block: pipeline индексации документов. Программа берет корпус README/статей/кода/PDF, режет его на чанки двумя стратегиями, генерирует embeddings, сохраняет JSON index с metadata и печатает сравнение chunking strategies.

Стек: Kotlin CLI, Gradle, `java.net.http.HttpClient` для Ollama embeddings, `kotlinx.serialization` для JSON, PDFBox для PDF -> text. High-level RAG/LLM SDKs не используются.

## Что Индексируется

По умолчанию `DOCUMENTS_DIR=..`, поэтому demo индексирует сам репозиторий курса:

- root `README.md`, `AGENTS.md`, day README;
- Kotlin и Gradle source files;
- `.md`, `.kt`, `.kts`, `.txt`, `.pdf`;
- runtime/secrets/build folders игнорируются.

Ограничение по умолчанию: `CORPUS_MAX_FILES=140`, чтобы demo оставался быстрым и удобным для видео.

## Chunking Strategies

`fixed`:

- режет текст окнами примерно по `FIXED_CHUNK_TOKENS=450`;
- добавляет overlap `FIXED_CHUNK_OVERLAP=75`;
- хорошо демонстрирует стабильный размер чанков, но section metadata искусственная.

`structured`:

- Markdown режется по заголовкам;
- Kotlin/Gradle code режется по объявлениям и файлам;
- plain text/PDF режутся по абзацам;
- крупные секции дополнительно делятся до `STRUCTURED_MAX_TOKENS=700`.

Каждый chunk получает metadata:

```text
source, title, section, chunk_id, strategy, ordinal, approx_tokens
```

## Embeddings

Backend по умолчанию:

```text
EMBEDDING_BACKEND=hash
```

Это deterministic local embedding для offline demo: он не требует Ollama, сети или секретов.

Для настоящих локальных embeddings:

```bash
ollama pull nomic-embed-text
EMBEDDING_BACKEND=ollama day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="ollama-demo"
```

Ollama вызывается напрямую:

```text
POST http://localhost:11434/api/embeddings
```

## Настройка

Локальный `.env` опционален:

```text
DOCUMENTS_DIR=..
INDEX_DIR=index
EMBEDDING_BACKEND=hash
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_EMBED_MODEL=nomic-embed-text
FIXED_CHUNK_TOKENS=450
FIXED_CHUNK_OVERLAP=75
STRUCTURED_MAX_TOKENS=700
RETRIEVAL_TOP_K=5
CORPUS_MAX_FILES=140
```

Не коммитьте `.env`, `.certs`, generated indexes и временные файлы.

## Запуск

Build:

```bash
./gradlew :day-21-document-indexing-kotlin:build
```

Offline demo:

```bash
day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="fixture-demo"
```

Search по сохраненному индексу:

```bash
day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="search structured \"как устроен MCP orchestration\""
```

Real Ollama embeddings:

```bash
ollama pull nomic-embed-text
EMBEDDING_BACKEND=ollama day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="ollama-demo"
```

Index custom directory:

```bash
DOCUMENTS_DIR=/absolute/path/to/docs day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="index"
```

## Ожидаемый Вывод

`fixture-demo` печатает:

```text
Day 21: Document indexing pipeline
DOCUMENTS DIR: ...
INDEX DIR: ...
EMBEDDING BACKEND: hash
LOADED DOCUMENTS: ...
APPROX PAGES: ...

CHUNKING
fixed chunks: ...
structured chunks: ...

FIXED INDEX
path: .../index/fixed-index.json
chunks: ...
embedding dimensions: 512

STRUCTURED INDEX
path: .../index/structured-index.json
chunks: ...
embedding dimensions: 512

COMPARISON REPORT: .../index/chunking-comparison.md
CHECK: documents -> chunks -> embeddings -> JSON indexes + metadata + comparison report
```

Generated runtime files:

```text
index/fixed-index.json
index/structured-index.json
index/chunking-comparison.md
```

`index/` ignored intentionally: это runtime artifact, а не source code.

## Видео

Сценарий:

1. Показать `.env.example`: hash backend и Ollama backend.
2. Запустить `fixture-demo`.
3. Показать два блока: `fixed chunks` и `structured chunks`.
4. Открыть `index/fixed-index.json` и показать metadata + embedding.
5. Открыть `index/structured-index.json` и показать `section`.
6. Открыть `index/chunking-comparison.md`: таблица размеров и sample retrieval.
7. Запустить `search structured "как устроен MCP orchestration"`.
8. Кратко показать команду `ollama-demo` для реальных embeddings.

## Проверка Требований

- Набор документов 20-30+ страниц: да, repo corpus печатает approximate pages.
- Разбиение на чанки: да.
- Генерация embeddings: да, `hash` offline или `ollama` real local.
- Сохранение индекса: да, JSON.
- Metadata: да, `source`, `title`, `section`, `chunk_id`.
- Две стратегии chunking: да, fixed и structured.
- Сравнение стратегий: да, `chunking-comparison.md`.
