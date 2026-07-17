# AI Course

Репозиторий для заданий курса по AI/LLM.

## Current Snapshot

- Статус на `2026-07-17`: `main` содержит завершённые дни 1-32; Day 33 добавляет support assistant с synthetic ticket/user context через MCP и grounded FAQ/docs RAG.
- Основной стек всех последних заданий: Kotlin CLI + Gradle + прямой REST через `java.net.http.HttpClient`.
- Для cloud-заданий основной провайдер: Eliza API, OpenRouter-compatible endpoint `https://api.eliza.yandex.net/openrouter/v1/chat/completions`.
- Основная cloud-модель: `meta-llama/llama-3.3-70b-instruct`; локальные дни 26-31 используют Ollama-модели из README конкретного задания.
- Реальный API-ключ хранится только в `.env` или переменных окружения. `.env`, `.certs/`, build outputs, history/summary/tmp-файлы не коммитятся.
- Для продолжения после сжатия контекста сначала читать [AGENTS.md](AGENTS.md), затем [skills/course-continuity/SKILL.md](skills/course-continuity/SKILL.md).
- Для проверки текущего состояния полезнее всего запускать Day 33 в `fixture-demo` и `eval-dry-run`: они офлайн показывают настоящий loopback MCP, ticket-aware hash RAG, source/fact IDs и cross-ticket isolation без секретов.
- День 26 не использует Eliza: он обращается только к loopback Ollama API `http://127.0.0.1:11434` и требует один раз скачать локальную модель.
- День 27 принимает `.txt`/`.md` заметку через браузер, держит её только в памяти и показывает локальный структурированный анализ через Ollama.
- День 28 использует локальные `nomic-embed-text` и `qwen3:14b`: Day 21 structured index → cosine retrieval → grounded ответ с sources/quotes; cloud нужен только для осознанного сравнения на том же контексте.
- День 29 измеряет baseline/optimized Q4 и Q8 на одинаковом локальном RAG-контексте.
- День 30 рассчитан на CPU VPS: локальный multi-pass Tesseract → подтверждённый INCI → exact retrieval и allowlist OCR corrections → локальная `qwen3:4b` → server-side grounded report; Ollama и приложение остаются на loopback, а web-вход публикуется через Caddy HTTPS с access token.
- День 31 индексирует только allowlisted root README/docs, получает текущую ветку и tracked files через embedded MCP, а `/help` формирует grounded ответ через локальную `qwen3:4b`; fixture/eval режимы работают офлайн.
- День 32 получает PR только как inert GitHub REST data, применяет один fail-closed cloud policy к allowlisted corpus и changed paths/patches/full diff/blobs, а PR больше configured file cap останавливает до partial review. Eliza получает только целые typed file/evidence items; validator и coverage используют ровно этот transmitted subset. CI не исполняет head PR: он checkout-ит только base SHA, а secret доступен лишь для non-draft same-repository PR.
- День 33 читает committed synthetic tickets/users только через два read-only MCP tools, обогащает retrieval query текущими typed facts и fail-closes в canonical unknown при weak retrieval, malformed/forged model JSON или cross-ticket ссылках. RAG cache недоверенный: fresh chunks задают exact IDs/metadata/text/fingerprints/`hash-v1` embeddings, poisoned cache rebuild-ится, а persistence использует unique no-follow temp в проверенной non-symlink директории. Offline fixture задаёт одинаковый auth-вопрос `ACCOUNT_LOCKED` и `INVALID_OTP/CLOCK_SKEW` тикетам и получает разные grounded ответы при `LLM calls=0`.

## Структура

