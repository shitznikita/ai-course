# Project Instructions

This repository stores AI course assignments. Keep every assignment small, reviewable, and isolated in its own day folder.

For durable course memory after context compaction, read `skills/course-continuity/SKILL.md`.

## Current Snapshot For New Agents

- Current date of this snapshot: 2026-07-17.
- `main` contains completed course days 1-31; Day 32 adds a bounded AI pull-request reviewer driven by a secure GitHub Actions workflow.
- Preferred stack remains Kotlin CLI with direct REST calls through `java.net.http.HttpClient`.
- Preferred provider remains Eliza API with OAuth token from `.env`/environment variables.
- Cloud assignments still use the Eliza/OpenRouter endpoint when required:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

- Do not use high-level LLM SDKs or ready-made agents as a replacement for the required REST request.
- Days 26-31 use local Ollama through a loopback-only HTTP API when a model is required. Day 30 targets a CPU VPS with `qwen3:4b`, Tesseract OCR, strict JSON, a `<50%` coverage gate before LLM, claim-level source IDs, deterministic allergy/regulatory checks and bounded chat; Caddy may expose only the Ktor UI/API over HTTPS behind a persistent browser-stored access token.
- Day 31 is a local developer-assistant CLI: sensitive topics are refused before index/embedding/MCP/prompt/model work; one bounded immutable EvidencePack is shared by prompt/fixture/validator/citations; MCP-only questions bypass generation; mixed model responses contain only `status`/`answer`/`sourceIds`; and exact `projectBranch`/`projectFiles` are assembled server-side from bounded MCP evidence that never enters the model prompt. Project documentation is never sent to cloud providers.
- Day 32 uses Eliza only for bounded PR-review text. Its `pull_request_target` workflow runs only for non-draft same-repository PRs whose base is the repository default branch, checks out that immutable base SHA, obtains PR metadata/files as inert GitHub REST data, and never checks out or executes head code. One reusable fail-closed cloud policy scans allowlisted base corpus plus raw changed paths/provider patches/bounded full diff/decoded blobs—including plain/quoted/backslash-escaped `Authorization` with schemes accepted by `AppConfig` such as OAuth and quoted credential values with special characters such as `$`—before any model call; only exact known placeholders and structural references are exempt. A match produces a non-echo coverage-0 diagnostic, and a PR above the configured changed-file cap also stops before partial review. Prompt budgeting keeps only whole file/evidence items in a typed `TransmittedReviewInput`; validator authorization and `reviewedFiles` derive only from that exact successful-call subset. Model-controlled title/detail/recommendation pass the same policy before rendering/publishing. `LLM_API_KEY` is the only repository LLM secret; no `.env` is used in CI. The reviewer posts one Russian sticky comment and cannot review the PR that first introduces its own workflow until it is merged.
- The best Day 30 offline smoke tests are:

```bash
./gradlew :day-30-private-cosmetics-service-kotlin:test
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh fixture-demo
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh eval-dry-run
```

- The best Day 31 offline smoke tests are:

```bash
./gradlew :day-31-developer-assistant-kotlin:test
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="mcp-smoke"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="fixture-demo"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="eval-dry-run"
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
- `day-10-context-strategies-kotlin/`: Day 10 context strategies without summary: sliding, facts, branching.
- `day-26-local-llm-kotlin/` through `day-29-local-llm-optimization-kotlin/`: local Ollama, local web/RAG and optimization assignments.
- `day-30-private-cosmetics-service-kotlin/`: private VPS web/API with OCR, local evidence retrieval, grounded report and chat.
- `day-31-developer-assistant-kotlin/`: project-documentation RAG plus read-only git MCP and grounded `/help`.
- `day-32-ai-code-review-kotlin/`: bounded Eliza-backed PR reviewer with offline fixtures, evaluation cases, and a secure same-repository GitHub Actions entrypoint.
- Add future assignments as `day-11-...`, `day-12-...`, and so on.
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
  - `day-10-context-strategies-kotlin/context-state.json`
  - `day-10-context-strategies-kotlin/*.tmp`
  - `day-30-private-cosmetics-service-kotlin/reports/`
  - `day-30-private-cosmetics-service-kotlin/runtime/`
  - `day-30-private-cosmetics-service-kotlin/*.tmp`
  - `day-31-developer-assistant-kotlin/reports/`
  - `day-31-developer-assistant-kotlin/runtime/`
  - `day-31-developer-assistant-kotlin/*.tmp`
  - `day-32-ai-code-review-kotlin/reports/`
  - `day-32-ai-code-review-kotlin/runtime/`
  - `day-32-ai-code-review-kotlin/*.tmp`

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

- For Day 10:

```bash
./gradlew :day-10-context-strategies-kotlin:build
day-10-context-strategies-kotlin/scripts/run-eliza.sh
```

- For Day 30:

```bash
./gradlew :day-30-private-cosmetics-service-kotlin:test
./gradlew :day-30-private-cosmetics-service-kotlin:build
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh fixture-demo
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh eval-dry-run
```

- For Day 31:

```bash
./gradlew :day-31-developer-assistant-kotlin:test
./gradlew :day-31-developer-assistant-kotlin:build
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="mcp-smoke"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="fixture-demo"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="eval-dry-run"
```

- For Day 32:

```bash
./gradlew :day-32-ai-code-review-kotlin:test
./gradlew :day-32-ai-code-review-kotlin:build
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="fixture-demo"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="eval-dry-run"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="prompt-dry-run"
```
