# Day 14: Инварианты и ограничения состояния

## Цель

День 14 продолжает День 13: тот же CLI-агент со state machine для сбора ТЗ MVP Android-приложения учета финансов, но каждый stage теперь получает активные инварианты проекта.

Главная идея: инварианты не заменяют состояние задачи. Они добавляются поверх lifecycle `intake -> planning -> waiting_for_approval -> execution -> validation -> done` и влияют на ответы LLM.

Стек: Kotlin CLI, Gradle, `kotlinx.serialization`, прямой REST через `java.net.http.HttpClient`, Eliza/OpenRouter-compatible endpoint.

## Runtime Storage

```text
state/task_state.json
memory/invariants.json
```

Оба файла ignored как runtime-state. Примеры лежат в:

```text
examples/task_state.example.json
examples/invariants.default.json
```

## Архитектура

- `TaskStateMachine` хранит разрешенные переходы из Day 13.
- `TaskStateStorage` сохраняет и загружает `state/task_state.json`.
- `AgentOrchestrator` ведет задачу через stages и пишет transition history.
- `IntakeAgent`, `PlanningAgent`, `ExecutionAgent`, `ValidationAgent` вызывают LLM для своих stages.
- `InvariantStore` хранит активные инварианты.
- `InvariantChecker` делает deterministic audit request/response.
- `LLMInvariantJudge` делает LLM audit пользовательского запроса.
- `PromptBuilder` добавляет active invariants в каждый stage prompt.

## Flow

1. Пользователь отправляет request.
2. Агент загружает active invariants.
3. Deterministic checker проверяет request только для audit.
4. LLM judge проверяет request только для audit.
5. Raw user request отправляется в stage LLM как есть.
6. Stage prompt содержит active invariants и правило соблюдать их.
7. Ответ stage LLM проверяется `InvariantChecker`.
8. Если ответ нарушает invariant, делается один retry с violation feedback.
9. Если retry тоже нарушает invariant, state переходит в `paused` с reason `assistant_response_invariant_violation`.

Важно: конфликт в user request не блокирует вызов LLM. Конфликт фиксируется в audit, но request отправляется модели как есть, чтобы видно было, как LLM сама объясняет отказ или предлагает безопасную альтернативу.

## Default Invariants

- Clean Architecture.
- Только Kotlin, не Java.
- Только Jetpack Compose, не XML layout.
- MVP без backend/cloud sync.
- Локальное хранение через Room.
- API-ключи только через `.env` или env vars.
- Без мата и токсичности.

## Запуск

```bash
./gradlew :day-14-state-invariants-kotlin:build
day-14-state-invariants-kotlin/scripts/run-eliza.sh
day-14-state-invariants-kotlin/scripts/run-eliza.sh --args="checker-demo"
day-14-state-invariants-kotlin/scripts/run-eliza.sh --args="interactive"
```

## CLI

State machine:

```text
/status
/state
/history
/approve
/reject <comment>
/pause
/resume
/reset
/exit
```

Invariants:

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
```

## Demo

Default demo показывает:

1. active invariants;
2. normal path из Day 13: intake -> planning -> waiting_for_approval;
3. simulated restart через saved `task_state.json`;
4. `/approve`: execution -> validation -> done;
5. conflict request `Напиши пример на Java...`;
6. conflict request `Добавь backend и облачную синхронизацию...`;
7. audit: request conflict exists, LLM still called, response validation runs.

Ожидаемые маркеры в выводе:

```text
=== INVARIANT AUDIT ===
Raw user request sent to stage LLM: true
LLM judge request audit called: true
=== ARTIFACT CREATED ===
Stage LLM called: true
Response validation:
=== TRANSITION ===
```

## Видео

Сценарий записи:

1. Показать, что Day 14 содержит `TaskStateMachine` и `AgentOrchestrator`, то есть продолжает Day 13.
2. Показать `examples/invariants.default.json`.
3. Показать `PromptBuilder`: active invariants входят в stage prompt.
4. Показать `InvariantChecker`: request audit и response validation.
5. Показать `.env.example`: секретов нет.
6. Запустить default demo.
7. На valid path объяснить lifecycle и persisted state.
8. На Java/backend conflict показать, что raw request ушел в LLM, а не был локально заблокирован.
9. Показать `/state` и `/history` в interactive.

## Проверка Требований

- Day 14 продолжает Day 13 state machine: да.
- `state/task_state.json` persisted: да.
- Инварианты отдельно от state: да.
- Active invariants в prompt: да.
- Request conflict не блокирует LLM call: да.
- Raw user request отправляется в stage LLM: да.
- Response validation + retry once: да.
- Runtime `memory/` и `state/` ignored: да.
- RAG/MCP/embeddings не используются: да.

## Security

- `.env`, `.certs/`, `memory/`, `state/` не коммитятся.
- OAuth token берется из `.env` или environment variables.
- В репозитории только `.env.example` с placeholder.
