# День 4: Температура

Минимальный Kotlin CLI, который отправляет один и тот же prompt в LLM API с разными значениями `temperature` и сравнивает ответы.

## Почему не `gpt-5-mini`

В предыдущем дне мы проверили, что `gpt-5-mini` через Eliza не принимает произвольный `temperature`: API вернул ошибку, что поддерживается только значение по умолчанию. Поэтому для задания дня 4 используется Eliza/OpenRouter DeepSeek endpoint, где `temperature` должен поддерживаться.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI
- API: Eliza API через OpenRouter-compatible endpoint
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- Модель: `deepseek/deepseek-v3.1-terminus`
- HTTP-клиент: `java.net.http.HttpClient`
- Ключ: только через `.env` или переменные окружения

## Prompt

```text
Придумай 5 очень необычных, но реалистичных идей для мобильного приложения для студентов. Не используй банальные решения вроде Pomodoro, flashcards, календаря или простого планировщика. Для каждой идеи дай название, очень короткое описание в 1 предложении и одну главную фишку.
```

Этот prompt подходит для сравнения, потому что в ответах можно оценить:

- точность: идеи должны соответствовать задаче и быть реалистичными;
- креативность: насколько необычные названия и механики предлагает модель;
- разнообразие: насколько идеи отличаются друг от друга.

Этот вариант заметнее показывает разницу между temperature значениями, потому что он меньше подталкивает модель к банальным учебным шаблонам.

## План

1. Взять один prompt и одну модель.
2. Отправить три REST-запроса, меняя только `temperature`:
   - `0`
   - `0.7`
   - `1.2`
3. Оставить одинаковым `max_tokens`.
4. Не менять `top_p`, `top_k`, `reasoning` и другие параметры.
5. Перед каждым ответом вывести тело REST-запроса без API-ключа.
6. Вывести три ответа и секцию `COMPARISON`, которая строится локально и не делает четвёртый temperature-запрос.

## Чек-лист

- [ ] Один prompt во всех трех запросах.
- [ ] Одна модель во всех трех запросах.
- [ ] Есть `temperature = 0`.
- [ ] Есть `temperature = 0.7`.
- [ ] Есть `temperature = 1.2`.
- [ ] Остальные параметры одинаковые.
- [ ] Тело REST-запроса выводится без API-ключа.
- [ ] API-ключ не хранится в коде.
- [ ] Есть локальное сравнение по точности, креативности и разнообразию.

## Настройка API-ключа

Можно использовать `.env` из дня 1. Скрипт `run-eliza.sh` сам подхватит `day-01-llm-rest-kotlin/.env`, но переопределит URL и модель для дня 4.

Если нужен отдельный `.env` для дня 4:

```bash
cp day-04-temperature-kotlin/.env.example day-04-temperature-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=deepseek/deepseek-v3.1-terminus
```

Файл `.env` нельзя коммитить.

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza еще не создан:

```bash
day-04-temperature-kotlin/scripts/setup-yandex-ca.sh
```

Если truststore уже есть в `day-01-llm-rest-kotlin/.certs/`, `run-eliza.sh` подхватит его автоматически.

Запуск:

```bash
day-04-temperature-kotlin/scripts/run-eliza.sh
```

Можно передать свой prompt:

```bash
day-04-temperature-kotlin/scripts/run-eliza.sh --args="Придумай 5 очень необычных идей для приложения для студентов, без банальных шаблонов"
```

Сборка:

```bash
./gradlew :day-04-temperature-kotlin:build
```

## Пример ожидаемого вывода

```text
=== REST REQUEST BODY, TEMPERATURE 0 ===
{
  "model": "...",
  "temperature": 0,
  "max_tokens": 800,
  "messages": [...]
}

=== TEMPERATURE 0 ===
<ответ>

=== TEMPERATURE 0.7 ===
<ответ>

=== TEMPERATURE 1.2 ===
<ответ>

=== COMPARISON ===
Prompt:
...

Observed metrics:
- temperature 0: ...
- temperature 0.7: ...
- temperature 1.2: ...

How to read the difference:
- `0` is usually best when accuracy and reproducibility matter most.
- `0.7` is usually the best balance between accuracy and creativity.
- `1.2` is usually best for brainstorming, alternative ideas, and more varied wording.

Short conclusion:
Inspect the three answers above: higher temperature should usually add more variation, while lower temperature should stay closer to the prompt.
```

## Сценарий видео

1. Открыть `Main.kt` и показать, что один prompt отправляется три раза.
2. Показать, что меняется только `temperature`.
3. Показать, что API-ключ берется из `.env`, а не хранится в коде.
4. Запустить:

```bash
day-04-temperature-kotlin/scripts/run-eliza.sh
```

5. Показать REST request body без API-ключа.
6. Показать ответы для `0`, `0.7`, `1.2`.
7. Показать `COMPARISON` и коротко сказать, для каких задач подходит каждое значение.

## Проверка требований

- Используется LLM API: да.
- Готовый интерфейс модели не используется: да.
- Высокоуровневый LLM SDK не используется: да.
- API-ключ не хардкодится: да.
- Один prompt во всех трех запросах: да.
- Одна модель во всех трех запросах: да.
- Меняется только `temperature`: да, для трех основных запросов.
- REST body выводится без ключа: да.
