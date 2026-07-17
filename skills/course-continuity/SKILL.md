---
name: course-continuity
description: Use when continuing this AI course repository after context compaction, starting a new course day, modifying existing day assignments, preparing GitHub PRs, recording/submitting videos, or needing the stable project memory and decisions from earlier days.
---

# Course Continuity

Use this skill as the durable memory for the AI course repo. Keep future work consistent with these decisions unless the user explicitly changes direction.

## Current Actual Snapshot (2026-07-17)

- `main` contains completed days 1-31. Historical snapshot text below documents earlier decisions but is no longer the source of truth for the latest day number.
- Day 30 is merged as `day-30-private-cosmetics-service-kotlin`.
- Day 30 deploys `qwen3:4b` through loopback Ollama on a CPU VPS. Ktor remains on loopback and is published through Caddy automatic HTTPS; the permanent access token is verified by the API and stored only in the authorized browser's `localStorage` until logout.
- Photo input uses local Tesseract OCR followed by user confirmation; the LLM receives text only.
- Quality comes from exact INCI retrieval, a `<50%` pre-LLM coverage gate, 63 curated local cards, claim-level primary/official sources, deterministic allergy/regulatory checks, strict enum/ID model decisions, server-side product-level report assembly and bounded RAM chat rather than fine-tuning. Technical ingredients no longer backfill «key ingredients», and ingredient `suitableFor` tags are not unioned into a product verdict.
- Raw Ollama port `11434`, VPS API tokens, SSH private keys, uploaded photos and chat history must never be committed or publicly exposed.
- Day 31 is `day-31-developer-assistant-kotlin` in the managed `AICOURSE-1` workflow worktree. Sensitive topics stop before index/embedding/MCP/prompt/model work. It indexes only root `README.md`, two reviewed root docs and its own README; one immutable bounded EvidencePack is the source of truth for prompt/fixture/validator/citations. MCP-only questions bypass generation, mixed model responses are docs-only, and strict typed `projectBranch`/`projectFiles` values are assembled server-side from bounded MCP evidence withheld from the model prompt. `/help` uses local `qwen3:4b`, aggregate context/output/reserve budgets and independent RAG/MCP grounding. Hash retrieval and fixture/eval modes stay deterministic and offline.
- Day 31 generated indexes/reports, `.env`, `.certs` directories or symlinks, and all real secrets remain ignored. Project docs must not be sent to cloud LLMs.
- Day 32 is `day-32-ai-code-review-kotlin`: a Kotlin CLI that turns bounded PR metadata, patches and changed blobs into a structured Russian review, then updates one sticky PR comment. One reusable fail-closed cloud policy scans allowlisted base corpus and raw changed paths/provider patches/bounded full diff/decoded blobs, including plain/quoted/backslash-escaped `Authorization` with schemes accepted by `AppConfig` such as OAuth and quoted credential values with special characters such as `$`; only exact known placeholders and structural references are exempt. A match makes zero model calls and emits a non-echo coverage-0 diagnostic. A PR above the configured changed-file cap also stops before partial review. Prompt budgeting selects only whole file/evidence items into typed `TransmittedReviewInput`; validator path/line/source authorization and reviewed coverage use only that exact successful-call subset. Model-controlled title/detail/recommendation are policy-checked before rendering or publishing. Local `fixture-demo`, `eval-dry-run`, and `prompt-dry-run` stay deterministic/offline. Live mode uses direct REST to Eliza/OpenRouter with OAuth from `.env` or environment variables.
- Day 32 CI is deliberately a `pull_request_target` workflow because it must use `LLM_API_KEY` and post a comment. It has only `contents: read` and `pull-requests: write`; is guarded to non-draft same-repository PRs targeting the repository default branch; pins official actions to immutable full commit SHAs; checks out **only** that `github.event.pull_request.base.sha` with credentials disabled; fetches PR metadata/files through GitHub REST as inert data; and never checks out, sources, runs, builds, or tests the head revision. No secret-bearing `.env` reaches CI.
- The workflow cannot review the PR that introduces or changes the reviewer itself: `pull_request_target` executes the workflow from the base branch. Merge the reviewer first, then demonstrate it on a later same-repository non-draft PR. Fork PRs and drafts are intentionally skipped.
- Best offline checks:

```bash
./gradlew :day-31-developer-assistant-kotlin:test
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="mcp-smoke"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="fixture-demo"
day-31-developer-assistant-kotlin/scripts/run-assistant.sh --args="eval-dry-run"
```

Day 32 offline checks:

```bash
./gradlew :day-32-ai-code-review-kotlin:test
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="fixture-demo"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="eval-dry-run"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="prompt-dry-run"
```

## Current Snapshot

