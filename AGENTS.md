# Project Instructions

This repository stores AI course assignments. Keep every assignment small, reviewable, and isolated in its own day folder.

## Repository Layout

- `day-01-llm-rest-kotlin/`: Day 1 Kotlin CLI that sends a direct REST request to an LLM API.
- Add future assignments as `day-02-...`, `day-03-...`, and so on.
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
