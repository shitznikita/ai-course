# Day 14: Инварианты и ограничения состояния

## Цель

День 14 добавляет инварианты: постоянные ограничения, которые ассистент не имеет права нарушать. Инварианты хранятся отдельно от диалога, явно добавляются в prompt и проверяются до и после ответа модели.

Стек: Kotlin CLI, Gradle, `kotlinx.serialization`, прямой REST через `java.net.http.HttpClient`, Eliza/OpenRouter-compatible endpoint.

## Инварианты

Default set:

- Clean Architecture.
- Только Kotlin, не Java.
- Только Jetpack Compose, не XML layout.
- MVP без backend и cloud sync.
- Локальное хранение через Room.
- API-ключи только через `.env` или env vars.
- Без мата и токсичности.

Runtime-файл:

```text
memory/invariants.json
```

Он ignored. Пример defaults лежит в `examples/invariants.default.json`.

## Архитектура

- `InvariantStore` загружает, сохраняет, включает и выключает инварианты.
- `InvariantChecker` делает deterministic проверку по keywords/patterns.
- `LLMInvariantJudge` опционально проверяет смысл через LLM.
- `PromptBuilder` добавляет active invariants в каждый prompt.
- `InvariantAwareAgent` проверяет user request, вызывает LLM, проверяет assistant response и делает retry при нарушении.

Flow:

1. Пользователь вводит запрос.
2. Агент загружает active invariants.
3. Deterministic checker проверяет request.
4. Если конфликт найден, LLM для задачи не вызывается.
5. Если request валиден, prompt собирается с инвариантами.
6. LLM генерирует ответ.
7. Ответ проверяется.
8. При нарушении делается один retry с feedback.
9. Если retry тоже нарушает инварианты, агент возвращает безопасный отказ.

## Запуск

```bash
./gradlew :day-14-state-invariants-kotlin:build
day-14-state-invariants-kotlin/scripts/run-eliza.sh
day-14-state-invariants-kotlin/scripts/run-eliza.sh --args="checker-demo"
day-14-state-invariants-kotlin/scripts/run-eliza.sh --args="interactive"
```

## CLI

```text
/invariants list
/invariants active
/invariants show <id>
/invariants enable <id>
/invariants disable <id>
/invariants add <text>
/invariants check <text>
/debug on
/debug off
/exit
```

## Demo

Проверки:

1. `Предложи архитектуру MVP приложения учета финансов.`
2. `Напиши пример на Java.`
3. `Добавь backend и облачную синхронизацию в MVP.`
4. `Покажи код, где API-ключ прямо в строке.`
5. `Расскажи матный анекдот.`

Ожидаемый вывод:

```text
=== ACTIVE INVARIANTS ===
- stack_kotlin_only
- no_backend_mvp
- no_hardcoded_api_keys

=== USER REQUEST ===
Напиши пример на Java.

=== INVARIANT CHECK ===
Valid: false
Violation: stack_kotlin_only

=== ASSISTANT RESPONSE ===
Не могу выполнить запрос...
```

## Чем инварианты отличаются от памяти и профиля

Memory хранит факты. Profile хранит стиль адаптации. Invariants хранят запреты и обязательные рамки. Пользователь может просить нарушить рамку, но агент должен отказать.

## Видео

Сценарий:

1. Показать структуру проекта.
2. Показать `examples/invariants.default.json`.
3. Показать `InvariantStore`.
4. Показать `InvariantChecker`.
5. Показать `PromptBuilder`.
6. Показать `.env.example`, где нет ключа.
7. Запустить demo.
8. Показать запрос без конфликта.
9. Показать конфликт Java.
10. Показать конфликт backend/cloud sync.
11. Показать конфликт API key hardcode.
12. Показать `/invariants active`.
13. Объяснить: request и response проверяются кодом, инварианты не лежат в истории диалога.

## Проверка Требований

- Инварианты отдельно от диалога: да.
- Активные инварианты в prompt: да.
- Проверка request: да.
- Проверка response: да.
- Отказ при конфликте: да.
- Deterministic checker: да.
- LLM judge: да.
- Пользовательские самозапреты: да.
- RAG/MCP/embeddings не используются: да.
