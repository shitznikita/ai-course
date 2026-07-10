# День 29: оптимизация локального RAG на Qwen3

## Цель

Это локальный и воспроизводимый benchmark для реального RAG-кейса Дня 28:

```text
Day 21 structured-index.json
  -> local nomic-embed-text retrieval
  -> qwen3 profile through local Ollama /api/chat
  -> grounded answer with citations and a quality/speed/resource report
```

Внешних моделей, API-ключей и cloud fallback нет. Один вопрос получает embedding и retrieval ровно один раз; одинаковый найденный контекст затем передаётся всем профилям. Поэтому результат измеряет параметры, prompt и квантование, а не случайную смену найденных chunks.

Стек: Kotlin/JVM 21, Gradle, `kotlinx.serialization`, прямой `java.net.http.HttpClient` и loopback Ollama API (`/api/embed`, `/api/chat`, `/api/show`, `/api/ps`). SDK для LLM/RAG нет.

## Профили «до → после → Q8»

| Профиль | Модель | Temperature | Max tokens | Context | Prompt |
|---|---|---:|---:|---:|---|
| `baseline-q4` | `qwen3:14b` / Q4_K_M | 0.6 | 512 | 32768 | нейтральный RAG |
| `optimized-q4` | `qwen3:14b` / Q4_K_M | 0 | 220 | 8192 | строгий grounded template |
| `optimized-q8` | `qwen3:14b-q8_0` / Q8_0 | 0 | 220 | 8192 | тот же строгий template |

Оптимизированный prompt ограничивает ответ 35 словами и цитату 100 символами, запрещает добавлять факты вне контекста и требует source IDs и дословные quotes. Compact JSON хранит `sources` как массив `chunk_id` и `quotes` как массив цитат: CLI сверяет их с полученными chunks и выводит полные source/section для каждого ID. Это оставляет `num_predict=220` достаточно места для полного JSON-contract. Q8 использует те же prompt и параметры, поэтому его строка показывает именно компромисс квантования. Q4 остаётся основным профилем; итоговый отчёт предложит Q8, только если его grounding или coverage выше более чем на 5 п.п.

## Подготовка

Нужны JDK 21 и [Ollama](https://ollama.com/). Если daemon не работает как сервис, запустите в другом терминале:

```bash
ollama serve
```

Модели нужны только один раз. Q8 требует примерно 16 GB свободного диска:

```bash
ollama pull qwen3:14b
ollama pull qwen3:14b-q8_0
ollama pull nomic-embed-text
ollama list
```

Нужен настоящий structured index Дня 21, который не хранится в Git:

```bash
CORPUS_MAX_FILES=400 \
FIXED_CHUNK_TOKENS=120 \
FIXED_CHUNK_OVERLAP=20 \
STRUCTURED_MAX_TOKENS=120 \
OLLAMA_EMBED_MODEL=nomic-embed-text \
EMBEDDING_BACKEND=ollama \
day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="ollama-demo"
```

## Команды

```bash
./gradlew :day-29-local-llm-optimization-kotlin:build

day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh --args="diagnose"

day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh \
  --args='profile optimized-q4 "Как запустить Day 21 offline indexing demo?"'

# default command; 3 questions × 3 profiles × 3 repeats
day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh
```

`diagnose` проверяет, что Ollama — loopback URL, Q4/Q8 и embedding-модель установлены, показывает метаданные квантования, читает index и сверяет размерность query-вектора. При проблеме CLI печатает точную команду `ollama serve`, `ollama pull` или пересборки Day 21, но ничего сам не скачивает и не меняет в другой папке.

`profile` выводит найденные chunks, один ответ, citations, latency, tokens, tokens/s и ресурсный снимок. `benchmark` создаёт ignored `reports/benchmark-*.json` и Markdown с таблицей качества, скорости, стабильности и памяти.

## Что измеряется

- качество: валидный JSON, grounded sources/quotes, coverage контрольных тезисов и behavior для `unknown`;
- скорость: latency, input/output tokens, output tokens/s, min/median/max latency;
- стабильность: transport success, grounding rate и Jaccard стабильность наборов sources между повторениями одного вопроса;
- ресурсы: model bytes, VRAM bytes и context из `/api/ps`, а также снимок `memory_pressure` macOS до и после вызова.

Поля `/api/ps`, которые Ollama конкретной версии не возвращает, печатаются как `n/a`, а не подменяются выдуманными значениями.

## Настройки и приватность

Скопируйте [.env.example](.env.example) в `.env` только для локальных override. Переменные окружения shell имеют приоритет. Основные настройки:

```text
WEEK6_INDEX_FILE=../day-21-document-indexing-kotlin/index/structured-index.json
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_Q4_MODEL=qwen3:14b
OLLAMA_Q8_MODEL=qwen3:14b-q8_0
OLLAMA_EMBED_MODEL=nomic-embed-text
BENCHMARK_RUNS=3
```

`OLLAMA_BASE_URL` принимает только `localhost`, `127.0.0.1` или `::1` по plain HTTP. Заметки, chunks, ответы и отчёты не отправляются в облако. Индекс и reports — локальные runtime-artifacts и не попадают в commit.

## Проверка и сценарий видео

```bash
./gradlew :day-29-local-llm-optimization-kotlin:test
./gradlew :day-29-local-llm-optimization-kotlin:build
ollama list
day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh --args="diagnose"
day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh \
  --args='profile optimized-q4 "Как запустить Day 21 offline indexing demo?"'
day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh
ollama ps
```

Для видео: показать Q4/Q8 в `ollama list`, `diagnose` с quantization, один grounded-ответ с quote, три строки итоговой таблицы baseline/optimized-Q4/optimized-Q8, recommendation и `ollama ps`. Не показывайте локальный `.env` или содержимое частных chunks.

## Проверка требований Дня 29

- параметры `temperature`, `num_predict` и `num_ctx` сравниваются явно;
- prompt оптимизирован под короткий grounded RAG-ответ;
- Q4 и Q8 сравниваются на одинаковом prompt/context;
- baseline, optimized Q4 и optimized Q8 измеряются по качеству, скорости, стабильности и ресурсам;
- generation и retrieval полностью локальны.
