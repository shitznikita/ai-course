# Day 15: Контролируемые переходы состояний

## Цель

День 15 соединяет state machine, guard-условия, инварианты и оркестрацию state-owned agents. Ассистент больше не может перепрыгивать обязательные этапы: переходы ограничены Kotlin-кодом.

Стек: Kotlin CLI, Gradle, `kotlinx.serialization`, прямой REST через `java.net.http.HttpClient`, Eliza/OpenRouter-compatible endpoint.

## Controlled Lifecycle

Состояния:

```text
intake
planning
waiting_for_approval
execution
validation
done
paused
cancelled
```

Allowed transitions:

```text
intake -> planning, paused, cancelled
planning -> waiting_for_approval, paused, cancelled
waiting_for_approval -> execution, planning, paused, cancelled
execution -> validation, planning, paused, cancelled
validation -> done, execution, planning, paused, cancelled
paused -> intake, planning, waiting_for_approval, execution, validation, cancelled
done -> []
cancelled -> []
```

Guard rules:

- `execution` only if `approvedPlan=true`.
- `done` only if `validationPassed=true`.
- `done` and `cancelled` are terminal.
- `planning` cannot be skipped because `intake -> execution` is absent.

## Архитектура

- `AgentOrchestrator` вызывает агента-владельца текущего state и применяет только разрешенные переходы.
- `TaskStateMachine` хранит allowed transitions.
- `TransitionValidator` проверяет guards.
- `TaskStateStorage` сохраняет `task_state.json`.
- `InvariantStore` и `InvariantChecker` проверяют stage artifacts.
- `IntakeStateAgent` владеет `intake` и artifact `taskBrief`.
- `PlanningStateAgent` владеет `planning` и artifact `finalPlan`.
- `ApprovalStateAgent` владеет `waiting_for_approval` и artifact `approvalSummary`.
- `ExecutionStateAgent` владеет `execution` и artifact `executionResult`.
- `ValidationStateAgent` владеет `validation` и artifact `validationReport`.
- `PromptBuilder` собирает stage prompts.
- `LlmClient` отправляет прямой REST-запрос.

## Хранение

Runtime files:

```text
task_state.json
memory/invariants.json
```

Они ignored. Примеры лежат в `examples/`.

## Запуск

```bash
./gradlew :day-15-controlled-transitions-kotlin:build
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh --args="transition-tests"
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh --args="pause-resume-demo"
day-15-controlled-transitions-kotlin/scripts/run-eliza.sh --args="interactive"
```

## CLI

```text
/status
/state
/history
/approve
/reject <reason>
/pause
/resume
/try-transition <state>
/reset
/exit
```

## Demo

Normal path:

1. `IntakeStateAgent` принимает задачу и создает `taskBrief`.
2. `intake -> planning`.
3. `PlanningStateAgent` создает один `finalPlan`.
4. Plan checked by invariants.
5. `planning -> waiting_for_approval`.
6. `ApprovalStateAgent` показывает approval gate.
7. Skip attempt `/try-transition execution` denied because `approvedPlan=false`.
8. `/approve` sets `approvedPlan=true`.
9. `waiting_for_approval -> execution`.
10. `ExecutionStateAgent` создает `executionResult`.
11. Execution result checked by invariants.
12. `execution -> validation`.
13. `ValidationStateAgent` создает `validationReport`.
14. `validationPassed=true`.
15. `validation -> done`.

Transition tests:

- `intake -> execution`: denied.
- `planning -> execution` without approval: denied.
- `execution -> done`: denied.
- `validation -> done` with failed validation: denied.
- `validation -> execution`: allowed for fix.
- `done -> execution`: denied.

## Почему надежнее prompt-only

Модель может предложить next state, но не может применить его. `TaskStateMachine.transition()` проверяет allowed transitions и guard-условия, пишет history и только после этого меняет state.

## Видео

Сценарий:

1. Показать структуру проекта.
2. Показать `TaskStateMachine`.
3. Показать `TransitionValidator`.
4. Показать `task_state.json`.
5. Показать `StateAgent` ownership: intake, planning, approval, execution, validation.
6. Показать `.env.example`, где нет ключа.
7. Запустить demo.
8. Показать вывод `STATE AGENT: IntakeStateAgent` и `PlanningStateAgent`.
9. Показать `waiting_for_approval`.
10. Попробовать skip execution.
11. Показать denied transition.
12. Выполнить approve.
13. Показать `ExecutionStateAgent`, `ValidationStateAgent`, execution -> validation -> done.
14. Запустить `transition-tests`.
15. Запустить `pause-resume-demo`.

## Проверка Требований

- Допустимые состояния: да.
- Разрешенные переходы: да.
- Guards: да.
- Нельзя делать execution без approve: да.
- Нельзя done без validation: да.
- Pause/resume: да.
- Invariants integrated: да.
- Оркестрация state-owned agents: да.
- Прямой REST API без SDK agents: да.
