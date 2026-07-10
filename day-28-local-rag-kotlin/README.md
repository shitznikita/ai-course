# День 28: локальный RAG с Qwen3 и сравнением с облаком

## Цель

Это полностью локальный RAG-путь поверх реального индекса из Дня 21:

```text
Day 21 structured-index.json
  -> local nomic-embed-text /api/embed
  -> cosine top-K retrieval
  -> local qwen3:14b /api/chat
  -> grounded JSON answer + sources + verbatim quotes
```

`local` никогда не обращается к облаку. Команды `compare` и `benchmark` нужны только для оценки: они передают в настроенный HTTPS Eliza/OpenRouter endpoint тот же уже найденный локальный контекст, что и локальной модели. Retrieval при этом всегда выполняется только локально и ровно один раз на вопрос.

Стек: Kotlin/JVM 21, Gradle, `kotlinx.serialization`, прямой `java.net.http.HttpClient` к Ollama и прямой REST к Eliza. SDK для LLM/RAG и cloud fallback отсутствуют.

## Что проверяется

- индекс Дня 21 имеет стратегию `structured`, backend `ollama`, модель `nomic-embed-text` и совместимую размерность векторов;
- запрос embedding идёт в loopback Ollama `/api/embed` с `truncate=false`;
- ответ генерирует локальная `qwen3:14b` через `/api/chat` с `stream=false`, `think=false`, `temperature=0` и JSON Schema;
- каждый ответ содержит статус, источники и дословные цитаты; валидатор сверяет их с реально retrieved chunks;
- benchmark измеряет валидность JSON, grounding/citations, coverage контрольных пунктов, latency, tokens/s и повторяемость наборов источников.

