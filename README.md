# AI Course

Репозиторий для заданий курса по AI/LLM.

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
