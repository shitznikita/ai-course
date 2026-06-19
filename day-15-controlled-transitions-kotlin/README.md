# Day 15: Контролируемые переходы состояний

## Цель

День 15 соединяет state machine, guard-условия, инварианты и swarm agents. Ассистент больше не может перепрыгивать обязательные этапы: переходы ограничены Kotlin-кодом.

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

- `AgentOrchestrator` управляет задачей.
- `TaskStateMachine` хранит allowed transitions.
- `TransitionValidator` проверяет guards.
- `TaskStateStorage` сохраняет `task_state.json`.
- `InvariantStore` и `InvariantChecker` проверяют stage artifacts.
- `PlanningAgentsSwarm` запускает Product, Tech, Risk planners и OrchestratorAgent.
- `ExecutionAgent` делает результат по утвержденному плану.
- `ValidationAgent` проверяет результат.
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

1. intake accepts task.
2. `intake -> planning`.
3. Planning swarm runs:
   - `ProductPlannerAgent`;
   - `TechPlannerAgent`;
   - `RiskPlannerAgent`;
   - `OrchestratorAgent`.
4. Plan checked by invariants.
5. `planning -> waiting_for_approval`.
6. Skip attempt `/try-transition execution` denied because `approvedPlan=false`.
7. `/approve` sets `approvedPlan=true`.
8. `waiting_for_approval -> execution`.
9. Execution result checked by invariants.
10. `execution -> validation`.
11. Validation report checked.
12. `validationPassed=true`.
13. `validation -> done`.

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
5. Показать planning swarm.
6. Показать `.env.example`, где нет ключа.
7. Запустить demo.
8. Показать swarm output.
9. Показать `waiting_for_approval`.
10. Попробовать skip execution.
11. Показать denied transition.
12. Выполнить approve.
13. Показать execution -> validation -> done.
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
- Swarm на planning этапе: да.
- Прямой REST API без SDK agents: да.
