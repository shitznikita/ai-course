# День 8: Работа с токенами

CLI-агент, который хранит историю сообщений, отправляет ее в LLM API и показывает, как растут токены и стоимость по мере диалога.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI
- API: Eliza API через OpenRouter-compatible endpoint
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- Модель по умолчанию: `meta-llama/llama-3.3-70b-instruct`
- HTTP-клиент: `java.net.http.HttpClient`
- История: JSON-файл `token-agent-history.json`
- Токены: локальная approximate-оценка + `usage` из API, если провайдер его возвращает
- Ключ: только через `.env` или переменные окружения

## Идея задания

В Chat Completions API агент сам поддерживает контекст: при каждом новом запросе он снова отправляет весь массив `messages`.

Из-за этого:

- короткий диалог дешевый;
- длинный диалог постепенно дорожает;
- при приближении к лимиту контекстного окна приложение должно предупредить пользователя;
- при превышении лимита запрос либо упадет в API, либо приложение должно остановить/обрезать историю.

В этом проекте переполнение показывается безопасно через искусственный лимит `APP_CONTEXT_LIMIT_TOKENS`. Это дешевле и понятнее, чем специально забивать реальное окно модели.

## План

1. Взять агента с сохранением истории из дня 7.
2. Добавить локальный подсчет токенов для текущего сообщения, всей истории и ответа.
3. Разбирать `usage.prompt_tokens`, `usage.completion_tokens`, `usage.total_tokens`, `usage.cost`, если API их вернул.
4. Добавить расчет стоимости по формуле:

```text
input_cost = prompt_tokens / 1_000_000 * input_price
output_cost = completion_tokens / 1_000_000 * output_price
total_cost = input_cost + output_cost
```

5. Добавить предупреждения при приближении к лимиту.
6. Добавить demo-режимы: короткий диалог, длинный диалог, overflow demo.
7. Добавить безопасный `file-dry-run` для большого локального файла без отправки в API.

## Чек-лист

- [ ] Есть отдельная сущность `Agent`.
- [ ] `Agent` хранит `messages`.
- [ ] История загружается и сохраняется в JSON.
- [ ] Считаются токены текущего сообщения.
- [ ] Считаются токены всей истории перед запросом.
- [ ] Считаются токены ответа.
- [ ] Печатается `usage` из API, если он есть.
- [ ] Оценивается стоимость запроса.
- [ ] Есть таблица замеров.
- [ ] Есть короткий диалог.
- [ ] Есть длинный диалог.
- [ ] Есть безопасная демонстрация переполнения контекста.
- [ ] Большой локальный файл не коммитится и не отправляется без явного подтверждения.
- [ ] API-ключ не хардкодится.

## Настройка API-ключа

Скрипт может переиспользовать `.env` из дня 1. Если нужен отдельный `.env`:

```bash
cp day-08-token-accounting-kotlin/.env.example day-08-token-accounting-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
AGENT_HISTORY_FILE=token-agent-history.json
```

Файл `.env` нельзя коммитить.

## Настройка лимитов и стоимости

В `.env` или перед командой можно задать:

```dotenv
MODEL_CONTEXT_WINDOW_TOKENS=131072
APP_CONTEXT_LIMIT_TOKENS=3000
MODEL_INPUT_PRICE_PER_1M_TOKENS=
MODEL_OUTPUT_PRICE_PER_1M_TOKENS=
```

`MODEL_CONTEXT_WINDOW_TOKENS` — реальный или справочный лимит модели.

`APP_CONTEXT_LIMIT_TOKENS` — искусственный безопасный лимит приложения. Именно он используется для overflow demo, чтобы не тратить деньги на реальное переполнение большой модели.

Если цены не заданы, стоимость выводится как `unknown`. Если API возвращает `usage.cost`, программа печатает его как основной факт от провайдера.

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza еще не создан:

```bash
day-08-token-accounting-kotlin/scripts/setup-yandex-ca.sh
```

Сборка:

```bash
./gradlew :day-08-token-accounting-kotlin:build
```

Интерактивный агент:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh
```

Короткий диалог:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="short"
```

Длинный диалог:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="long"
```

Безопасная демонстрация переполнения:

```bash
APP_CONTEXT_LIMIT_TOKENS=800 day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="overflow"
```

Dry-run для большого файла без API-запроса:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-dry-run"
```

Этот режим не требует API-ключа, если запускать его напрямую через Gradle, потому что он не делает сетевой запрос.

По умолчанию проверяется файл:

```text
/Users/shitznikita/Downloads/skills-all.md
```

Можно указать другой:

```bash
BIG_CONTEXT_FILE=/path/to/big-file.md day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-dry-run"
```

Если macOS не дает Java-процессу читать файл из `Downloads`, программа выведет `Operation not permitted` и не отправит API-запрос. В таком случае можно дать доступ терминалу в System Settings или временно скопировать файл в доступную папку и указать путь через `BIG_CONTEXT_FILE`.

Практичный вариант для записи видео:

```bash
cp /Users/shitznikita/Downloads/skills-all.md \
  /Users/shitznikita/Documents/Projects/shitz-projects/ai-course/day-08-token-accounting-kotlin/skills-all.local.md
```

Файл `*.local.md` в папке дня 8 добавлен в `.gitignore`, поэтому он не попадет в репозиторий.

## Реальная отправка большого файла

Реальная отправка большого файла отключена по умолчанию. Программа сначала считает токены и примерную стоимость, а затем блокирует запрос.

Чтобы отправить файл намеренно, нужно явно задать:

```bash
CONFIRM_BIG_CONTEXT_SEND=YES_I_UNDERSTAND_THE_COST \
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-send"
```

Это сделано специально, чтобы случайно не отправить файл примерно на сотни тысяч или миллион токенов.

Для реальной проверки реакции API на переполнение нужно дополнительно поднять искусственный лимит приложения выше размера файла, иначе приложение остановит запрос само:

```bash
APP_CONTEXT_LIMIT_TOKENS=2000000 \
CONFIRM_BIG_CONTEXT_SEND=YES_I_UNDERSTAND_THE_COST \
BIG_CONTEXT_FILE=/Users/shitznikita/Documents/Projects/shitz-projects/ai-course/day-08-token-accounting-kotlin/skills-all.local.md \
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-send"
```

`file-send` добавляет контрольный маркер в начало и в конец большого контекста. Если API не вернет ошибку, по ответу можно проверить, видит ли модель оба маркера. Если виден только конец, значит провайдер или роутер мог обрезать начало контекста. Если API вернет ошибку context length или payload limit, это тоже корректная демонстрация реального переполнения.

Сам файл не коммитится в репозиторий и не печатается в консоль целиком. В консоль выводятся только путь, размер, примерное число токенов и оценка стоимости.

## Пример вывода

```text
=== USER MESSAGE ===
Привет! Меня зовут Никита.

=== TOKEN STATS BEFORE REQUEST ===
Current message tokens: 9
History tokens before request: 35
Estimated request tokens: 35
Context limit: 3000
Limit usage: 1.2%

=== MODEL RESPONSE ===
Привет, Никита! Запомнил.

=== TOKEN STATS AFTER RESPONSE ===
Response tokens: 7
History tokens after response: 48
API prompt tokens: 31
API completion tokens: 9
API total tokens: 40
Estimated/API cost: $0.000001

=== MEASUREMENT TABLE ===
| Step | User tokens | History before | Response tokens | History after | API total | Cost | Limit % |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 9 | 35 | 7 | 48 | 40 | $0.000001 | 1.2% |
```

## Почему max_tokens не равен контекстному окну

`max_tokens` ограничивает длину ответа модели. Контекстное окно ограничивает общий размер входа и выхода: system prompt, всю историю `messages`, текущий user prompt и будущий ответ.

Если история стала слишком большой, уменьшение `max_tokens` может помочь оставить место под ответ, но не решает проблему слишком большого входного контекста.

## Что ломается при переполнении

В реальном API возможны разные варианты:

- API возвращает ошибку про context length;
- агрегатор автоматически обрезает старые сообщения;
- приложение само останавливает запрос;
- приложение применяет sliding window и удаляет старые сообщения;
- приложение суммаризирует старый контекст.

В этом задании выбран самый наглядный и безопасный вариант: приложение останавливает запрос и печатает ошибку:

```text
Context limit exceeded: current request has X tokens, app limit is Y. Request was not sent to API.
```

## Сценарий видео

1. Показать папку `day-08-token-accounting-kotlin`.
2. Открыть `Main.kt` и показать `Agent`.
3. Показать `ApproximateTokenCounter`.
4. Показать `ModelConfig`: модель, лимит контекста, цены input/output.
5. Показать `.env.example` и объяснить, что ключ не хардкодится.
6. Запустить короткий диалог:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="short"
```

7. Показать, что токенов мало.
8. Запустить длинный диалог:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="long"
```

9. Показать в таблице, что `History before` растет.
10. Запустить overflow demo:

```bash
APP_CONTEXT_LIMIT_TOKENS=800 day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="overflow"
```

11. Показать, что приложение остановило запрос до API.
12. Запустить dry-run большого файла:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-dry-run"
```

13. Показать путь, размер, примерное число токенов и предупреждение без отправки файла.
14. Коротко объяснить: в Chat Completions API каждый запрос снова отправляет всю историю, поэтому длинный диалог дороже и ближе к лимиту.

## Проверка требований

- Используется LLM API через HTTP-клиент: да.
- Не используется готовый агент или LLM SDK: да.
- `Agent` хранит историю сообщений: да.
- История отправляется в API как `messages`: да.
- Считаются токены текущего запроса, истории и ответа: да.
- Используется `usage`, если API его возвращает: да.
- Есть оценка стоимости: да.
- Есть короткий и длинный диалог: да.
- Есть демонстрация переполнения: да, через безопасный искусственный лимит.
- Большой файл не отправляется без явного подтверждения: да.
- `max_tokens` не путается с context window: да, это отдельно описано.