```text
ai-course/
  AGENTS.md                 # Корневые инструкции для AI-агента и GitHub workflow
  skills/                   # Репозиторные skill-инструкции
  day-01-llm-rest-kotlin/   # День 1: REST-запрос к LLM API на Kotlin
  day-02-response-format-kotlin/ # День 2: формат и ограничения ответа
  day-03-reasoning-methods-kotlin/ # День 3: разные способы рассуждения
  day-04-temperature-kotlin/ # День 4: temperature
  day-05-model-versions-kotlin/ # День 5: версии моделей
  day-06-first-agent-kotlin/ # День 6: первый агент
  day-07-persistent-context-kotlin/ # День 7: сохранение контекста
  day-08-token-accounting-kotlin/ # День 8: работа с токенами
  day-09-history-compression-kotlin/ # День 9: сжатие истории
  day-10-context-strategies-kotlin/ # День 10: стратегии контекста без summary
  day-11-memory-layers-kotlin/ # День 11: модель памяти ассистента
  day-12-personalization-kotlin/ # День 12: персонализация ассистента
  day-13-task-state-machine-kotlin/ # День 13: Task State Machine
  day-14-state-invariants-kotlin/ # День 14: инварианты состояния
  day-15-controlled-transitions-kotlin/ # День 15: контролируемые переходы
  day-16-mcp-connection-kotlin/ # День 16: подключение MCP
  day-17-telegram-mcp-tool-kotlin/ # День 17: свой MCP tool для Telegram
  day-18-telegram-course-scheduler-kotlin/ # День 18: scheduler поверх Telegram MCP
  day-19-mcp-tool-composition-kotlin/ # День 19: композиция MCP-инструментов
  day-20-mcp-orchestration-kotlin/ # День 20: orchestration нескольких MCP-серверов
  day-21-document-indexing-kotlin/ # День 21: индексация документов, embeddings, chunking
  day-22-first-rag-query-kotlin/ # День 22: первый RAG-запрос и сравнение с no-RAG
  day-23-reranking-filtering-kotlin/ # День 23: RAG reranking, filtering, query rewrite
  day-24-citations-anti-hallucination-kotlin/ # День 24: citations, sources, anti-hallucination
  day-25-rag-memory-chat-kotlin/ # День 25: mini-chat with RAG, sources, task memory
  day-26-local-llm-kotlin/       # День 26: локальная LLM через Ollama
  day-27-local-notes-web-kotlin/ # День 27: локальный веб-анализатор заметок
  day-28-local-rag-kotlin/       # День 28: local RAG + cloud comparison
  day-29-local-llm-optimization-kotlin/ # День 29: оптимизация local RAG на Qwen3
  day-30-private-cosmetics-service-kotlin/ # День 30: приватный LLM-сервис анализа косметики
  day-31-developer-assistant-kotlin/ # День 31: RAG + MCP ассистент разработчика
  day-32-ai-code-review-kotlin/ # День 32: безопасный AI reviewer для PR
  day-33-support-assistant-kotlin/ # День 33: support assistant с RAG + MCP
  gradle/                   # Gradle Wrapper
  gradlew
  settings.gradle.kts
```

## Задания

- [День 1: REST-запрос к LLM API на Kotlin](day-01-llm-rest-kotlin/README.md)
- [День 2: Формат ответа](day-02-response-format-kotlin/README.md)
- [День 3: Разные способы рассуждения](day-03-reasoning-methods-kotlin/README.md)
- [День 4: Температура](day-04-temperature-kotlin/README.md)
- [День 5: Версии моделей](day-05-model-versions-kotlin/README.md)
- [День 6: Первый агент](day-06-first-agent-kotlin/README.md)
- [День 7: Сохранение контекста](day-07-persistent-context-kotlin/README.md)
- [День 8: Работа с токенами](day-08-token-accounting-kotlin/README.md)
- [День 9: Управление контекстом — сжатие истории](day-09-history-compression-kotlin/README.md)
- [День 10: Управление контекстом разными стратегиями](day-10-context-strategies-kotlin/README.md)
- [День 11: Модель памяти ассистента](day-11-memory-layers-kotlin/README.md)
- [День 12: Персонализация ассистента](day-12-personalization-kotlin/README.md)
- [День 13: Состояние задачи](day-13-task-state-machine-kotlin/README.md)
- [День 14: Инварианты и ограничения состояния](day-14-state-invariants-kotlin/README.md)
- [День 15: Контролируемые переходы состояний](day-15-controlled-transitions-kotlin/README.md)
- [День 16: Подключение MCP](day-16-mcp-connection-kotlin/README.md)
- [День 17: Первый MCP-инструмент для Telegram](day-17-telegram-mcp-tool-kotlin/README.md)
- [День 18: Telegram Course Scheduler MCP](day-18-telegram-course-scheduler-kotlin/README.md)
- [День 19: Композиция MCP-инструментов](day-19-mcp-tool-composition-kotlin/README.md)
- [День 20: Orchestration MCP](day-20-mcp-orchestration-kotlin/README.md)
- [День 21: Индексация документов](day-21-document-indexing-kotlin/README.md)
- [День 22: Первый RAG-запрос](day-22-first-rag-query-kotlin/README.md)
- [День 23: Реранкинг и фильтрация](day-23-reranking-filtering-kotlin/README.md)
- [День 24: Цитаты, источники и анти-галлюцинации](day-24-citations-anti-hallucination-kotlin/README.md)
- [День 25: Мини-чат с RAG + памятью задачи](day-25-rag-memory-chat-kotlin/README.md)
- [День 26: Запуск локальной LLM](day-26-local-llm-kotlin/README.md)
- [День 27: Веб-анализатор заметок с локальной LLM](day-27-local-notes-web-kotlin/README.md)
- [День 28: Локальная LLM + RAG](day-28-local-rag-kotlin/README.md)
- [День 29: Оптимизация локальной LLM](day-29-local-llm-optimization-kotlin/README.md)
- [День 30: Приватный сервис анализа косметики](day-30-private-cosmetics-service-kotlin/README.md)
- [День 31: Ассистент разработчика с RAG и MCP](day-31-developer-assistant-kotlin/README.md)
- [День 32: AI code review для pull request](day-32-ai-code-review-kotlin/README.md)
- [День 33: Ассистент поддержки пользователей](day-33-support-assistant-kotlin/README.md)

