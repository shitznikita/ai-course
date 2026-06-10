# День 6: Первый агент

Минимальный CLI-чат с отдельной сущностью `Agent`. Агент хранит историю текущей сессии, собирает REST-запрос к LLM, отправляет его через HTTP-клиент и возвращает ответ интерфейсу.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI-чат
- API: Eliza API
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- Модель: `meta-llama/llama-3.3-70b-instruct`
- HTTP-клиент: `java.net.http.HttpClient`
- Ключ: только через `.env` или переменные окружения

## Роль агента

```text
Ты StudyAgent — учебный помощник. Ты отвечаешь кратко, понятно и задаешь уточняющие вопросы, если запрос пользователя неполный. Используй историю текущей сессии, чтобы помнить факты, которые пользователь уже сообщил.
```

## Структура

```text
day-06-first-agent-kotlin/
  .env.example
  README.md
  build.gradle.kts
  scripts/
    run-eliza.sh
    setup-yandex-ca.sh
  src/main/kotlin/
    Main.kt
```

## Как работает Agent

`Main.kt` содержит два слоя:

- CLI-интерфейс: читает ввод пользователя, обрабатывает `exit`/`quit`/`/exit`, передает сообщение агенту и печатает ответ.
- `Agent`: хранит `provider/model config`, `system prompt`, историю сообщений текущей сессии, метод `ask`, метод `buildRequest`, метод `callLLM`, парсинг ответа и добавление `user`/`assistant` сообщений в историю.

CLI не собирает REST-запрос сам. Это делает агент.

## План

1. Создать отдельный класс `Agent`.
2. В агенте хранить `system` сообщение и историю текущей сессии.
3. Сделать метод `ask(userMessage)`.
4. В `ask` добавить user-сообщение в историю.
5. Собрать REST body через `buildRequest`.
6. Отправить запрос через `callLLM`.
7. Распарсить ответ модели.
8. Добавить assistant-сообщение в историю.
9. Вернуть ответ в CLI.

## Чек-лист

- [ ] Есть отдельная сущность `Agent`.
- [ ] Есть system prompt / роль агента.
- [ ] История сообщений хранится в текущей сессии.
- [ ] CLI не собирает REST body сам.
- [ ] Agent собирает REST body через `buildRequest`.
- [ ] Agent вызывает LLM API через HTTP-клиент.
- [ ] Agent добавляет user и assistant сообщения в историю.
- [ ] Есть команды выхода: `exit`, `quit`, `/exit`.
- [ ] Есть debug-режим для просмотра request body без API-ключа.
- [ ] API-ключ не хранится в коде.

## Настройка API-ключа

Можно использовать `.env` из дня 1. Скрипт `run-eliza.sh` сам подхватит `day-01-llm-rest-kotlin/.env`.

Если нужен отдельный `.env` для дня 6:

```bash
cp day-06-first-agent-kotlin/.env.example day-06-first-agent-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
AGENT_DEBUG=false
```

Файл `.env` нельзя коммитить.

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza ещё не создан:

```bash
day-06-first-agent-kotlin/scripts/setup-yandex-ca.sh
```

Если truststore уже есть в `day-01-llm-rest-kotlin/.certs/`, `run-eliza.sh` подхватит его автоматически.

Сборка:

```bash
./gradlew :day-06-first-agent-kotlin:build
```

Запуск:

```bash
day-06-first-agent-kotlin/scripts/run-eliza.sh
```

Скрипт запускает Gradle с `--console=plain --quiet`, чтобы Gradle не перерисовывал интерактивную строку ввода в терминале.

Debug-режим можно включить переменной:

```bash
AGENT_DEBUG=true day-06-first-agent-kotlin/scripts/run-eliza.sh
```

Или прямо в чате командой:

```text
/debug
```

## Проверка памяти сессии

В чате введи:

```text
User: Привет, меня зовут Никита
Agent: ...

User: Как меня зовут?
Agent: Тебя зовут Никита.
```

Второй ответ должен использовать историю текущей сессии. После перезапуска программы история очищается.

## Пример debug request body

В debug-режиме во втором запросе будет видно, что в `messages` ушли:

```json
[
  {"role": "system", "content": "..."},
  {"role": "user", "content": "Привет, меня зовут Никита"},
  {"role": "assistant", "content": "..."},
  {"role": "user", "content": "Как меня зовут?"}
]
```

API-ключ в request body не печатается.

## Сценарий видео

1. Показать структуру папки `day-06-first-agent-kotlin`.
2. Открыть `Main.kt` и показать отдельный класс `Agent`.
3. Показать, что `Agent` хранит `messages` и system prompt.
4. Показать методы `ask`, `buildRequest`, `callLLM`.
5. Показать `.env.example` и объяснить, что реальный ключ не хардкодится.
6. Запустить:

```bash
AGENT_DEBUG=true day-06-first-agent-kotlin/scripts/run-eliza.sh
```

7. Отправить сообщение: `Привет, меня зовут Никита`.
8. Отправить второе сообщение: `Как меня зовут?`.
9. Показать, что во втором debug body есть вся история.
10. Показать, что агент отвечает с учетом истории.
11. Коротко сказать: CLI только передает ввод, а логика контекста, памяти и API-вызова инкапсулирована в `Agent`.

## Проверка требований

- Используется LLM API через HTTP-клиент: да.
- Это чат с накоплением истории: да.
- Это не серия независимых one-shot запросов: да.
- История хранится внутри агента в текущей сессии: да.
- Есть отдельная сущность `Agent`: да.
- Есть system prompt / роль агента: да.
- API-ключ не хардкодится: да.
- Есть debug request body без API-ключа: да.
