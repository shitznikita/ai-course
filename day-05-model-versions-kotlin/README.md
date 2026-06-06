# День 5: Версии моделей

Kotlin CLI, который отправляет один и тот же prompt в три разные облачные LLM-модели через один REST API, замеряет время ответа, токены и стоимость, а затем выводит короткое сравнение.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI
- API: Eliza API через OpenRouter-compatible endpoint
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- HTTP-клиент: `java.net.http.HttpClient`
- Ключ: только через `.env` или переменные окружения

## Модели

| Class | Model | Size | Price | Link | Why |
|---|---|---:|---:|---|---|
| Weak | `meta-llama/llama-3.2-3b-instruct` | 3B | $0.0509/M input, $0.335/M output | [OpenRouter](https://openrouter.ai/meta-llama/llama-3.2-3b-instruct) | Маленькая модель: дешёвая, но может терять смысл задачи. |
| Medium | `meta-llama/llama-3.3-70b-instruct` | 70B | $0.10/M input, $0.32/M output | [OpenRouter](https://openrouter.ai/meta-llama/llama-3.3-70b-instruct) | Средний класс: лучше структура, смысл и рассуждение. |
| Strong | `nousresearch/hermes-3-llama-3.1-405b` | 405B | $1/M input, $1/M output | [OpenRouter](https://openrouter.ai/nousresearch/hermes-3-llama-3.1-405b) | Сильная модель 400B+: богаче анализ, но дороже. |

Цены взяты из Eliza/OpenRouter model catalog. В ответе API также приходит `usage.cost`, поэтому код показывает фактическую стоимость запроса, если API её вернул.

## Prompt

```text
Проанализируй идею мобильного приложения для учета личных финансов. Дай 5 ключевых функций, 3 риска реализации и короткий план MVP. Ответь структурировано и кратко.
```

Prompt выбран так, чтобы было видно:

- качество понимания бизнес-задачи;
- полноту и структуру ответа;
- реалистичность рисков;
- качество MVP-плана.

## План

1. Взять один prompt.
2. Отправить его в слабую, среднюю и сильную модель.
3. Зафиксировать одинаковые параметры: `temperature = 0.2`, `max_tokens = 700`.
4. Для каждой модели измерить start time, end time и duration seconds.
5. Достать `prompt_tokens`, `completion_tokens`, `total_tokens` и `cost` из `usage`.
6. Если usage не пришёл, вывести `not provided by API`.
7. Вывести ответы и секцию `COMPARISON`.

## Чек-лист

- [ ] Один prompt для всех моделей.
- [ ] Три модели: слабая, средняя, сильная.
- [ ] Все запросы идут через REST API.
- [ ] API-ключ не хардкодится.
- [ ] Время ответа измеряется.
- [ ] Токены выводятся из `usage`, если API их вернул.
- [ ] Стоимость выводится из `usage.cost` или считается по известной цене.
- [ ] Есть короткое сравнение качества, скорости и ресурсоёмкости.

## Настройка API-ключа

Можно использовать `.env` из дня 1. Скрипт `run-eliza.sh` сам подхватит `day-01-llm-rest-kotlin/.env`.

Если нужен отдельный `.env` для дня 5:

```bash
cp day-05-model-versions-kotlin/.env.example day-05-model-versions-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
```

Файл `.env` нельзя коммитить.

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza ещё не создан:

```bash
day-05-model-versions-kotlin/scripts/setup-yandex-ca.sh
```

Если truststore уже есть в `day-01-llm-rest-kotlin/.certs/`, `run-eliza.sh` подхватит его автоматически.

Сборка:

```bash
./gradlew :day-05-model-versions-kotlin:build
```

Запуск:

```bash
day-05-model-versions-kotlin/scripts/run-eliza.sh
```

Можно передать свой prompt:

```bash
day-05-model-versions-kotlin/scripts/run-eliza.sh --args="Проанализируй сервис доставки еды для студентов: функции, риски, MVP"
```

## Пример ожидаемого вывода

```text
=== MODEL 1: WEAK ===
Model: Meta: Llama 3.2 3B Instruct
Duration: 1.23 s
Tokens: prompt=..., completion=..., total=...
Estimated cost: $...
Answer:
...

=== MODEL 2: MEDIUM ===
...

=== MODEL 3: STRONG ===
...

=== COMPARISON ===
Quality:
...

Speed:
...

Resource usage:
...

Conclusion:
...
```

## Таблица результатов

Пример фактического прогона:

| Model | Class | Time | Prompt tokens | Completion tokens | Total tokens | Estimated cost | Quality notes |
|---|---|---:|---:|---:|---:|---:|---|
| Llama 3.2 3B Instruct | Weak | 13.67 s | 89 | 654 | 743 | $0.00022362 | Длинный ответ, заметно хуже языковая чистота: встречались English words внутри русского текста. |
| Llama 3.3 70B Instruct | Medium | 9.47 s | 64 | 287 | 351 | $0.00009824 | Самый быстрый и дешёвый в этом прогоне, ответ компактный и достаточно структурный. |
| Hermes 3 405B Instruct | Strong | 13.48 s | 64 | 281 | 345 | $0.00034500 | Чистый и сжатый ответ, но стоимость выше из-за цены за токен. |

Цифры могут немного меняться от запуска к запуску: OpenRouter маршрутизирует запросы через доступных провайдеров, а модели могут генерировать разную длину ответа.

## Сценарий видео

1. Открыть `Main.kt` и показать список трёх моделей.
2. Показать, что используется один prompt и одинаковые параметры `temperature`/`max_tokens`.
3. Показать, что API-ключ берётся из `.env`, а не хранится в коде.
4. Запустить:

```bash
day-05-model-versions-kotlin/scripts/run-eliza.sh
```

5. Показать ответы слабой, средней и сильной модели.
6. Показать duration, tokens и estimated cost.
7. Показать `COMPARISON`.
8. Коротко сказать вывод: маленькая модель дешевле, но менее надёжна; средняя даёт хороший баланс; сильная обычно лучше анализирует, но дороже.

## Проверка требований

- Используется LLM API: да.
- Локальные модели не используются: да.
- Готовый веб-интерфейс модели не используется: да.
- API-ключ не хардкодится: да.
- Один prompt для всех моделей: да.
- Есть слабая, средняя и сильная модель: да.
- Есть время ответа: да.
- Есть токены из API: да, если `usage` пришёл.
- Есть стоимость: да, из `usage.cost` или расчёт по цене модели.
- Есть ссылки на модели: да.