- Snapshot date: 2026-06-12.
- `main` currently contains completed assignments for days 1-9.
- Day 10 is implemented as a separate PR with a cleaner multi-file Kotlin layout.
- Latest completed assignment on main: Day 9, history compression with `compare` and `multi` modes.
- Current working convention: one Kotlin/JVM Gradle subproject per day, CLI first, direct REST via `java.net.http.HttpClient`, no high-level LLM SDKs.
- Default provider for recent agent tasks: Eliza API through the OpenRouter-compatible endpoint.
- Default recent model: `meta-llama/llama-3.3-70b-instruct`.
- The best smoke test for the latest state is:

```bash
./gradlew :day-09-history-compression-kotlin:build
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="multi"
```

After context compaction, start by reading `AGENTS.md`, this file, root `README.md`, and the latest day README before changing code.

## User Preferences

- The user wants a technical mentor and executor, not just advice.
- Ask only truly necessary questions; otherwise make reasonable choices and implement.
- Preferred stack: Kotlin CLI.
- Preferred provider: Eliza API using the working company quota/key from Day 1.
- Keep solutions simple, explicit, and suitable for video + code submission.
- Do not use ready-made AI agents or high-level LLM SDKs instead of direct REST requests.
- For day-assignment changes, the user wants the agent to push the branch and create a GitHub pull request automatically after committing; the user will approve/merge on GitHub.

## Repository

- Root: `/Users/shitznikita/Documents/Projects/shitz-projects/ai-course`.
- GitHub remote: `git@github.com:shitznikita/ai-course.git`.
- Repository should remain private unless the user explicitly confirms public sharing is allowed.
- Use one folder per day: `day-01-...`, `day-02-...`, `day-03-...`.
- Use one branch/PR per assignment or substantial revision.
- Before committing, verify `.env`, `.certs/`, build outputs, IDE files, and secrets are ignored.
- After committing day work, push the branch and create a PR into `main` when credentials/tools/policy allow it.

## Common Implementation Pattern

- Kotlin/JVM Gradle subproject per day.
- Root Gradle Wrapper stays in repo root.
- Each day has:
  - `build.gradle.kts`
  - `src/main/kotlin/Main.kt`
  - `.env.example`
  - `scripts/setup-yandex-ca.sh`
  - `scripts/run-eliza.sh`
  - `README.md`
- API key is loaded from `.env` or env vars, never hardcoded.
- `run-eliza.sh` may reuse `day-01-llm-rest-kotlin/.env` when a later day has no own `.env`.
- Eliza internal CA requires a local Java truststore in `.certs/`; `.certs/` is ignored.

## Eliza/API Notes

- Historical Day 1-3 raw API URL: `https://api.eliza.yandex.net/raw/openai/v1/chat/completions`.
- Auth header: `Authorization: OAuth <token>`.
- Historical raw model: `gpt-5-mini`.
- Eliza/gpt-5-mini rejected:
  - `max_tokens`: use prompt instructions or supported model-specific fields instead.
  - `temperature=0.2`: API said only default value is supported.
- Therefore raw `gpt-5-mini` requests should stay minimal:

```json
{
  "model": "gpt-5-mini",
  "messages": [...]
}
```

- Current default for agent days 6-10:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

- Keep model/provider identical within a comparison assignment unless the assignment says otherwise.

## Completed Days

### Day 1: REST request

Folder: `day-01-llm-rest-kotlin`.

Purpose: minimal Kotlin CLI that sends one REST request to LLM API and prints the answer.

Run:

```bash
./gradlew :day-01-llm-rest-kotlin:build
day-01-llm-rest-kotlin/scripts/run-eliza.sh --args="Ответь кратко: что такое REST API?"
```

Key lesson: Java needed Yandex CA truststore for `api.eliza.yandex.net`.

### Day 2: Response format

Folder: `day-02-response-format-kotlin`.

Purpose: same prompt, response control comparison.

Current implementation prints 5 blocks:

```text
WITHOUT LIMITS
FORMAT ONLY
LENGTH ONLY
FINISH ONLY
ALL LIMITS
```

Run:

```bash
./gradlew :day-02-response-format-kotlin:build
day-02-response-format-kotlin/scripts/run-eliza.sh
```

Key lesson: separate restrictions show their individual effects; `FINISH ONLY` can end with `END` while still being long.

### Day 3: Reasoning methods

Folder: `day-03-reasoning-methods-kotlin`.

Task: 12 coins, one fake and lighter, find it in 3 balance-scale weighings.

Important correction from the user: Day 3 must produce exactly 4 independent API answers:

```text
1. DIRECT
2. STEP BY STEP
3. PROMPT FIRST
4. EXPERTS
```

Then `COMPARISON (NOT A SOLUTION MODE)` compares them. It is not a fifth solution mode.

Current intended semantics:

