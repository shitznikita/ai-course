---
name: course-continuity
description: Use when continuing this AI course repository after context compaction, starting a new course day, modifying existing day assignments, preparing GitHub PRs, recording/submitting videos, or needing the stable project memory and decisions from earlier days.
---

# Course Continuity

Use this skill as the durable memory for the AI course repo. Keep future work consistent with these decisions unless the user explicitly changes direction.

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

- Default API URL used so far: `https://api.eliza.yandex.net/raw/openai/v1/chat/completions`.
- Auth header: `Authorization: OAuth <token>`.
- Default model: `gpt-5-mini`.
- Eliza/gpt-5-mini rejected:
  - `max_tokens`: use prompt instructions or supported model-specific fields instead.
  - `temperature=0.2`: API said only default value is supported.
- Therefore prefer minimal request body:

```json
{
  "model": "gpt-5-mini",
  "messages": [...]
}
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

## Starting A New Day

1. Confirm only essentials if needed: stack, provider, key, interface, task type.
2. Prefer `Kotlin + Eliza + CLI` if user agrees or implies continuity.
3. Create branch `day-XX-short-name`.
4. Create folder `day-XX-short-name-kotlin`.
5. Copy the proven Eliza run/setup script pattern from the latest day.
6. Add the subproject to `settings.gradle.kts`.
7. Add root README/AGENTS entries.
8. Implement direct REST via `java.net.http.HttpClient`.
9. Add a day README with plan, checklist, setup, run commands, video scenario, requirement check.
10. Build and run with Eliza if feasible.
11. Commit.
12. Push the branch and create a PR into `main` automatically when allowed.
13. If push/PR is blocked by sandbox policy, missing credentials, missing GitHub CLI, or inability to verify safe/private destination, explain the blocker and provide the exact command or compare URL.

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
