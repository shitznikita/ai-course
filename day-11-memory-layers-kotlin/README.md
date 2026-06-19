# День 11: Модель памяти ассистента

CLI-агент на Kotlin, который показывает явную модель памяти из трех слоев:

- short-term memory: сообщения текущего чата;
- working memory: контекст текущей задачи;
- long-term memory: профиль пользователя и глобальные правила.

Домен агента: помощник по сбору ТЗ для мобильного приложения.

## Что используется

- Язык: Kotlin
- Интерфейс: CLI
- API: Eliza API через OpenRouter-compatible endpoint
- Endpoint: `https://api.eliza.yandex.net/openrouter/v1/chat/completions`
- Модель по умолчанию: `meta-llama/llama-3.3-70b-instruct`
- HTTP-клиент: `java.net.http.HttpClient`
- Хранение памяти: отдельные JSON-файлы
- Ключ: только через `.env` или переменные окружения

## Архитектура

Основные файлы:

- `Main.kt` — точка входа, выбор режима `demo` или `interactive`.
- `MemoryAgent.kt` — агент, который собирает prompt и вызывает LLM.
- `MemoryManager.kt` — единая точка управления всеми слоями памяти.
- `MemoryStores.kt` — JSON-хранилище для каждого слоя.
- `PromptBuilder.kt` — сборка prompt из выбранных слоев и audit токенов.
- `LlmClient.kt` — прямой REST-запрос в LLM API.
- `DemoRunner.kt` — автоматический сценарий для видео.
- `InteractiveCli.kt` — команды управления памятью.

## Слои памяти

Память хранится раздельно:

```text
memory/short_term/current_chat.json
memory/working/task_context.json
memory/long_term/user_profile.json
```

Эти файлы являются runtime-состоянием и не коммитятся.

### Short-term memory

Хранит последние сообщения текущего чата:

```json
{
  "messages": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ]
}
```

В prompt отправляются только последние `SHORT_TERM_MESSAGES_LIMIT` сообщений.

### Working memory

Хранит состояние текущей задачи:

```json
{
  "taskName": "MVP приложения учета финансов",
  "goal": "Собрать ТЗ для MVP",
  "status": "",
  "stage": "requirements",
  "constraints": ["первый релиз без backend"],
  "decisions": ["добавить экспорт CSV"],
  "openQuestions": ["нужна ли авторизация?"],
  "nextSteps": []
}
```

### Long-term memory

Хранит устойчивые данные, применимые во всех чатах:

```json
{
  "userName": "Никита",
  "preferences": ["краткие ответы с чек-листами"],
  "globalRules": ["API-ключи хранить только в .env"],
  "stableDecisions": [],
  "knowledge": []
}
```

## Prompt Builder

Prompt собирается в явном порядке:

1. system prompt;
2. long-term memory;
3. working memory;
4. short-term memory;
5. текущее сообщение пользователя.

Перед ответом агент печатает audit:

```text
=== PROMPT AUDIT ===
system: included=true, tokens=...
long-term: included=true, tokens=...
working: included=true, tokens=...
short-term: included=true, tokens=...
current-user-message: included=true, tokens=...
Total prompt token estimate: ...
=== END PROMPT AUDIT ===
```

## Настройка API-ключа

Скрипт может переиспользовать `.env` из дня 1. Если нужен отдельный `.env`:

```bash
cp day-11-memory-layers-kotlin/.env.example day-11-memory-layers-kotlin/.env
```

И вставь токен:

```dotenv
LLM_API_KEY=your_eliza_oauth_token_here
LLM_AUTH_SCHEME=OAuth
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

Файл `.env` нельзя коммитить.

## Запуск

Команды запускаются из корня репозитория.

Если truststore для Eliza еще не создан:

```bash
day-11-memory-layers-kotlin/scripts/setup-yandex-ca.sh
```

Сборка:

```bash
./gradlew :day-11-memory-layers-kotlin:build
```

Автоматический demo-сценарий:

```bash
day-11-memory-layers-kotlin/scripts/run-eliza.sh
```

То же самое явно:

```bash
day-11-memory-layers-kotlin/scripts/run-eliza.sh --args="demo"
```

Интерактивный режим:

```bash
day-11-memory-layers-kotlin/scripts/run-eliza.sh --args="interactive"
```

## CLI-команды

```text
/memory show
/memory show short
/memory show working
/memory show long
/memory save short <text>
/memory save working <key> <value>
/memory save long <key> <value>
/memory clear short
/memory clear working
/memory status
/exit
```

Working memory keys:

```text
task_name
goal
status
stage
constraint
decision
open_question
next_step
```

Long-term memory keys:

```text
user_name
preference
global_rule
stable_decision
knowledge
```

## Demo-сценарий

Автоматический demo показывает:

1. старт с пустой памятью;
2. явное сохранение long-term memory:
   - `user_name = Никита`;
   - `preference = краткие ответы с чек-листами`;
   - `global_rule = API-ключи хранить только в .env`;
3. явное сохранение working memory:
   - задача: MVP приложения учета финансов;
   - stage: requirements;
   - constraints: без backend, срок 3 недели;
   - decisions: категории расходов, экспорт CSV;
   - open question: нужна ли авторизация;
4. short-term notes текущего диалога;
5. `/memory show`;
6. `/memory status`;
7. финальный вопрос: `Сформируй краткое ТЗ для MVP`;
8. prompt audit, где видно влияние всех слоев памяти.

## Пример ручной проверки

```text
/memory save long user_name Никита
/memory save long preference Отвечай кратко и структурно
/memory save working task_name MVP приложения учета финансов
/memory save working constraint Без backend в первой версии
/memory save working decision Нужен экспорт CSV
/memory show
/memory status
Сформируй следующий шаг по ТЗ.
/memory clear short
/memory show short
```

## Сценарий видео

1. Показать папку `day-11-memory-layers-kotlin`.
2. Показать `.env.example` и объяснить, что ключ не хардкодится.
3. Показать `MemoryManager`, `PromptBuilder`, `MemoryAgent`.
4. Показать отдельные файлы памяти в `memory/short_term`, `memory/working`, `memory/long_term`.
5. Запустить:

```bash
day-11-memory-layers-kotlin/scripts/run-eliza.sh
```

6. Показать `/memory show`: данные лежат в разных слоях.
7. Показать `/memory status`: видно, какие слои будут подмешаны в prompt.
8. Показать `PROMPT AUDIT`: видно токены каждого слоя.
9. Показать финальный ответ, который учитывает профиль пользователя, задачу и текущий чат.
10. Коротко сказать вывод: это stateful-агент с явной memory layers моделью, без MCP, RAG и embeddings.

## Проверка требований

- Три слоя памяти есть: да.
- Слои хранятся отдельно: да.
- Пользователь явно выбирает, куда сохранять данные: да.
- Видно, какие данные попали в каждый слой: да, через `/memory show`.
- Видно, какие слои попали в prompt: да, через `/memory status` и `PROMPT AUDIT`.
- Prompt не использует RAG, MCP, embeddings или summary: да.
- Используется прямой REST через `java.net.http.HttpClient`: да.
- API-ключ не хардкодится: да.
- README содержит команды запуска и сценарий видео: да.
