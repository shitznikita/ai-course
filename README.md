# AI Course

Репозиторий для заданий курса по AI/LLM.

## Структура

```text
ai-course/
  day-01-llm-rest-kotlin/   # День 1: REST-запрос к LLM API на Kotlin
  gradle/                   # Gradle Wrapper
  gradlew
  settings.gradle.kts
```

## Задания

- [День 1: REST-запрос к LLM API на Kotlin](day-01-llm-rest-kotlin/README.md)

## Запуск дня 1

Для Eliza:

```bash
day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"
```

Обычная Gradle-команда для сборки:

```bash
./gradlew :day-01-llm-rest-kotlin:build
```

## Правила безопасности

- Не коммитить `.env`.
- Не коммитить `.certs/`.
- Не публиковать реальные API-ключи, OAuth-токены и приватные сертификаты.
- Для публичного репозитория внимательно проверить, что внутренние корпоративные URL допустимо показывать наружу.
