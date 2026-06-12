# День 10: Управление контекстом разными стратегиями

CLI-агент на Kotlin, который показывает три стратегии управления контекстом без summary:

- `sliding`: Sliding Window;
- `facts`: Sticky Facts / Key-Value Memory;
- `branching`: ветки диалога от checkpoint.

Код специально разделен на небольшие файлы: `ContextAgent`, `ContextStrategy`, `LlmClient`, `FactMemoryUpdater`, `DemoRunner`, `InteractiveCli`.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI
- API: Eliza API через OpenRouter-compatible endpoint
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- Модель по умолчанию: `meta-llama/llama-3.3-70b-instruct`
- HTTP-клиент: `java.net.http.HttpClient`
- Хранение интерактивного состояния: JSON-файл `context-state.json`
- Токены: локальная approximate-оценка + `usage` из API, если провайдер его возвращает
- Ключ: только через `.env` или переменные окружения

## Архитектура

Основные файлы:

- `Main.kt` — точка входа, выбор режима `demo` или `interactive`.
- `ContextAgent.kt` — агент, который хранит состояние и вызывает выбранную стратегию.
- `ContextStrategies.kt` — общий интерфейс `ContextStrategy` и три реализации.
- `FactMemoryUpdater.kt` — обновление key-value facts через LLM или fallback-эвристику.
- `LlmClient.kt` — прямой REST-запрос в LLM API.
- `DemoScenario.kt` — тестовый сценарий сбора ТЗ.
- `DemoRunner.kt` — автоматический прогон стратегий и таблица сравнения.
- `InteractiveCli.kt` — простой CLI с переключателем стратегий и ветками.

## Стратегии

### Sliding Window

Локальная история может быть длинной, но в LLM-запрос отправляются только последние `N` сообщений.

В выводе видно:

```text
Strategy: sliding
Recent/messages sent: 6
Dropped messages: 7
Prompt tokens: 850
```

Плюс: просто и дешево.

Минус: старые детали могут исчезнуть из текущего REST-запроса.

### Sticky Facts / Key-Value Memory

Агент хранит отдельный объект `facts`:

```json
{
  "userName": "Никита",
  "goal": "мобильное приложение для учета личных финансов",
  "constraints": "первый релиз без backend",
  "preferences": "очень простой интерфейс"
}
```

После каждого сообщения пользователя facts обновляются через LLM-промпт:

```text
Обнови key-value facts на основе нового сообщения пользователя.
Сохрани только устойчивые важные факты: цель, ограничения, предпочтения, решения, договоренности.
Не добавляй догадки. Верни только JSON.
```

В основной LLM-запрос отправляется:

- system prompt;
- facts в формате key-value;
- последние `N` сообщений.

Facts — это не summary: они не пересказывают весь диалог, а хранят только устойчивые поля.

### Branching

Агент умеет:

- сохранить checkpoint;
- создать две ветки;
- продолжать каждую ветку независимо;
- переключаться между ветками.

В demo:

- общий checkpoint создается после первых 5 сообщений ТЗ;
- `branch_a` продолжает вариант простого MVP;
- `branch_b` продолжает вариант продвинутой версии с аналитикой;
- финальные ответы сравниваются на изоляцию веток.

## Настройка API-ключа

Скрипт может переиспользовать `.env` из дня 1. Если нужен отдельный `.env`:

```bash
cp day-10-context-strategies-kotlin/.env.example day-10-context-strategies-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
RECENT_MESSAGES_LIMIT=6
FACTS_UPDATE_MODE=llm
```

Файл `.env` нельзя коммитить.

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza еще не создан:

```bash
day-10-context-strategies-kotlin/scripts/setup-yandex-ca.sh
```

Сборка:

```bash
./gradlew :day-10-context-strategies-kotlin:build
```

Автоматический demo-сценарий:

```bash
day-10-context-strategies-kotlin/scripts/run-eliza.sh
```

То же самое явно:

```bash
day-10-context-strategies-kotlin/scripts/run-eliza.sh --args="demo"
```

Интерактивный режим:

```bash
day-10-context-strategies-kotlin/scripts/run-eliza.sh --args="interactive"
```

## CLI-команды