## Быстрая Карта Дней

| День | Папка | Суть | Основная команда |
|---:|---|---|---|
| 1 | `day-01-llm-rest-kotlin` | первый прямой REST-запрос к LLM | `day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"` |
| 2 | `day-02-response-format-kotlin` | формат, длина, завершение ответа | `day-02-response-format-kotlin/scripts/run-eliza.sh` |
| 3 | `day-03-reasoning-methods-kotlin` | direct, step-by-step, prompt-first, experts | `day-03-reasoning-methods-kotlin/scripts/run-eliza.sh` |
| 4 | `day-04-temperature-kotlin` | сравнение temperature | `day-04-temperature-kotlin/scripts/run-eliza.sh` |
| 5 | `day-05-model-versions-kotlin` | weak/medium/strong модели, токены, стоимость | `day-05-model-versions-kotlin/scripts/run-eliza.sh` |
| 6 | `day-06-first-agent-kotlin` | первый CLI-агент с памятью процесса | `day-06-first-agent-kotlin/scripts/run-eliza.sh` |
| 7 | `day-07-persistent-context-kotlin` | persistent JSON context | `day-07-persistent-context-kotlin/scripts/run-eliza.sh` |
| 8 | `day-08-token-accounting-kotlin` | токены, стоимость, переполнение контекста | `day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="short"` |
| 9 | `day-09-history-compression-kotlin` | summary compression и multi-сравнение | `day-09-history-compression-kotlin/scripts/run-eliza.sh --args="multi"` |
| 10 | `day-10-context-strategies-kotlin` | sliding, facts, branching без summary | `day-10-context-strategies-kotlin/scripts/run-eliza.sh` |
| 11 | `day-11-memory-layers-kotlin` | short-term, working, long-term memory layers | `day-11-memory-layers-kotlin/scripts/run-eliza.sh` |
| 12 | `day-12-personalization-kotlin` | активные профили пользователя поверх memory layers | `day-12-personalization-kotlin/scripts/run-eliza.sh` |
| 13 | `day-13-task-state-machine-kotlin` | task state machine, pause/resume, stage agents | `day-13-task-state-machine-kotlin/scripts/run-eliza.sh` |
| 14 | `day-14-state-invariants-kotlin` | state machine из Day 13 + инварианты, audit, response validation | `day-14-state-invariants-kotlin/scripts/run-eliza.sh` |
| 15 | `day-15-controlled-transitions-kotlin` | controlled lifecycle, guards, state-owned agents | `day-15-controlled-transitions-kotlin/scripts/run-eliza.sh` |
| 16 | `day-16-mcp-connection-kotlin` | remote MCP connection и `tools/list` discovery | `day-16-mcp-connection-kotlin/scripts/run-mcp.sh` |
| 17 | `day-17-telegram-mcp-tool-kotlin` | свой read-only MCP tool вокруг Telegram history | `day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="fixture-demo"` |
| 18 | `day-18-telegram-course-scheduler-kotlin` | scheduler-agent вызывает MCP tool, сохраняет Telegram day prompt | `day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="fixture-demo"` |
| 19 | `day-19-mcp-tool-composition-kotlin` | agent вызывает MCP tools цепочкой search -> summarize -> save | `day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="fixture-demo"` |
| 20 | `day-20-mcp-orchestration-kotlin` | agent оркестрирует tools с 4 MCP servers в длинном flow | `day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="fixture-demo"` |
| 21 | `day-21-document-indexing-kotlin` | documents -> chunks -> embeddings -> JSON index + comparison | `day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="fixture-demo"` |
| 22 | `day-22-first-rag-query-kotlin` | question -> retrieve chunks -> RAG prompt -> LLM, плюс no-RAG comparison | `day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="fixture-demo"` |
| 23 | `day-23-reranking-filtering-kotlin` | query rewrite -> top-K before -> rerank/filter -> top-K after | `day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="fixture-demo"` |
| 24 | `day-24-citations-anti-hallucination-kotlin` | grounded RAG answer -> sources -> quotes -> validation / unknown gate | `day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="fixture-demo"` |
| 25 | `day-25-rag-memory-chat-kotlin` | persisted mini-chat -> task state -> RAG every turn -> sources/quotes | `day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="fixture-demo"` |
| 26 | `day-26-local-llm-kotlin` | Ollama + Qwen3 14B локально, CLI и 3 HTTP-запроса | `day-26-local-llm-kotlin/scripts/run-local-llm.sh` |
| 27 | `day-27-local-notes-web-kotlin` | `.txt`/`.md` upload -> local Ollama -> structured web report | `day-27-local-notes-web-kotlin/scripts/run-notes-web.sh` |
| 28 | `day-28-local-rag-kotlin` | Day 21 index -> local embeddings/retrieval -> Qwen3 grounded RAG, optional cloud compare | `day-28-local-rag-kotlin/scripts/run-local-rag.sh --args="diagnose"` |
| 29 | `day-29-local-llm-optimization-kotlin` | baseline Q4 -> optimized Q4 -> Q8 на одинаковом local RAG context | `day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh --args="diagnose"` |
| 30 | `day-30-private-cosmetics-service-kotlin` | private VPS API: OCR -> INCI retrieval -> local LLM -> grounded report/chat | `day-30-private-cosmetics-service-kotlin/scripts/run-local.sh fixture-demo` |
| 31 | `day-31-developer-assistant-kotlin` | allowlisted project-doc RAG + read-only git MCP + grounded `/help` | `day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="fixture-demo"` |
| 32 | `day-32-ai-code-review-kotlin` | bounded PR patches -> direct Eliza review -> Russian sticky comment | `day-32-ai-code-review-kotlin/scripts/run-review.sh --args="fixture-demo"` |
| 33 | `day-33-support-assistant-kotlin` | synthetic ticket/user MCP -> ticket-aware RAG -> grounded support answer | `day-33-support-assistant-kotlin/scripts/run-support.sh --args="fixture-demo"` |

