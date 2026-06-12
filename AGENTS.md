# Project Instructions

This repository stores AI course assignments. Keep every assignment small, reviewable, and isolated in its own day folder.

For durable course memory after context compaction, read `skills/course-continuity/SKILL.md`.

## Current Snapshot For New Agents

- Current date of this snapshot: 2026-06-12.
- `main` contains completed course days 1-9.
- Preferred stack remains Kotlin CLI with direct REST calls through `java.net.http.HttpClient`.
- Preferred provider remains Eliza API with OAuth token from `.env`/environment variables.
- Recent default endpoint/model for agent tasks:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

- Do not use high-level LLM SDKs or ready-made agents as a replacement for the required REST request.
- Day 9 is the latest completed assignment and the best smoke test is:

```bash
./gradlew :day-09-history-compression-kotlin:build
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="multi"
```

## Repository Layout

- `day-01-llm-rest-kotlin/`: Day 1 Kotlin CLI that sends a direct REST request to an LLM API.
- `day-02-response-format-kotlin/`: Day 2 response format and output constraints.
- `day-03-reasoning-methods-kotlin/`: Day 3 reasoning strategies.
- `day-04-temperature-kotlin/`: Day 4 temperature comparison.
- `day-05-model-versions-kotlin/`: Day 5 model versions.
- `day-06-first-agent-kotlin/`: Day 6 first chat agent with in-process memory.
- `day-07-persistent-context-kotlin/`: Day 7 persistent JSON context.
- `day-08-token-accounting-kotlin/`: Day 8 token accounting and context overflow demos.
- `day-09-history-compression-kotlin/`: Day 9 history compression with summary and multi-scenario comparison.
- Add future assignments as `day-10-...`, `day-11-...`, and so on.
- Keep Gradle Wrapper files at the repository root.

## GitHub Workflow

- Work on a separate branch for each assignment: `day-01-llm-rest`, `day-02-...`.
- Prefer one pull request per assignment or major iteration.
- Merge into `main` only after the code runs and the README is updated.
- Use short commits that explain the concrete change.

## Security Rules

- Never commit `.env`, `.certs/`, API keys, OAuth tokens, private SSH keys, or real secrets.
- Commit `.env.example` with placeholder values only.
- For corporate/internal endpoints, keep the repository private unless publication is explicitly allowed.
- Before pushing, run `git status --ignored` and check that secrets are ignored.
- Keep local/generated context files ignored:
  - `day-07-persistent-context-kotlin/agent-history.json`
  - `day-08-token-accounting-kotlin/token-agent-history.json`
  - `day-08-token-accounting-kotlin/*.local.md`
  - `day-09-history-compression-kotlin/recent-messages.json`
  - `day-09-history-compression-kotlin/context-summary.md`
  - `day-09-history-compression-kotlin/*.tmp`

## Assignment Quality Bar

- Each day folder should include its own `README.md`.
- The README should contain: goal, stack, setup, run commands, expected output, and video scenario when relevant.
- Code should be minimal and match the assignment constraints.
- Prefer direct REST/HTTP code for LLM API tasks unless the assignment explicitly allows SDKs.

## Verification

- Run the smallest relevant build or command before committing.
- For Day 1:

```bash
./gradlew :day-01-llm-rest-kotlin:build
day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"
```

- For Day 2:

```bash
./gradlew :day-02-response-format-kotlin:build
day-02-response-format-kotlin/scripts/run-eliza.sh
```

- For Day 3:

```bash
./gradlew :day-03-reasoning-methods-kotlin:build
day-03-reasoning-methods-kotlin/scripts/run-eliza.sh
```

- For Day 4:

```bash
./gradlew :day-04-temperature-kotlin:build
day-04-temperature-kotlin/scripts/run-eliza.sh
```

- For Day 5:

```bash
./gradlew :day-05-model-versions-kotlin:build
day-05-model-versions-kotlin/scripts/run-eliza.sh
```

- For Day 6:

```bash
./gradlew :day-06-first-agent-kotlin:build
day-06-first-agent-kotlin/scripts/run-eliza.sh
```

- For Day 7:

```bash
./gradlew :day-07-persistent-context-kotlin:build
day-07-persistent-context-kotlin/scripts/run-eliza.sh
```

- For Day 8:

```bash
./gradlew :day-08-token-accounting-kotlin:build
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="short"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="forgetting"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-dry-run"
```

- For Day 9:

```bash
./gradlew :day-09-history-compression-kotlin:build
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="compare"
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="multi"
```
