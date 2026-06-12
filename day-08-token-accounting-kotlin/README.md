# День 8: Работа с токенами

CLI-агент, который хранит историю сообщений, отправляет ее в LLM API и показывает, как растут токены и стоимость по мере диалога.

## Текущий статус

- Реализация актуальна на 2026-06-12.
- Основные режимы: `short`, `long`, `overflow`, `forgetting`, `file-dry-run`, `file-send`.
- Основной вывод токенов специально упрощен под формулировку задания:
  - `Current request`;
  - `Dialog history`;
  - `Model response`.
- Реальная проверка переполнения большим файлом уже была выполнена: API вернул `HTTP 400`, потому что максимум контекста `131072` токенов, а запрос был примерно `1303283` токенов.
- Для демонстрации "как агент забывает старую информацию" добавлен режим `forgetting`: агент хранит всю историю локально, но в REST-запрос отправляет только хвост истории через `sliding-window`.
- Локальный файл `skills-all.local.md` и history/tmp-файлы не коммитятся.

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

В этом проекте есть две разные демонстрации:

- `overflow` показывает жесткое переполнение: приложение останавливает запрос или реальный API отвечает ошибкой `context length`;
- `forgetting` показывает поведение агента при скользящем окне: старая часть истории остается в локальном файле, но больше не отправляется модели.

Именно `forgetting` лучше подходит под комментарий автора курса про то, как старая часть контекста "отъезжает" и откуда берутся галлюцинации: модель отвечает только по тем `messages`, которые реально попали в текущий REST-запрос.

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
8. Добавить `forgetting` demo со стратегией `sliding-window`, где старые сообщения не отправляются в API.

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
- [ ] Есть демонстрация забывания старого контекста через `sliding-window`.
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
CONTEXT_OVERFLOW_STRATEGY=fail-fast
MODEL_INPUT_PRICE_PER_1M_TOKENS=
MODEL_OUTPUT_PRICE_PER_1M_TOKENS=
```

`MODEL_CONTEXT_WINDOW_TOKENS` — реальный или справочный лимит модели.

`APP_CONTEXT_LIMIT_TOKENS` — искусственный безопасный лимит приложения. Именно он используется для overflow demo, чтобы не тратить деньги на реальное переполнение большой модели.

`CONTEXT_OVERFLOW_STRATEGY` управляет поведением приложения при превышении лимита:

- `fail-fast` — остановить запрос до API и напечатать ошибку;
- `sliding-window` — отправить в API только последние сообщения, которые помещаются в лимит.

В режиме `forgetting` по умолчанию используется `sliding-window` и лимит `650` токенов, если `APP_CONTEXT_LIMIT_TOKENS` не задан вручную.

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

Демонстрация забывания старого контекста:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="forgetting"
```

Этот режим делает реальные API-запросы. Сначала агент просит модель запомнить секретное кодовое слово, затем добавляет фоновые сообщения, а в конце спрашивает кодовое слово. Когда старые сообщения перестают помещаться в `APP_CONTEXT_LIMIT_TOKENS`, программа печатает строку вида:

```text
Sent to API: 612 (8 old messages skipped by sliding-window)
```

Если секретное сообщение было пропущено, модель больше не видит его в текущем `messages` и может честно ответить, что не видит кодовое слово, или начать угадывать. Это и есть практическая демонстрация "забывания" старой части контекста.

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

## Фактический результат с большим файлом

Для файла `skills-all.local.md` размером `4664435` bytes локальная оценка показала:

```text
Approximate file tokens: 1177946
Approximate request tokens if sent now: 1178009
Model context window: 131072 tokens
```

При реальной отправке API вернул:

```text
HTTP status: 400
API error: This endpoint's maximum context length is 131072 tokens.
However, you requested about 1303283 tokens.
```

Вывод: в этой конфигурации модель не начинает забывать старый контекст, потому что запрос не доходит до самой модели. Провайдер/роутер отклоняет слишком большой prompt до генерации ответа. Это тоже полезная демонстрация переполнения контекстного окна.

## Пример вывода

```text
=== USER MESSAGE ===
Привет! Меня зовут Никита.

=== TOKENS ===
Current request: 9
Dialog history: 35
Model response: 7
Context limit usage: 1.2%

=== MODEL RESPONSE ===
Привет, Никита! Запомнил.

API usage: prompt=31, completion=9, total=40
Cost: $0.000001

=== MEASUREMENT TABLE ===
| Step | Current request | Dialog history | Model response | API total | Cost |
|---:|---:|---:|---:|---:|---:|
| 1 | 9 | 35 | 7 | 40 | $0.000001 |
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

В этом задании теперь показаны два варианта:

1. `overflow`: приложение останавливает запрос и печатает ошибку:

```text
Context limit exceeded: current request has X tokens, app limit is Y. Request was not sent to API.
```

2. `forgetting`: приложение применяет `sliding-window`, отправляет в API только последние сообщения и печатает, сколько старых сообщений было пропущено.

Для сдачи лучше показывать `forgetting`, потому что он ближе к смыслу задания: старый факт есть в локальной истории агента, но не попал в текущий контекст модели.

## Сценарий видео

1. Показать папку `day-08-token-accounting-kotlin`.
2. Открыть `Main.kt` и показать `Agent`.
3. Показать `ApproximateTokenCounter`.
4. Показать `ContextOverflowStrategy`: `fail-fast` и `sliding-window`.
5. Показать `ModelConfig`: модель, лимит контекста, цены input/output.
6. Показать `.env.example` и объяснить, что ключ не хардкодится.
7. Запустить короткий диалог:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="short"
```

8. Показать, что токенов мало.
9. Запустить длинный диалог:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="long"
```

10. Показать в таблице, что `Dialog history` растет.
11. Запустить `forgetting` demo:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="forgetting"
```

12. Показать строку `Sent to API: ... old messages skipped by sliding-window`.
13. Показать финальный ответ модели на вопрос про первое кодовое слово.
14. Объяснить: модель не "помнит" то, что не попало в текущий REST-запрос.
15. При желании показать hard-limit overflow:

```bash
APP_CONTEXT_LIMIT_TOKENS=800 day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="overflow"
```

16. Показать, что приложение остановило запрос до API.
17. Запустить dry-run большого файла:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-dry-run"
```

18. Показать путь, размер, примерное число токенов и предупреждение без отправки файла.
19. Коротко объяснить: в Chat Completions API каждый запрос снова отправляет историю, но если история не помещается, старая часть может быть отброшена, и модель начинает отвечать без этих фактов.

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
- Есть демонстрация забывания старой части контекста: да, через режим `forgetting` и стратегию `sliding-window`.
- Большой файл не отправляется без явного подтверждения: да.
- `max_tokens` не путается с context window: да, это отдельно описано.
