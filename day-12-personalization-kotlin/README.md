# Day 12: Персонализация ассистента

## Цель

День 12 показывает персонализацию поверх memory layers: агент хранит несколько профилей пользователя, выбирает активный профиль и автоматически добавляет его в каждый prompt.

Стек: Kotlin CLI, Gradle, `kotlinx.serialization`, прямой REST через `java.net.http.HttpClient`, Eliza/OpenRouter-compatible endpoint.

## Архитектура

Основные компоненты:

- `UserProfileManager` хранит список профилей и активный профиль.
- `MemoryManager` хранит short-term, working и long-term memory отдельно.
- `PromptBuilder` собирает prompt и audit.
- `PersonalizedAgent` вызывает LLM и сохраняет текущий диалог.
- `DemoRunner` сравнивает один вопрос на трех профилях.
- `InteractiveCli` дает команды управления профилями.

Prompt собирается в порядке:

1. system prompt;
2. long-term memory;
3. active user profile;
4. working memory;
5. short-term memory;
6. current user message.

Важно: в prompt попадает только активный профиль, а не весь список профилей.

## Хранение

Runtime-файлы не коммитятся:

```text
memory/short_term/current_chat.json
memory/working/task_context.json
memory/long_term/user_memory.json
profiles/profiles.json
profiles/active_profile.json
```

Примеры профилей для сдачи лежат в `examples/profiles/`.

## Профили

В демо есть три профиля:

- `beginner`: простые объяснения, пошаговый чек-лист, минимум терминов.
- `senior_mobile_dev`: кратко, технически, Kotlin/Android, trade-offs.
- `product_manager`: цель, пользователи, риски, MVP, меньше кода.

Профиль содержит: id, имя, роль, уровень опыта, стиль, формат, стек/домен, ограничения, привычки, предпочтения, язык, примеры желательного и нежелательного ответа.

## Запуск

```bash
./gradlew :day-12-personalization-kotlin:build
day-12-personalization-kotlin/scripts/run-eliza.sh
day-12-personalization-kotlin/scripts/run-eliza.sh --args="interactive"
```

Если локального `.env` нет, скрипт переиспользует `day-01-llm-rest-kotlin/.env`.

## CLI

```text
/profile list
/profile use <profile_id>
/profile show
/profile create
/profile update <key> <value>
/profile compare
/memory show
/profile status
/exit
```

Ключи для `/profile update`:

```text
name
role
experience_level
style
format
domain_stack
constraint
habit
preference
language
do_example
avoid_example
```

## Demo

Автоматический demo задает один вопрос всем профилям:

```text
Объясни, как мне реализовать сохранение контекста в AI-агенте.
```

Ожидаемый вывод:

```text
=== PROFILE: beginner ===
...

=== PROFILE: senior_mobile_dev ===
...

=== PROFILE: product_manager ===
...

=== COMPARISON ===
- beginner: ...
- senior_mobile_dev: ...
- product_manager: ...
```

В audit видно:

- активный профиль;
- стиль и формат;
- какие слои вошли в prompt;
- примерную оценку prompt tokens.

## Чем профиль отличается от long-term memory

Long-term memory хранит устойчивые факты и правила. Profile хранит способ адаптации ответа: стиль, формат, уровень детализации, ограничения и привычки пользователя.

Если хранить профиль только как обычную память, агент хуже понимает, что это не факт задачи, а инструкция персонализации.

## Видео

Сценарий:

1. Показать структуру проекта.
2. Показать `examples/profiles`.
3. Показать `UserProfileManager`.
4. Показать `PromptBuilder` и порядок слоев.
5. Показать `.env.example`, где нет реального ключа.
6. Запустить `scripts/run-eliza.sh`.
7. Показать ответы трех профилей.
8. Показать `/profile list`, `/profile use beginner`, `/profile show`.
9. Объяснить, что активный профиль автоматически идет в каждый запрос.

## Проверка Требований

- Несколько профилей пользователя: да.
- Активный профиль: да.
- Профиль в каждом prompt: да.
- Разные ответы для разных профилей: да.
- Профили отдельно от памяти диалога: да.
- Секреты не хранятся в профиле: да.
- RAG/MCP/embeddings не используются: да.
