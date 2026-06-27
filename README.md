# AI Course

Репозиторий для заданий курса по AI/LLM.

## Current Snapshot

- Статус на `2026-06-12`: на `main` есть завершенные задания дней 1-9, день 10 готовится отдельной веткой.
- Основной стек всех последних заданий: Kotlin CLI + Gradle + прямой REST через `java.net.http.HttpClient`.
- Основной провайдер: Eliza API, OpenRouter-compatible endpoint `https://api.eliza.yandex.net/openrouter/v1/chat/completions`.
- Основная модель для дней 6-10: `meta-llama/llama-3.3-70b-instruct`.
- Реальный API-ключ хранится только в `.env` или переменных окружения. `.env`, `.certs/`, build outputs, history/summary/tmp-файлы не коммитятся.
- Для продолжения после сжатия контекста сначала читать [AGENTS.md](AGENTS.md), затем [skills/course-continuity/SKILL.md](skills/course-continuity/SKILL.md).
- Для проверки текущего состояния полезнее всего запускать день 9 в режиме `multi`, потому что он показывает несколько тематик и финальную таблицу расходов.

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

## Правила безопасности

- Не коммитить `.env`.
- Не коммитить `.certs/`.
- Не публиковать реальные API-ключи, OAuth-токены и приватные сертификаты.
- Для публичного репозитория внимательно проверить, что внутренние корпоративные URL допустимо показывать наружу.

## GitHub Workflow

Корневые правила для работы с репозиторием лежат в [AGENTS.md](AGENTS.md).

Дополнительный skill для GitHub и курса:

- [skills/github-course-workflow/SKILL.md](skills/github-course-workflow/SKILL.md)
- [skills/course-continuity/SKILL.md](skills/course-continuity/SKILL.md)
