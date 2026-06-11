# День 9: Управление контекстом — сжатие истории

CLI-агент, который умеет заменять старую часть истории диалога на `summary`, хранить последние N сообщений как есть и сравнивать полный контекст со сжатым.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI
- API: Eliza API через OpenRouter-compatible endpoint
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- Модель по умолчанию: `meta-llama/llama-3.3-70b-instruct`
- HTTP-клиент: `java.net.http.HttpClient`
- История последних сообщений: JSON-файл `recent-messages.json`
- Summary старой истории: отдельный файл `context-summary.md`
- Ключ: только через `.env` или переменные окружения

## Идея

В обычном чат-агенте каждый новый запрос отправляет всю историю `messages`. Когда диалог растет, растут токены, стоимость и риск упереться в контекстное окно.

В этом задании агент делает так:

1. Последние `N` сообщений хранит полностью.
2. Старую часть истории отправляет в LLM с просьбой сделать summary.
3. Summary сохраняет отдельно.
4. В следующий запрос отправляет:

```text
system prompt
summary старой истории
последние N сообщений как есть
новый user message
```

## План

1. Взять агента из прошлых дней.
2. Добавить настройку `RECENT_MESSAGES_LIMIT`.
3. Хранить recent messages отдельно от summary.
4. Реализовать LLM-запрос для создания summary.
5. Добавить режим сравнения `full_history` vs `compressed_history`.
6. Посчитать токены полного и сжатого контекста.
7. Показать экономию токенов и качество ответов.
8. Учесть стоимость summary-запроса отдельно.

## Чек-лист

- [ ] Есть отдельная сущность `Agent`.
- [ ] `Agent` хранит messages.
- [ ] `Agent` хранит summary отдельно.
- [ ] Есть настройка `RECENT_MESSAGES_LIMIT`.
- [ ] Последние N сообщений остаются как есть.
- [ ] Старая история сжимается через LLM.
- [ ] Summary сохраняется на диск.
- [ ] В compressed-режиме отправляется summary + последние N сообщений.
- [ ] Full и compressed режимы используют один финальный вопрос.
- [ ] Считаются токены full context и compressed context.
- [ ] Показывается экономия токенов.
- [ ] Показывается стоимость summary-запроса.
- [ ] API-ключ не хардкодится.

## Настройка API-ключа

Скрипт может переиспользовать `.env` из дня 1. Если нужен отдельный `.env`:

```bash
cp day-09-history-compression-kotlin/.env.example day-09-history-compression-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
RECENT_MESSAGES_LIMIT=10
RECENT_MESSAGES_FILE=recent-messages.json
SUMMARY_FILE=context-summary.md
```

Файл `.env` нельзя коммитить.

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza еще не создан:

```bash
day-09-history-compression-kotlin/scripts/setup-yandex-ca.sh
```

Сборка:

```bash
./gradlew :day-09-history-compression-kotlin:build
```

Основной режим сравнения:

```bash
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="compare"
```

Интерактивный режим:

```bash
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="interactive"
```

Очистить сохраненные recent messages и summary:

```bash
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="clear"
```

Изменить количество последних сообщений:

```bash
RECENT_MESSAGES_LIMIT=6 day-09-history-compression-kotlin/scripts/run-eliza.sh --args="compare"
```

## Что делает compare

`compare` строит тестовую историю:

- пользователь сообщает имя: Никита;
- говорит, что проходит курс по AI-агентам;
- сообщает предпочтения: Kotlin CLI, direct REST, JSON, `.env`, GitHub PR workflow;
- затем добавляются сообщения-заполнители, чтобы история стала длинной;
- LLM делает summary старой части;
- один и тот же финальный вопрос отправляется в двух режимах.

Финальный вопрос:

```text
Как меня зовут, что я сейчас изучаю и какие мои технические предпочтения и workflow ты помнишь?
```

## Пример вывода

```text
=== SUMMARY CREATED BY LLM ===
Old messages summarized: 40
Recent messages kept as is: 10
Summary tokens: 120
Summary request tokens: 900
Summary response tokens: 130
Summary cost: $0.000100

=== CONTEXT MODE: FULL HISTORY ===
Messages sent: 52
Prompt tokens: 1400
Response:
...

=== CONTEXT MODE: COMPRESSED HISTORY ===
Summary tokens: 120
Recent messages: 10
Prompt tokens: 520
Response:
...

=== TOKEN SAVINGS ===
Full history tokens: 1400
Compressed context tokens: 520
Saved tokens: 880
Saved percent: 62.9%
Summary creation tokens: 1030

=== COST COMPARISON ===
Full history answer cost: $0.000200
Compressed answer cost: $0.000080
Summary creation cost: $0.000100
Compressed total for this demo: $0.000180

=== QUALITY COMPARISON ===
Full history answer: 7/7 expected facts detected
Compressed history answer: 6/7 expected facts detected
Lost facts: JSON history
```

## Где хранятся данные

По умолчанию:

```text
day-09-history-compression-kotlin/recent-messages.json
day-09-history-compression-kotlin/context-summary.md
```

Оба файла считаются пользовательскими данными и игнорируются Git.

## Стоимость

В compressed-подходе есть дополнительная стоимость: запрос к LLM для создания summary.

Но дальше summary может экономить токены на каждом следующем запросе, потому что вместо всей старой истории отправляется короткое summary.

Для длинных диалогов это часто выгоднее, но качество зависит от того, насколько хорошо summary сохранило важные факты.

## Ограничения

- Summary может потерять детали.
- Summary не должно полностью заменять весь диалог: последние N сообщений остаются как есть.
- Если summary плохое, агент может забыть важные факты или исказить договоренности.
- Для настоящего production-агента можно добавить более сложные стратегии: sliding window, hierarchical summaries, RAG, embeddings. В этом задании они специально не используются.

## Сценарий видео

1. Показать папку `day-09-history-compression-kotlin`.
2. Открыть `Main.kt` и показать `Agent`.
3. Показать `RECENT_MESSAGES_LIMIT`.
4. Показать, что `recent-messages.json` и `context-summary.md` хранятся отдельно.
5. Показать prompt для summary.
6. Запустить:

```bash
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="compare"
```

7. Показать созданное LLM summary.
8. Показать блок `FULL HISTORY`.
9. Показать блок `COMPRESSED HISTORY`.
10. Показать `TOKEN SAVINGS`.
11. Показать `QUALITY COMPARISON`.
12. Коротко объяснить вывод: summary экономит токены и стоимость на длинной дистанции, но может потерять детали.

## Проверка требований

- Используется LLM API через HTTP-клиент: да.
- Не используется готовый агент или LLM SDK: да.
- История хранится: да.
- Summary хранится отдельно: да.
- Последние N сообщений остаются как есть: да.
- Старая история сжимается через LLM: да.
- Full и compressed контекст сравниваются на одном вопросе: да.
- Считаются токены и экономия: да.
- Summary-запрос учитывается отдельно: да.
- API-ключ не хардкодится: да.
