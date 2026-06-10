# День 7: Сохранение контекста

CLI-чат с агентом, который сохраняет историю сообщений в JSON-файл и загружает её при следующем запуске. Это продолжение дня 6: там память жила только в процессе, а здесь контекст восстанавливается между сессиями.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI-чат
- API: Eliza API через OpenRouter-compatible endpoint
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- Модель: `meta-llama/llama-3.3-70b-instruct`
- HTTP-клиент: `java.net.http.HttpClient`
- Хранилище истории: JSON-файл `agent-history.json`
- Ключ: только через `.env` или переменные окружения

## Структура

```text
day-07-persistent-context-kotlin/
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

`Agent` хранит:

- provider/model config;
- system prompt;
- историю сообщений;
- путь к JSON-файлу истории;
- метод `ask`;
- метод `buildRequest`;
- метод `callLLM`;
- методы `loadHistory`, `saveHistory`, `clearHistory`.

При старте агент проверяет файл истории:

- если файл есть, загружает messages из JSON;
- если файла нет, начинает новый диалог с system prompt;
- после user-сообщения сохраняет историю;
- после assistant-ответа снова сохраняет историю.

CLI не управляет LLM-запросом и не сохраняет контекст сам. Он только читает ввод, передает сообщение в `agent.ask(...)` и печатает ответ.

## План

1. Взять агента из дня 6.
2. Добавить JSON-хранилище истории.
3. Загружать историю при старте.
4. Сохранять историю после каждого сообщения пользователя и ответа ассистента.
5. Добавить команду `/clear`.
6. Печатать путь к файлу истории и количество загруженных сообщений.
7. Оставить debug-режим с REST body без API-ключа.

## Чек-лист

- [ ] Есть отдельная сущность `Agent`.
- [ ] История хранится в JSON.
- [ ] История загружается при старте.
- [ ] История сохраняется после user-сообщения.
- [ ] История сохраняется после assistant-ответа.
- [ ] После перезапуска агент помнит прошлые сообщения.
- [ ] Есть `/clear` для очистки истории.
- [ ] Есть `exit`, `quit`, `/exit`.
- [ ] Есть debug request body без API-ключа.
- [ ] API-ключ не хранится в коде.

## Настройка API-ключа

Можно использовать `.env` из дня 1. Скрипт `run-eliza.sh` сам подхватит `day-01-llm-rest-kotlin/.env`.

Если нужен отдельный `.env` для дня 7:

```bash
cp day-07-persistent-context-kotlin/.env.example day-07-persistent-context-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
AGENT_DEBUG=false
AGENT_HISTORY_FILE=agent-history.json
```

Файл `.env` нельзя коммитить.

## Где хранится история

По умолчанию история хранится здесь:

```text
day-07-persistent-context-kotlin/agent-history.json
```

Файл добавлен в `.gitignore`, потому что это пользовательские данные. Путь можно поменять:

```bash
AGENT_HISTORY_FILE=/tmp/study-agent-history.json day-07-persistent-context-kotlin/scripts/run-eliza.sh
```

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza ещё не создан:

```bash
day-07-persistent-context-kotlin/scripts/setup-yandex-ca.sh
```

Если truststore уже есть в `day-01-llm-rest-kotlin/.certs/`, `run-eliza.sh` подхватит его автоматически.

Сборка:

```bash
./gradlew :day-07-persistent-context-kotlin:build
```

Запуск:

```bash
day-07-persistent-context-kotlin/scripts/run-eliza.sh
```

Debug-режим:

```bash
AGENT_DEBUG=true day-07-persistent-context-kotlin/scripts/run-eliza.sh
```

## Проверка восстановления контекста

Первый запуск:

```text
User: Привет, меня зовут Никита. Я делаю задание 7 дня.
Agent: ...
User: exit
```

Второй запуск:

```text
User: Как меня зовут и какое задание я делаю?
Agent: Тебя зовут Никита, ты делаешь задание 7 дня.
```

В консоли также будет видно:

- путь к файлу истории;
- сколько сообщений загружено;
- когда история сохранена.

## Очистка истории

В чате:

```text
/clear
```

Команда очищает список сообщений, удаляет сохраненный JSON и создает новую историю только с system prompt.

## Чем отличается от дня 6

В день 6 история была только в `mutableListOf(...)` внутри процесса. После перезапуска она исчезала.

В день 7 тот же список сообщений сохраняется в JSON-файл. При новом запуске агент читает файл и снова отправляет прошлые `messages` в REST API.

## Сценарий видео

1. Показать структуру папки `day-07-persistent-context-kotlin`.
2. Открыть `Main.kt` и показать `Agent`.
3. Показать `loadHistory`, `saveHistory`, `clearHistory`.
4. Показать `.env.example` и объяснить, что реальный ключ не хардкодится.
5. Запустить:

```bash
AGENT_DEBUG=true day-07-persistent-context-kotlin/scripts/run-eliza.sh
```

6. Написать: `Привет, меня зовут Никита. Я делаю задание 7 дня.`
7. Показать, что история сохранена в `agent-history.json`.
8. Выйти через `exit`.
9. Запустить приложение снова.
10. Спросить: `Как меня зовут и какое задание я делаю?`
11. Показать, что debug body содержит прошлую сессию.
12. Показать, что агент отвечает с учетом сохраненного контекста.
13. Коротко объяснить отличие от дня 6: теперь память живёт между запусками.

## Проверка требований

- Используется LLM API через HTTP-клиент: да.
- Это чат с историей: да.
- Это не серия независимых one-shot запросов: да.
- История сохраняется между запусками: да.
- История хранится в JSON: да.
- История загружается при старте: да.
- Есть отдельная сущность `Agent`: да.
- Есть `/clear`: да.
- API-ключ не хардкодится: да.
