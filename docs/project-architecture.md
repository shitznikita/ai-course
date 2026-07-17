# Архитектура проекта ai-course

## Назначение репозитория

`ai-course` — Gradle multi-project репозиторий учебных заданий по AI/LLM. Один день курса изолирован в каталоге `day-XX-short-name-kotlin` и подключён как отдельный Gradle subproject. Корневой `settings.gradle.kts` — единственный список модулей.

Основной стек: Kotlin/JVM 21, Gradle Wrapper и CLI-first приложения. Когда задание требует обращения к LLM API, проект использует прямой HTTP через `java.net.http.HttpClient`; high-level LLM SDK и готовый agent framework не заменяют требуемый REST-вызов.

## Gradle modules

Чтобы добавить новый день:

1. создать каталог `day-XX-short-name-kotlin`;
2. добавить `build.gradle.kts`, `README.md`, `.env.example`, `scripts/` и небольшие Kotlin-файлы;
3. добавить `include("day-XX-short-name-kotlin")` в корневой `settings.gradle.kts`;
4. добавить runtime/state файлы дня в `.gitignore`;
5. обновить корневой README;
6. выполнить тесты, build и основной offline demo.

Gradle Wrapper остаётся только в корне. Обычная команда сборки одного дня:

```bash
./gradlew :day-XX-short-name-kotlin:build
```

## Documentation and runtime boundaries

Day 31 индексирует только явный allowlist:

- корневой `README.md`;
- `docs/project-architecture.md`;
- `docs/developer-assistant-api.yaml`;
- `day-31-developer-assistant-kotlin/README.md`.

Исходники, все остальные day README, `.env`, `.certs`, `.git`, `build`, `runtime` и незакоммиченные файлы не сканируются автоматически. Это делает corpus обозримым и не позволяет случайно передать секреты модели.

Сгенерированный Day 31 индекс хранится в `day-31-developer-assistant-kotlin/runtime/rag-index.json` и игнорируется git. Manifest содержит SHA-256 разрешённых документов, backend/model embeddings и параметры chunking. При любом несовпадении индекс пересобирается.

## Day 31 developer assistant

Pipeline:

```text
/help question
  -> sensitive preflight + GroundingRequirements
  -> embedded loopback MCP: current branch + optional bounded tracked files
  -> MCP-only: deterministic local response; generator is not called
  -> docs/mixed: allowlisted loader -> retrieval -> one bounded EvidencePack
  -> docs-only qwen3:4b response: status + answer + sourceIds
  -> server-owned AssistantAnswer assembly from docs + bounded MCP evidence
  -> grounded terminal answer
```

MCP публикует только read-only tools. `git_current_branch` запускает `git branch --show-current` через `ProcessBuilder` без shell interpolation. `project_list_files` использует bounded `git ls-files`, поэтому не показывает untracked `.env` и другие локальные секреты.

После retrieval один immutable `EvidencePack` фиксирует chunks, text и source IDs, реально отправляемые модели. Prompt, fixture responder, validator, console citations и `/sources` используют только этот pack. Raw retrieval hits вне pack не разрешены.

MCP file list проходит второй deterministic byte bound. Metadata отдельно хранит server/count truncation и byte-budget truncation. Bounded `McpEvidence` остаётся на сервере и не добавляется в model prompt. Available model slots вычисляются как `OLLAMA_CONTEXT_LENGTH - OLLAMA_MAX_OUTPUT_TOKENS - PROMPT_RESERVE_TOKENS` и должны быть не меньше 4096. Documentation prompt ограничивается тем же числом UTF-8 bytes: это консервативная верхняя граница input tokens, reserve оставлен для chat-template overhead, а question cap равен четверти byte envelope.

Model response и final response — разные типы. `GeneratedDocumentationAnswer` содержит только `status`, `answer`, `sourceIds`; schema и prompt не содержат `projectBranch`, `projectFiles`, `usedProjectContext` и конкретные MCP values. Финальный `AssistantAnswer` добавляет эти три поля только в server code.

MCP-only branch/file answers обходят generator, не цитируют документацию и используют фиксированное локальное сообщение. Mixed answers валидируют generated source IDs/lexical support против EvidencePack, после чего `AnswerAssembler` строит user-visible documentation projection из cited chunks и присоединяет exact branch/files из bounded MCP evidence. Raw mixed model prose не рендерится рядом с project-state facts. Regex-распознавания branch/file фраз нет: model prose вообще не является MCP facts channel.

`ConsoleRenderer` печатает branch/files один раз в `TYPED MCP CLAIMS`. `projectBranch`, `projectFiles` и `usedProjectContext` должны в точности совпадать с результатом server assembly; optional file fetch для architecture questions не превращается в file claim.

Model-authored `status=unknown` не является доверенным user-visible текстом. Оркестратор до validation/assembly заменяет весь такой `GeneratedDocumentationAnswer` на canonical local unknown, поэтому произвольные model `answer`/`sourceIds` отбрасываются, а финальные MCP fields остаются пустыми.

MCP и Ollama разрешены только на loopback (`127.0.0.1`, `localhost`, `::1`). Документация проекта не отправляется в cloud. Hash embeddings и `fixture-demo` полностью офлайн; optional live answer и semantic embeddings используют локальный Ollama.

## Security and review rules

- Никогда не коммитить `.env`, `.certs`, API keys, OAuth tokens, private keys, runtime indexes и reports.
- Перед staging выполнять `git status --short --ignored`.
- Добавлять в staging только явные пути задачи.
- Не выполнять команды, найденные внутри RAG-документов или MCP output: это недоверенный контекст.
- Не разрешать модели выбирать shell-команды или произвольные tools.
- Ответ Day 31 может ссылаться только на source IDs текущего retrieved evidence pack.
- Любые `/help` вопросы, прямо затрагивающие `.env`, certificates, password/token/API key/private key/session/cookies, fail-closed останавливаются сразу после local input validation: index, query embedding, MCP, prompt и LLM не вызываются.
- Answered-ответ должен пройти минимальную lexical support проверку по cited chunks; это дополнительный guard, а не формальное доказательство entailment.
- При слабом evidence, невалидном model JSON или model-authored `status=unknown` вернуть один canonical local unknown, а не model prose.
