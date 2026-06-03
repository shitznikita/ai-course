# День 1: REST-запрос к LLM API на Kotlin

Минимальный Kotlin CLI, который отправляет REST-запрос к LLM API, получает ответ и выводит его в консоль.

В проекте не используются AI-агенты, Codex, LangChain, OpenAI SDK или другие высокоуровневые LLM-обертки. Запрос отправляется обычным HTTP-клиентом `java.net.http.HttpClient`.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI
- HTTP: `java.net.http.HttpClient`
- Формат API: OpenAI-compatible `POST /chat/completions`
- Ключ: только через `.env` или переменные окружения

По умолчанию пример настроен на Eliza API:

- API URL: `https://api.eliza.yandex.net/raw/openai/v1/chat/completions`
- Auth scheme: `OAuth`
- Model: `gpt-5-mini`

Альтернатива для прямого DeepSeek API указана в `.env.example`.

## План выполнения задания

1. Выбрать минимальный интерфейс: CLI.
2. Выбрать API и модель: Eliza через корпоративную квоту или прямой DeepSeek.
3. Получить API-ключ и положить его в `.env`.
4. Собрать JSON-запрос с `model` и `messages`.
5. Отправить `POST` через обычный HTTP-клиент.
6. Распарсить `choices[0].message.content`.
7. Вывести статус, модель, промпт и ответ в консоль.
8. Записать короткое видео запуска.
9. Залить код на GitHub без `.env`.

## Чек-лист перед сдачей

- [ ] В `.env` указан настоящий API-ключ.
- [ ] В `.env` явно указана модель в `LLM_MODEL`.
- [ ] Команда `day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"` возвращает `HTTP status: 200`.
- [ ] В консоли виден текст ответа модели.
- [ ] В видео видно код REST-запроса и успешный запуск.
- [ ] `.env` не попал в GitHub.
- [ ] В описании сдачи указано, какая LLM использована.

## Выбор API

Для этой сдачи самый простой вариант — Eliza, потому что API-ключ уже есть и можно использовать корпоративную месячную квоту. Важно честно указать, что это корпоративная REST-точка входа к модели, а модель задана явно через `LLM_MODEL`.

Если проверяющий будет требовать прямой доступ к провайдеру без корпоративного прокси, переключи `.env` на DeepSeek. Код менять не нужно.

GigaChat тоже подходит, но у него отдельный процесс получения access token, поэтому для первого дня он менее минимальный. Hugging Face и OpenRouter удобны, но это агрегаторы/роутеры; для задания их лучше использовать только если явно указана конкретная бесплатная модель.

## Как получить API-ключ

### Вариант 1: Eliza

1. Получить OAuth-токен для Eliza по внутренней инструкции компании.
2. Проверить, что есть доступ к API и месячная API-квота.
3. Использовать токен в `.env` как `LLM_API_KEY`.

### Вариант 2: DeepSeek напрямую

1. Зарегистрироваться на платформе DeepSeek.
2. Создать API-ключ в разделе API keys.
3. Пополнить баланс, если требуется.
4. В `.env` указать URL, модель и `Bearer`-авторизацию из примера ниже.

## Настройка

Скопируй пример:

```bash
cp day-01-llm-rest-kotlin/.env.example day-01-llm-rest-kotlin/.env
```

Для Eliza:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/raw/openai/v1/chat/completions
LLM_MODEL=gpt-5-mini
```

Для прямого DeepSeek:

```dotenv
LLM_API_KEY=your_deepseek_api_key_here
LLM_AUTH_SCHEME=Bearer
LLM_API_URL=https://api.deepseek.com/chat/completions
LLM_MODEL=deepseek-v4-flash
```

Файл `.env` добавлен в `.gitignore`, его нельзя коммитить.

## Запуск

Команды ниже запускаются из корня репозитория.

Обычный запуск через Gradle Wrapper:

```bash
./gradlew :day-01-llm-rest-kotlin:run
```

Можно передать свой промпт:

```bash
./gradlew :day-01-llm-rest-kotlin:run --args="Ответь кратко: зачем нужен REST API?"
```

Если wrapper недоступен, но Gradle установлен:

```bash
gradle :day-01-llm-rest-kotlin:run
```

Также проект можно открыть в IntelliJ IDEA и запустить `Main.kt`.

## Запуск через Eliza

Для `api.eliza.yandex.net` Java может не знать корпоративный Yandex CA. В таком случае появится ошибка:

```text
SSLHandshakeException
PKIX path building failed
unable to find valid certification path to requested target
```

Создай локальный truststore:

```bash
day-01-llm-rest-kotlin/scripts/setup-yandex-ca.sh
```

После этого запускай Eliza-запрос через специальный скрипт:

```bash
day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"
```

Скрипт не отключает SSL-проверку. Он скачивает Yandex CA bundle и передает Java локальный truststore через `JAVA_TOOL_OPTIONS`.

## Пример ожидаемого вывода

```text
HTTP status: 200
Model: gpt-5-mini
Prompt: Ответь кратко: что такое REST API?
Answer:
REST API — архитектурный стиль для веб-сервисов, где данные представлены как ресурсы с URI и к ним обращаются стандартными HTTP-методами.
```

## Что показать в видео

1. Открыть `Main.kt` и показать, что запрос собирается вручную: URL, headers, JSON body, `HttpClient.send`.
2. Открыть `.env`, не показывая сам ключ полностью.
3. Запустить `day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"`.
4. Показать в консоли `HTTP status: 200` и текст ответа модели.
5. Коротко сказать: "Это Kotlin CLI, обычный REST-запрос к LLM API, модель указана явно, ключ хранится в `.env`".

## Проверка требований

- REST-запрос к модели: да, `POST` через `HttpClient`.
- Ответ получается и выводится в консоль: да.
- CLI/Web/бот: CLI.
- Готовые агенты не используются: да.
- Высокоуровневые LLM SDK не используются: да.
- Модель указана явно: да, через `LLM_MODEL`.
- API-ключ не хардкодится: да, берется из `.env` или переменных окружения.