- DIRECT: send task exactly as is.
- STEP BY STEP: same task + only `Решай пошагово.`
- PROMPT FIRST: first ask model to write a prompt for itself, then send that generated prompt as the solving prompt.
- EXPERTS: ask analyst, engineer, critic; each expert gives their own solution; then final shared answer.
- COMPARISON: compare all 4 responses, check correctness, note differences and best approach.

Run:

```bash
./gradlew :day-03-reasoning-methods-kotlin:build
day-03-reasoning-methods-kotlin/scripts/run-eliza.sh
```

Observed useful result: prompt-first can be very detailed but still wrong; comparison should check against an explicit reference idea, not just reward verbosity.

### Day 4: Temperature

Folder: `day-04-temperature-kotlin`.

Purpose: same prompt and same model, three REST calls with `temperature` values `0`, `0.7`, `1.2`, plus a local comparison summary.

Important: `gpt-5-mini` through Eliza rejected non-default temperature, so Day 4 should use a temperature-compatible model/endpoint. Current plan: Eliza/OpenRouter DeepSeek endpoint with the same OAuth token:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=deepseek/deepseek-v3.1-terminus
```

The code should print sanitized REST request bodies without API keys. The comparison section should stay local and should not add a fourth temperature experiment request.

### Day 5: Model versions

Folder: `day-05-model-versions-kotlin`.

Purpose: same prompt sent to weak, medium, and strong cloud LLM models via Eliza/OpenRouter-compatible REST API. Measure response time, usage tokens, and cost.

Chosen models:

```text
WEAK: google/gemma-3-4b-it (4B)
MEDIUM: meta-llama/llama-3.3-70b-instruct (70B)
STRONG: nousresearch/hermes-3-llama-3.1-405b (405B)
```

Free `:free` variants may hit rate limits; standard paid routes are cheap for short prompts and more reliable for recording.

### Day 6: First agent

Folder: `day-06-first-agent-kotlin`.

Purpose: minimal CLI chat-agent with session history. This is not a one-shot request; the `Agent` class stores `system`, `user`, and `assistant` messages for the current process and sends the whole history in every REST request.

Default model/provider:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

Role: StudyAgent, a concise learning assistant that uses current-session history. Debug mode is available through `AGENT_DEBUG=true` or `/debug`.

### Day 7: Persistent context

Folder: `day-07-persistent-context-kotlin`.

Purpose: day 6 agent plus JSON persistence. The agent stores chat messages in `agent-history.json`, loads them at startup, and continues the dialog after process restart.

Default model/provider:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

History file is user data and should stay ignored. `/clear` resets saved history.

### Day 8: Token accounting

Folder: `day-08-token-accounting-kotlin`.

Purpose: day 7 persistent agent plus token/cost accounting. The agent counts approximate local tokens for the current user message, full `messages` history before each request, model response, and full history after response. If the API returns `usage`, print `prompt_tokens`, `completion_tokens`, `total_tokens`, and `usage.cost`.

Default model/provider:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
```

Demo modes:

```bash
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="short"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="long"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="forgetting"
APP_CONTEXT_LIMIT_TOKENS=800 day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="overflow"
day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-dry-run"
APP_CONTEXT_LIMIT_TOKENS=2000000 CONFIRM_BIG_CONTEXT_SEND=YES_I_UNDERSTAND_THE_COST BIG_CONTEXT_FILE=/absolute/path/to/skills-all.local.md day-08-token-accounting-kotlin/scripts/run-eliza.sh --args="file-send"
```

Important safety rule: `/Users/shitznikita/Downloads/skills-all.md` may be used only for local token/cost dry-run by default. Do not commit it, do not print it fully, and do not send it to API unless the user explicitly confirms via the documented `CONFIRM_BIG_CONTEXT_SEND=YES_I_UNDERSTAND_THE_COST` guard.

Observed real overflow result with copied local file `skills-all.local.md`:

```text
File size: 4664435 bytes
Approximate file tokens: 1177946
Approximate request tokens if sent now: 1178009
API returned HTTP 400:
maximum context length is 131072 tokens, requested about 1303283 tokens
```

Conclusion: in this configuration the model does not silently forget context; the provider/router rejects an over-limit prompt before generation. To demonstrate the course point about old context drifting away, Day 8 now has `forgetting` mode: local history remains complete, but `sliding-window` sends only the newest messages that fit `APP_CONTEXT_LIMIT_TOKENS`, so old facts can disappear from the current REST request. Day 8 output was simplified to the three assignment counters: `Current request`, `Dialog history`, `Model response`.

### Day 9: History compression

Folder: `day-09-history-compression-kotlin`.