## Запуск дня 1

Для Eliza:

```bash
day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-01-llm-rest-kotlin:build
```

## Запуск дня 2

Для Eliza:

```bash
day-02-response-format-kotlin/scripts/run-eliza.sh
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-02-response-format-kotlin:build
```

## Запуск дня 3

Для Eliza:

```bash
day-03-reasoning-methods-kotlin/scripts/run-eliza.sh
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-03-reasoning-methods-kotlin:build
```

## Запуск дня 4

Для Eliza DeepSeek:

```bash
day-04-temperature-kotlin/scripts/run-eliza.sh
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-04-temperature-kotlin:build
```

## Запуск дня 5

Для Eliza/OpenRouter:

```bash
day-05-model-versions-kotlin/scripts/run-eliza.sh
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-05-model-versions-kotlin:build
```

## Запуск дня 6

Для Eliza:

```bash
day-06-first-agent-kotlin/scripts/run-eliza.sh
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-06-first-agent-kotlin:build
```

## Запуск дня 7

Для Eliza:

```bash
day-07-persistent-context-kotlin/scripts/run-eliza.sh
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-07-persistent-context-kotlin:build
```

## Запуск дня 8

Для Eliza:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="short"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="long"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="forgetting"
APP_CONTEXT_LIMIT_TOKENS=800 day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="overflow"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-dry-run"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-08-token-accounting-kotlin:build
```

## Запуск дня 9

Для Eliza:

```bash
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="compare"
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="multi"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-09-history-compression-kotlin:build
```

Фактический последний прогон `multi` показал:

```text
Full prompt tokens total: 3595
Compressed prompt tokens total: 1456
Saved prompt tokens: 2139 / 59.5%
Summary creation tokens: 2842
Full answer cost total: $0.001216
Compressed answer cost total: $0.000358
Summary cost total: $0.000505
Compressed total: $0.000863
Quality trade-off: Tokyo travel compressed answer lost "свободный день".
```

## Запуск дня 10

Для Eliza:

```bash
day-10-context-strategies-kotlin/scripts/run-eliza.sh
day-10-context-strategies-kotlin/scripts/run-eliza.sh --args="interactive"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-10-context-strategies-kotlin:build
```

## Запуск дня 11

Для Eliza:

```bash
day-11-memory-layers-kotlin/scripts/run-eliza.sh
day-11-memory-layers-kotlin/scripts/run-eliza.sh --args="interactive"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-11-memory-layers-kotlin:build
```

## Запуск дня 12

Для Eliza:

```bash
day-12-personalization-kotlin/scripts/run-eliza.sh
day-12-personalization-kotlin/scripts/run-eliza.sh --args="interactive"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-12-personalization-kotlin:build
```

## Запуск дня 13

Для Eliza:

```bash
day-13-task-state-machine-kotlin/scripts/run-eliza.sh
day-13-task-state-machine-kotlin/scripts/run-eliza.sh --args="pause-demo"
day-13-task-state-machine-kotlin/scripts/run-eliza.sh --args="interactive"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-13-task-state-machine-kotlin:build
```

## Запуск дня 14

Для Eliza:

```bash
day-14-state-invariants-kotlin/scripts/run-eliza.sh
day-14-state-invariants-kotlin/scripts/run-eliza.sh --args="checker-demo"
day-14-state-invariants-kotlin/scripts/run-eliza.sh --args="interactive"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-14-state-invariants-kotlin:build
```

## Запуск дня 15

Для Eliza:

```bash
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh --args="transition-tests"
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh --args="pause-resume-demo"
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh --args="interactive"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-15-controlled-transitions-kotlin:build
```

## Запуск дня 16

Для public DeepWiki MCP:

```bash
day-16-mcp-connection-kotlin/scripts/run-mcp.sh
day-16-mcp-connection-kotlin/scripts/run-mcp.sh --args="raw-check"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-16-mcp-connection-kotlin:build
```

## Запуск дня 17

Для локального Telegram MCP tool в offline fixture-режиме:

```bash
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="fixture-demo"
day-17-telegram-mcp-tool-kotlin/scripts/run-mcp.sh --args="raw-check"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-17-telegram-mcp-tool-kotlin:build
```

## Запуск дня 18

Для Telegram course scheduler MCP в offline fixture-режиме:

```bash
day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="fixture-demo"
LLM_API_KEY= COURSE_DAY=18 SCHEDULE_INTERVAL_SECONDS=5 SCHEDULER_RUNS=2 day-18-telegram-course-scheduler-kotlin/scripts/run-scheduler.sh --args="scheduler-demo"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-18-telegram-course-scheduler-kotlin:build
```

## Запуск дня 19

Для MCP tool composition pipeline в offline fixture-режиме:

```bash
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="fixture-demo"
day-19-mcp-tool-composition-kotlin/scripts/run-pipeline.sh --args="raw-check"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-19-mcp-tool-composition-kotlin:build
```

## Запуск дня 20

Для multi-server MCP orchestration в offline fixture-режиме:

```bash
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="fixture-demo"
day-20-mcp-orchestration-kotlin/scripts/run-orchestration.sh --args="raw-check"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-20-mcp-orchestration-kotlin:build
```

## Запуск дня 21

Для индексации документов в offline fixture-режиме:

```bash
day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="fixture-demo"
day-21-document-indexing-kotlin/scripts/run-indexer.sh --args="search structured \"как устроен MCP orchestration\""
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-21-document-indexing-kotlin:build
```

## Запуск дня 22

Для первого RAG-запроса в offline fixture-режиме:

```bash
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="fixture-demo"
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="eval-dry-run"
```

Для live-сравнения no-RAG vs RAG:

```bash
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="compare-demo"
day-22-first-rag-query-kotlin/scripts/run-rag.sh --args="ask rag \"как запустить Day 21 offline demo?\""
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-22-first-rag-query-kotlin:build
```

## Запуск дня 23

Для RAG reranking/filtering в offline fixture-режиме:

```bash
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="fixture-demo"
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="compare-dry-run"
```

Для live-сравнения baseline RAG vs reranked RAG:

```bash
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="compare-demo"
day-23-reranking-filtering-kotlin/scripts/run-rerank.sh --args="ask reranked \"как запустить Day 21 offline demo?\""
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-23-reranking-filtering-kotlin:build
```

## Запуск дня 24

Для citations/sources/anti-hallucination в offline fixture-режиме:

```bash
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="fixture-demo"
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="eval-dry-run"
```

Для prompt preview или live grounded answer:

```bash
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="ask-dry-run \"как запустить Day 21 offline demo?\""
day-24-citations-anti-hallucination-kotlin/scripts/run-citations.sh --args="ask \"как запустить Day 21 offline demo?\""
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-24-citations-anti-hallucination-kotlin:build
```

## Запуск дня 25

Для RAG mini-chat с task memory в offline fixture-режиме:

```bash
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="fixture-demo"
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="scenario-dry-run"
```

Для одного turn без live LLM или live chat:

```bash
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="ask-dry-run \"как запустить Day 21 offline demo?\""
day-25-rag-memory-chat-kotlin/scripts/run-chat.sh --args="chat"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-25-rag-memory-chat-kotlin:build
```

## Запуск дня 26

Сначала запустите Ollama и один раз скачайте локальную модель:

```bash
ollama serve
ollama pull qwen3:14b
ollama run qwen3:14b "Ответь одним предложением: что такое локальная LLM?"
```

Затем проверьте loopback API и три запроса из Kotlin CLI:

```bash
day-26-local-llm-kotlin/scripts/run-local-llm.sh --args="diagnose"
day-26-local-llm-kotlin/scripts/run-local-llm.sh
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-26-local-llm-kotlin:build
```

## Запуск дня 27

После установки модели из дня 26 проверьте Ollama и запустите локальное веб-приложение:

```bash
day-27-local-notes-web-kotlin/scripts/run-notes-web.sh --args="diagnose"
day-27-local-notes-web-kotlin/scripts/run-notes-web.sh
```

Откройте `http://127.0.0.1:8787`, загрузите `.txt` или `.md` заметку и получите структурированный отчёт. Полная инструкция, curl API и video scenario — в [README дня 27](day-27-local-notes-web-kotlin/README.md).

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-27-local-notes-web-kotlin:build
```

## Запуск дня 28

День 28 требует локальные `qwen3:14b`, `nomic-embed-text` и настоящий Ollama structured index Дня 21. Сначала проверьте готовность:

```bash
ollama pull qwen3:14b
ollama pull nomic-embed-text
day-28-local-rag-kotlin/scripts/run-local-rag.sh --args="diagnose"
```

Один полностью локальный RAG-вопрос:

```bash
day-28-local-rag-kotlin/scripts/run-local-rag.sh \
  --args='local "Как запустить Day 21 offline indexing demo?"'