Использование одной embedding-модели для индекса и query нужно для совместимого векторного поиска. См. официальные [Ollama Embed API](https://docs.ollama.com/api/embed), [рекомендации по embeddings](https://docs.ollama.com/capabilities/embeddings) и [Chat API](https://docs.ollama.com/api/chat).

## Подготовка

Нужны JDK 21, Gradle Wrapper и [Ollama](https://ollama.com/). В отдельном терминале запустите daemon, если он не работает как фоновый сервис:

```bash
ollama serve
```

Один раз скачайте две модели:

```bash
ollama pull qwen3:14b
ollama pull nomic-embed-text
ollama list
```

### Индекс Дня 21

По умолчанию программа читает:

```text
../day-21-document-indexing-kotlin/index/structured-index.json
```

Это runtime-artifact примерно на 31 MB, поэтому Git его намеренно не хранит. Нужен настоящий structured index из Дня 21, собранный с Ollama embeddings (не hash offline fixture):

```bash
CORPUS_MAX_FILES=400 \
FIXED_CHUNK_TOKENS=120 \
FIXED_CHUNK_OVERLAP=20 \
STRUCTURED_MAX_TOKENS=120 \
OLLAMA_EMBED_MODEL=nomic-embed-text \
EMBEDDING_BACKEND=ollama \
day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="ollama-demo"
```

`CORPUS_MAX_FILES=400` нужен, чтобы corpus включил Day 21 материалы, используемые тремя контрольными вопросами; старый cap `140` часто заканчивается раньше. Сниженные размеры chunks не превышают context window современной `nomic-embed-text` при legacy indexer Дня 21. Если `diagnose` сообщает о несовместимости размерностей, модели или source coverage benchmark, пересоберите именно этот индекс. День 28 никогда не изменяет папку Дня 21 самостоятельно.

## Запуск

Сначала соберите модуль и проверьте все локальные зависимости:

```bash
./gradlew :day-28-local-rag-kotlin:build
day-28-local-rag-kotlin/scripts/run-local-rag.sh --args="diagnose"
```

Один полностью локальный вопрос (без сети и без API-ключа после загрузки Ollama-моделей):

```bash
day-28-local-rag-kotlin/scripts/run-local-rag.sh \
  --args='local "Как запустить Day 21 offline indexing demo?"'
```

Один вопрос с одинаковым local retrieval context для локальной и облачной генерации:

```bash
day-28-local-rag-kotlin/scripts/run-local-rag.sh \
  --args='compare "Чем отличается fixed chunking от structured chunking?"'
```

Основной demo запускает три контрольных вопроса Дня 22 по три раза для каждой модели:

```bash
day-28-local-rag-kotlin/scripts/run-local-rag.sh
# то же самое: ... --args="benchmark"
```

Контрольные вопросы находятся в [eval/benchmark-questions.json](eval/benchmark-questions.json): offline demo Дня 21, metadata chunks и сравнение fixed/structured chunking. В формулировках оставлены точные имена полей и конфигурационных терминов из исходных материалов, а системная инструкция требует отвечать по-русски. На каждый вопрос embedding/retrieval происходит один раз, затем один и тот же подготовленный prompt передаётся в шесть генераций: 3 local + 3 cloud.

После benchmark появляются игнорируемые Git файлы `reports/benchmark-*.json` и `reports/benchmark-*.md` с raw результатами и сводной таблицей.

Benchmark завершает команду успешно, если все HTTP-запросы завершились; низкий `grounded` rate не маскируется как transport error, а остаётся измеримым результатом в отчёте. Одиночные `local` и `compare`, напротив, возвращают ошибочный exit code при невалидном grounding.

## Команды

| Команда | Что делает | Нужен cloud token |
|---|---|---:|
| `diagnose` | проверяет loopback Ollama, две модели, формат/метаданные Day 21 index, размерность query embedding и наличие cloud config | нет |
| `local <вопрос>` | локальный embedding → retrieval → Qwen3 grounded answer | нет |
| `compare <вопрос>` | один local и один cloud ответ на одном контексте | да |
| `benchmark` | 3 вопроса × 3 повтора для local и cloud, report качества/скорости/стабильности | да |

Если Ollama недоступна, программа подскажет `ollama serve`; если модель отсутствует — `ollama pull qwen3:14b` или `ollama pull nomic-embed-text`; если index отсутствует/несовместим — точную команду пересборки Дня 21. Ничего из этого CLI не скачивает и не пересобирает автоматически.

## Настройки и приватность

Скопируйте [.env.example](.env.example) в локальный `.env`, если нужны overrides. Shell environment имеет наивысший приоритет. Скрипт также аккуратно читает существующий `day-01-llm-rest-kotlin/.env`, чтобы `compare` и `benchmark` могли использовать уже настроенный OAuth token.

Важные переменные:

```text
WEEK6_INDEX_FILE=../day-21-document-indexing-kotlin/index/structured-index.json
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=qwen3:14b
OLLAMA_EMBED_MODEL=nomic-embed-text
RETRIEVAL_TOP_K=4
RAG_MAX_CONTEXT_TOKENS=2200
BENCHMARK_RUNS=3

LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
LLM_API_KEY=local-oauth-token-only
# CLOUD_TEMPERATURE=0  # только если выбранная cloud-модель поддерживает параметр
```

Для cloud температура по умолчанию не передаётся: например, текущая `gpt-5-mini` в корпоративном конфиге отвергает принудительное значение. При необходимости задать её для совместимой модели используйте `CLOUD_TEMPERATURE` в диапазоне `0..2`.

`OLLAMA_BASE_URL` принимает только plain loopback HTTP URL (`127.0.0.1`, `localhost` или `::1`). Это запрещает случайно отправить document chunks на внешний Ollama endpoint.

`local` не использует ключ, Eliza или сеть. Напротив, `compare` и `benchmark` отправляют в configured HTTPS cloud endpoint **только выбранные chunks курса и вопрос**, потому что модели должны получить идентичный контекст. Не запускайте эти команды на приватных документах без явного разрешения. Token, `.env` и reports в Git не попадают.

## Проверка

Unit-тесты не требуют настоящего Ollama или OAuth:

```bash
./gradlew :day-28-local-rag-kotlin:test
```

Они проверяют exact reader формата Дня 21, совместимость index/query embedding, strict cosine retrieval, bounded context, parser/validator цитат, запрет внешних URLs, общий retrieval для повторов benchmark и aggregation метрик.

Полная живая проверка:

```bash
./gradlew :day-28-local-rag-kotlin:build
ollama list
day-28-local-rag-kotlin/scripts/run-local-rag.sh --args="diagnose"
day-28-local-rag-kotlin/scripts/run-local-rag.sh \
  --args='local "Как запустить Day 21 offline indexing demo?"'
day-28-local-rag-kotlin/scripts/run-local-rag.sh \
  --args='compare "Какие metadata хранит Day 21 для каждого chunk?"'
day-28-local-rag-kotlin/scripts/run-local-rag.sh
ollama ps
```

## Сценарий видео

1. Показать `ollama list` с `qwen3:14b` и `nomic-embed-text`, а также существующий `day-21-document-indexing-kotlin/index/structured-index.json`.
2. Выполнить `diagnose`: демонстрация loopback, metadata index и compatible query vector.
3. Запустить `local` и показать локальные найденные chunks, answer, sources, verbatim quotes, latency и tokens/s.
4. Выполнить `benchmark`: показать, что для каждого вопроса retrieval показан один раз, затем напечатаны 3 повторения local/cloud и финальная таблица качества, скорости и стабильности.
5. Выполнить `ollama ps`: локальные embedding и generation действительно были использованы.
6. Не показывать `.env`, OAuth token или приватные chunks при cloud comparison.

## Проверка требований Дня 28

- Используется реальный structured JSON index из Недели 6 / Дня 21.
- Retrieval целиком локальный: `/api/embed` + cosine top-K.
- Локальная генерация целиком локальная: `qwen3:14b` через loopback Ollama.
- Ответы grounded: JSON, citations и дословные quotes проверяются against selected chunks.
- `compare` и `benchmark` сопоставляют локальную и облачную модели на одинаковом контексте.
- Benchmark оценивает качество, скорость и стабильность; report сохраняется локально и игнорируется Git.
