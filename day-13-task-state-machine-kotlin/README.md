# Day 13: Состояние задачи (Task State Machine)

## Цель

День 13 превращает чат в stateful-агента: задача имеет формальное состояние, текущий шаг, ожидаемое действие, артефакты этапов и историю переходов.

Стек: Kotlin CLI, Gradle, `kotlinx.serialization`, прямой REST через `java.net.http.HttpClient`, Eliza/OpenRouter-compatible endpoint.

## State Machine

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

Переходы проверяются кодом:

```text
intake -> planning, paused, cancelled
planning -> waiting_for_approval, execution, paused, cancelled
waiting_for_approval -> execution, planning, paused, cancelled
execution -> validation, planning, paused, cancelled
validation -> done, execution, planning, paused, cancelled
paused -> intake, planning, execution, validation, cancelled
done -> []
cancelled -> []
```

LLM может создавать артефакты этапов, но не может сама менять `status`: переход делает `TaskStateMachine`.

## Архитектура

- `AgentOrchestrator` управляет задачей и вызывает этапы.
- `TaskStateMachine` хранит разрешенные transitions.
- `TaskStateStorage` сохраняет `task_state.json`.
- `IntakeAgent`, `PlanningAgent`, `ExecutionAgent`, `ValidationAgent` создают stage artifacts.
- `PromptBuilder` делает stage-specific prompts.
- `LlmClient` отправляет прямой REST-запрос.

## Хранение

Runtime state:

```text
state/task_state.json
```

Файл ignored. Пример структуры лежит в `examples/task_state.example.json`.

## Запуск

```bash
./gradlew :day-13-task-state-machine-kotlin:build
day-13-task-state-machine-kotlin/scripts/run-eliza.sh
day-13-task-state-machine-kotlin/scripts/run-eliza.sh --args="pause-demo"
day-13-task-state-machine-kotlin/scripts/run-eliza.sh --args="interactive"
```

## CLI

```text
/status
/pause
/resume
/approve
/reject <comment>
/state
/history
/reset
/exit
```

Любой обычный текст создает новую задачу и запускает pipeline с `intake`.

## Demo

Основной сценарий:

1. Пользователь вводит задачу про MVP Android-приложения учета финансов.
2. `IntakeAgent` создает `task_brief`.
3. Код переводит `intake -> planning`.
4. `PlanningAgent` создает `task_plan`.
5. Код переводит `planning -> waiting_for_approval`.
6. Demo имитирует перезапуск и показывает, что state загружен с диска.
7. `/approve` переводит `waiting_for_approval -> execution`.
8. `ExecutionAgent` создает `draft_result`.
9. Код переводит `execution -> validation`.
10. `ValidationAgent` создает `validation_report`.
11. Код переводит `validation -> done`.

Pause demo:

```bash
day-13-task-state-machine-kotlin/scripts/run-eliza.sh --args="pause-demo"
```

Запрос `Сделай ТЗ для приложения.` не содержит platform/deadline/core features, поэтому state становится `paused` и ожидает user input.

## Почему это не обычный чат

Обычный чат хранит только историю сообщений. Stateful-agent хранит структуру процесса: stage, current step, expected action, artifacts и transition history. Поэтому после перезапуска он продолжает с `waiting_for_approval`, а не просит снова объяснить задачу.

## Видео

Сценарий:

1. Показать структуру проекта.
2. Показать `TaskStateMachine`.
3. Показать allowed transitions.
4. Показать `StageAgents`.
5. Показать `task_state.json`.
6. Показать `.env.example`, где нет ключа.
7. Запустить demo.
8. Показать `intake -> planning`.
9. Показать остановку на `waiting_for_approval`.
10. Показать simulated restart.
11. Выполнить `/approve`.
12. Показать `execution -> validation -> done`.
13. Показать `/history`.

## Проверка Требований

- Формальное состояние задачи: да.
- Этап, текущий шаг, ожидаемое действие: да.
- Автоматические transitions по артефактам: да.
- Pause/resume: да.
- Persistence на диск: да.
- Невалидные transitions блокируются кодом: да.
- Разные stage prompts: да.
- RAG/MCP/embeddings не используются: да.