```

`compare` и default `benchmark` дополнительно используют локальный Day 1 OAuth config и передают retrieved course chunks в configured cloud endpoint. Полная инструкция, команды пересборки index, privacy warning и video scenario — в [README дня 28](day-28-local-rag-kotlin/README.md).

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-28-local-rag-kotlin:build
```

## Запуск дня 29

День 29 сравнивает baseline Q4, оптимизированный Q4 и Q8-вариант `qwen3:14b` на одинаковом локальном RAG-контексте Дня 21. Подготовьте модели и проверьте зависимости:

```bash
ollama pull qwen3:14b
ollama pull qwen3:14b-q8_0
ollama pull nomic-embed-text
day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh --args="diagnose"
```

Один профиль и полный benchmark:

```bash
day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh \
  --args='profile optimized-q4 "Как запустить Day 21 offline indexing demo?"'
day-29-local-llm-optimization-kotlin/scripts/run-optimization.sh
```

Команда по умолчанию выполняет 3 вопроса × 3 профиля × 3 повтора и сохраняет локальный отчёт в ignored `reports/`. Полные setup, метрики и video scenario — в [README дня 29](day-29-local-llm-optimization-kotlin/README.md).

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-29-local-llm-optimization-kotlin:build
```

## Запуск дня 30

Day 30 превращает локальную LLM в приватный web/API-сервис для анализа косметики. Основной VPS-профиль — `qwen3:4b`, один inference одновременно, bounded queue, Bearer auth, rate/context limits и RAM-only chat. Фото распознаётся локальным Tesseract; внешние AI/OCR API не используются. Недостаточное OCR-покрытие останавливается до LLM, а выводы и ссылки собираются как проверяемые claim → source пары.

Offline-проверки без Ollama:

```bash
./gradlew :day-30-private-cosmetics-service-kotlin:test
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh fixture-demo
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh eval-dry-run
```

Локальный запуск после установки Ollama/Tesseract:

```bash
ollama pull qwen3:4b
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh diagnose
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh
```

VPS systemd assets, HTTP examples, smoke/load/rate-limit checks и video scenario — в [README дня 30](day-30-private-cosmetics-service-kotlin/README.md).

Обычная Gradle-команда:

```bash
./gradlew :day-30-private-cosmetics-service-kotlin:build
```

## Запуск дня 31

Day 31 — локальный CLI-ассистент разработчика. RAG индексирует корневой README, `docs/project-architecture.md`, `docs/developer-assistant-api.yaml` и README самого дня. Embedded MCP возвращает реальную текущую git-ветку и bounded tracked file list. MCP-only вопросы обходят generator; mixed model prompt содержит только документацию, а exact `projectBranch`/`projectFiles` финально присваиваются server-side. Sensitive topics о secrets/certificates отклоняются до index, embeddings, MCP, prompt и model. Live `/help` использует только loopback Ollama; cloud API не вызывается.

Offline-проверки:

```bash
./gradlew :day-31-developer-assistant-kotlin:test
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="mcp-smoke"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="fixture-demo"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="eval-dry-run"
```

Live запуск требует двух терминалов. В **Terminal A** сначала запустите Ollama server и оставьте его работающим:

```bash
ollama serve
```

Если Ollama desktop app уже запущен, отдельный `ollama serve` не нужен. Пока server работает, в **Terminal B**:

```bash
ollama pull qwen3:4b
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="diagnose"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh
```

Полный corpus allowlist, MCP schemas, expected output, privacy boundary и video scenario — в [README дня 31](day-31-developer-assistant-kotlin/README.md).

Обычная Gradle-команда:

```bash
./gradlew :day-31-developer-assistant-kotlin:build
```

## Запуск дня 32

Day 32 — AI reviewer для pull request. CLI берёт bounded metadata, patches и
changed blobs через GitHub REST, строит RAG по документации и коду trusted base,
останавливает cloud review до HTTP при sensitive changed path/patch/blob,
формирует prompt только из целых file/evidence items и проверяет structured
result против exact transmitted subset до рендеринга. Офлайн fixtures показывают
полный flow без GitHub token и live LLM.

```bash
./gradlew :day-32-ai-code-review-kotlin:test
./gradlew :day-32-ai-code-review-kotlin:build
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="fixture-demo"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="eval-dry-run"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="prompt-dry-run"
```

Для live локального запуска скопируйте `.env.example` в `.env`, укажите OAuth token и при необходимости создайте truststore:

```bash
cp day-32-ai-code-review-kotlin/.env.example day-32-ai-code-review-kotlin/.env
day-32-ai-code-review-kotlin/scripts/setup-yandex-ca.sh
```

GitHub workflow [ai-code-review.yml](.github/workflows/ai-code-review.yml) запускается только для non-draft PR из этого же репозитория в default branch. Он pin-ит official actions, checkout-ит только exact default-base SHA и получает данные PR через GitHub REST; head revision не checkout-ится и не выполняется. Поэтому он не может проверить PR, который впервые добавляет сам workflow: сначала merge Day 32, затем откройте отдельный same-repository PR для видео. Полная архитектура, expected sticky comment и сценарий записи — в [README дня 32](day-32-ai-code-review-kotlin/README.md).

## Запуск дня 33

Day 33 — Kotlin support assistant для вымышленного продукта. Embedded
Streamable HTTP MCP рекламирует ровно `support_get_ticket` и
`support_get_user`, strict repository читает только committed
`synthetic=true` JSON, а RAG индексирует четыре allowlisted FAQ/docs файла.
Текущий error code, product area, account state и другие typed facts входят в
retrieval query, поэтому одинаковый вопрос даёт разные ответы для разных
тикетов.

Полная offline-проверка:

```bash
./gradlew :day-33-support-assistant-kotlin:test
./gradlew :day-33-support-assistant-kotlin:build
day-33-support-assistant-kotlin/scripts/run-support.sh --args="mcp-smoke"
day-33-support-assistant-kotlin/scripts/run-support.sh --args="fixture-demo"
day-33-support-assistant-kotlin/scripts/run-support.sh --args="eval-dry-run"
day-33-support-assistant-kotlin/scripts/run-support.sh \
  --args="prompt-dry-run TCK-1001 Почему не работает авторизация?"