Purpose: context management with summary compression. The agent keeps the latest `RECENT_MESSAGES_LIMIT` messages as-is, summarizes older messages with an LLM request, stores summary separately in `context-summary.md`, stores recent messages in `recent-messages.json`, and compares `full_history` vs `compressed_history` on the same final question.

Default model/provider:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
RECENT_MESSAGES_LIMIT=10
```

Run:

```bash
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="compare"
day-09-history-compression-kotlin/scripts/run-eliza.sh --args="multi"
```

The comparison should print the LLM-created summary, full prompt tokens, compressed prompt tokens, token savings, summary request cost, and a simple quality comparison. `multi` runs several scenarios and prints a final results table with total token/cost savings and lost facts. Summary and recent-history files are user data and must remain ignored.

Observed latest `multi` result:

```text
AI course workflow: full 1366, compressed 501, saved 865 / 63.3%, quality 6/7 vs 6/7
Tokyo travel plan: full 1062, compressed 460, saved 602 / 56.7%, quality 9/9 vs 8/9, lost "свободный день"
FocusGarden product brief: full 1167, compressed 495, saved 672 / 57.6%, quality 9/9 vs 9/9
TOTAL: full 3595, compressed 1456, saved 2139 / 59.5%
Summary creation tokens total: 2842
Full answer cost total: $0.001216
Compressed answer cost total: $0.000358
Summary cost total: $0.000505
Compressed total: $0.000863
```

Useful explanation for video/submission: summary compression saved prompt tokens substantially, but it can lose details. In the Tokyo scenario the compressed answer missed the free-day preference, even though the summary contained it.

### Day 10: Context strategies without summary

Folder: `day-10-context-strategies-kotlin`.

Purpose: compare three context management strategies without summary:

```text
sliding: send only the latest N messages
facts: send key-value sticky facts plus latest N messages
branching: keep checkpoint plus independent branch histories
```

The user explicitly asked that Day 10 and future days should not put everything into `Main.kt`. Keep the code split into understandable files/classes.

Current layout:

```text
Main.kt
ContextAgent.kt
ContextStrategies.kt
FactMemoryUpdater.kt
LlmClient.kt
DemoScenario.kt
DemoRunner.kt
InteractiveCli.kt
```

Default model/provider:

```text
LLM_API_URL=https://api.eliza.yandex.net/openrouter/v1/chat/completions
LLM_MODEL=meta-llama/llama-3.3-70b-instruct
RECENT_MESSAGES_LIMIT=6
FACTS_UPDATE_MODE=llm
```

Run:

```bash
./gradlew :day-10-context-strategies-kotlin:build
day-10-context-strategies-kotlin/scripts/run-eliza.sh
day-10-context-strategies-kotlin/scripts/run-eliza.sh --args="interactive"
```

Observed demo result from the first completed Day 10 run:

```text
sliding: prompt 160, quality 7/12, lost name, app goal, backend, categories, limits
facts: prompt 4207 including fact-update calls, quality 12/12, lost none
branching: prompt 493, branches isolated=true
```

Important constraints:

- Do not use summary as one of the Day 10 strategies.
- Facts must be structured key-value memory, not a retelling of the whole dialog.
- Sliding Window must really drop old messages from the API request.
- Branching must keep branch histories independent.
- Keep using direct REST via `java.net.http.HttpClient`.

## Starting A New Day

1. Confirm only essentials if needed: stack, provider, key, interface, task type.
2. Prefer `Kotlin + Eliza + CLI` if user agrees or implies continuity.
3. Start from current `main`; pull first if network/tools allow it.
4. Create branch `day-XX-short-name`.
5. Create folder `day-XX-short-name-kotlin`.
6. Copy the proven Eliza run/setup script pattern from the latest day.
7. Add the subproject to `settings.gradle.kts`.
8. Add root README/AGENTS/course-continuity entries.
9. Implement direct REST via `java.net.http.HttpClient`.
10. Add a day README with plan, checklist, setup, run commands, video scenario, requirement check.
11. Build and run with Eliza if feasible.
12. Commit.
13. Push the branch and create a PR into `main` automatically when allowed.
14. If push/PR is blocked by sandbox policy, missing credentials, missing GitHub CLI, or inability to verify safe/private destination, explain the blocker and provide the exact command or compare URL.

## Video Guidance

For each day, video should show:

- the relevant `Main.kt`;
- API key is not hardcoded (`.env.example`, env loading);
- terminal run command;
- successful output;
- a short spoken explanation matching the assignment.

Never show the real `.env` token in the video.

## Safety

- Never commit `.env`, `.certs/`, tokens, OAuth values, private SSH keys, or real secrets.
- Internal Eliza URLs exist in code/docs, so assume private repo unless user explicitly approves public sharing.
- If pushing or PR creation is blocked by policy, missing credentials, or missing tooling, provide the exact user-run command instead of bypassing.
