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
  gradle/                   # Gradle Wrapper
  gradlew
  settings.gradle.kts
```

## Задания

- [День 1: REST-запрос к LLM API на Kotlin](day-01-llm-rest-kotlin/README.md)
- [День 2: Формат ответа](day-02-response-format-kotlin/README.md)
- [День 3: Разные способы рассуждения](day-03-reasoning-methods-kotlin/README.md)

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