```

Один optional live Eliza call после локальной настройки `.env` и truststore:

```bash
day-33-support-assistant-kotlin/scripts/setup-yandex-ca.sh
day-33-support-assistant-kotlin/scripts/run-support.sh \
  --args="ask TCK-1001 Почему не работает авторизация?"
```

Committed fixture не содержит реальных клиентов/PII. Реальная CRM и support
write actions не поддерживаются. MCP schemas, expected terminal labels,
failure behavior и video scenario — в [README дня 33](day-33-support-assistant-kotlin/README.md).

## Правила безопасности

- Не коммитить `.env`.
- Не коммитить `.certs/`.
- Не публиковать реальные API-ключи, OAuth-токены и приватные сертификаты.
- Не коммитить VPS Bearer token, SSH private keys или заполненный `/etc/cosmetics-ai/cosmetics-ai.env`.
- Не коммитить Day 31 RAG index/runtime и не расширять его corpus за пределы reviewed allowlist без отдельной проверки.
- Для Day 32 не передавать secret через PR text, logs или `.env` в CI; использовать только GitHub secret `LLM_API_KEY`. Никогда не менять base-only checkout на checkout head в `pull_request_target`.
- Day 32 `reviewedFiles` означает только exact file items, реально переданные в
  успешный model call; fetched/parsed/binary/incomplete/omitted files не входят.
- Day 32 cloud policy извлекает quoted/escaped JSON credential values вместе со
  special characters вроде `$` и проверяет plain/quoted/escaped
  `Authorization` для scheme, принятой `AppConfig`, включая `OAuth`; model
  title/detail/recommendation проходят тот же sensitive-content gate до
  renderer/publisher.
- Day 33 live Eliza разрешён только для committed synthetic fixture. Не
  подменять его real CRM export: live preflight сверяет reviewed SHA-256
  fixture/knowledge и transmitted evidence, а sensitive-looking question/history
  останавливает до HTTP. OAuth destination pinned на canonical Eliza URI;
  shared Day 1 token загружается только после этой проверки. Fixture/index
  читаются bounded через `NOFOLLOW_LINKS`; cache chunks полностью сверяются со
  fresh corpus и deterministic embeddings, а запись использует unique
  `CREATE_NEW`/`NOFOLLOW_LINKS` temp внутри real non-symlink directory. Не
  расширять MCP list/search/write tools и не коммитить
  `runtime/rag-index.json`, reports, `.env`, `.certs` или chat data.
- Для публичного репозитория внимательно проверить, что внутренние корпоративные URL допустимо показывать наружу.

## GitHub Workflow

Корневые правила для работы с репозиторием лежат в [AGENTS.md](AGENTS.md).

Дополнительный skill для GitHub и курса:

- [skills/github-course-workflow/SKILL.md](skills/github-course-workflow/SKILL.md)
- [skills/course-continuity/SKILL.md](skills/course-continuity/SKILL.md)