```text
/strategy sliding
/strategy facts
/strategy branching
/checkpoint
/branch create branch_a
/branch create branch_b
/branch switch branch_a
/branch switch branch_b
/branches
/status
/clear
/exit
```

## Demo-сценарий

Автоматический сценарий собирает ТЗ для мобильного приложения:

1. Меня зовут Никита.
2. Я хочу сделать мобильное приложение для учета личных финансов.
3. Целевая аудитория — люди, которые плохо контролируют расходы.
4. Первый релиз должен быть без backend.
5. Нужно добавить категории расходов.
6. Нужно добавить лимиты по категориям.
7. Важно, чтобы интерфейс был очень простым.
8. Нужен экспорт в CSV.
9. Пуши пока не нужны.
10. Монетизация — подписка позже, не в MVP.
11. Срок MVP — 3 недели.
12. Главный риск — слишком сложный UX.
13. Сформируй итоговое ТЗ для MVP.

Для `branching` первые 5 сообщений становятся checkpoint, потом создаются две ветки:

- `branch_a`: простой MVP за 3 недели;
- `branch_b`: продвинутая версия с аналитикой.

## Как читать сравнение

В конце печатается таблица:

```text
| Strategy | Prompt tokens | Important facts kept | Important facts lost | Answer quality | Stability | User convenience |
```

Смысл колонок:

- `Prompt tokens` — сколько токенов ушло на контекст стратегии.
- `Important facts kept` — какие ключевые факты нашлись в финальном ответе.
- `Important facts lost` — какие факты не проявились в ответе.
- `Answer quality` — простая оценка по числу сохраненных фактов.
- `Stability` — насколько стратегия держит важные детали.
- `User convenience` — насколько удобно пользоваться стратегией.

## Фактический результат проверки

Последний demo-прогон показал:

```text
| Strategy | Prompt tokens | Important facts kept | Important facts lost | Answer quality | Stability | User convenience |
|---|---:|---|---|---|---|---|
| sliding | 160 | audience; simple UI; CSV; no pushes; subscription later; 3 weeks; UX risk | name; finance app; no backend; categories; limits | 7/12 | medium | easy |
| facts | 4207 | name; finance app; audience; no backend; categories; limits; simple UI; CSV; no pushes; subscription later; 3 weeks; UX risk | none | 12/12 | high | medium |
| branching | 493 | branches isolated=true | n/a | good | high | harder, but good for alternatives |
```

Вывод для видео: `sliding` дешевый и простой, но потерял ранние детали ТЗ; `facts` дороже из-за обновления key-value памяти через LLM, зато сохранил все важные факты; `branching` удержал две независимые ветки продукта.

## Сценарий видео

1. Показать папку `day-10-context-strategies-kotlin`.
2. Показать, что код разделен по файлам: `ContextAgent`, `ContextStrategies`, `FactMemoryUpdater`, `LlmClient`.
3. Показать `.env.example` и объяснить, что API-ключ не хардкодится.
4. Показать `ContextStrategy` и три реализации.
5. Запустить:

```bash
day-10-context-strategies-kotlin/scripts/run-eliza.sh
```

6. Показать блок `SLIDING`: последние сообщения отправлены, старые отброшены.
7. Показать блок `FACTS`: facts накопились и попали в запрос.
8. Показать блок `BRANCHING`: branch A и branch B дали разные ответы.
9. Показать таблицу сравнения.
10. Коротко сказать вывод:
    - Sliding Window простая и дешевая, но теряет старые детали.
    - Facts держит важные устойчивые данные, но может ошибиться при извлечении.
    - Branching удобен для альтернативных сценариев, но сложнее в управлении.

## Проверка требований

- Используется LLM API через HTTP-клиент: да.
- Не используется готовый агент или LLM SDK: да.
- API-ключ не хардкодится: да.
- `Agent` вынесен в отдельную сущность: да.
- Есть общий интерфейс `ContextStrategy`: да.
- Есть Sliding Window: да.
- Есть Sticky Facts / Key-Value Memory: да.
- Есть Branching: да.
- Есть переключатель стратегий в CLI: да.
- Считаются токены контекста: да.
- Есть demo-сценарий на одном сценарии ТЗ: да.
- Есть таблица сравнения: да.
- Summary как основная стратегия не используется: да.
