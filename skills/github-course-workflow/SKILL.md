---
name: github-course-workflow
description: Use when working in this AI course repository with GitHub, branches, pull requests, commits, repository hygiene, assignment folders, README updates, or safe publishing of course code.
---

# GitHub Course Workflow

Use this workflow for AI course tasks in this repository.

## Before Editing

1. Check the current branch and status:

```bash
git status --short
git branch --show-current
```

2. Keep user changes intact. Do not reset, checkout, or delete files unless explicitly requested.
3. For a new assignment, create a dedicated folder such as `day-02-task-name/`.

## Branches And PRs

- Use `main` as the stable branch.
- Use one branch per assignment or substantial revision:

```bash
git checkout main
git pull
git checkout -b day-02-task-name
```

- For day-assignment changes requested by the user, do not stop after a local commit. After verification, push the branch and create a pull request automatically whenever credentials/tools allow it.
- The user will review/approve/merge the pull request on GitHub.
- Do not merge PRs automatically unless the user explicitly asks.
- If push or PR creation is blocked by sandbox policy, missing credentials, missing GitHub CLI, or inability to verify the destination is safe/private, explain the blocker and give the exact command the user should run.

## Commits

Before committing:

```bash
git status --short --ignored
```

Confirm that `.env`, `.certs/`, build outputs, IDE files, and secrets are ignored.

Commit messages should be short and concrete:

```bash
git add .
git commit -m "Add day 2 ..."
```

## Push And Pull Request

The remote should use SSH:

```bash
git remote -v
```

Expected shape:

```text
origin  git@github.com:shitznikita/ai-course.git
```

If needed:

```bash
git remote set-url origin git@github.com:shitznikita/ai-course.git
ssh -T git@github.com
```

After committing a day-assignment change, push the current branch:

```bash
git push -u origin <branch>
```

Then create a pull request into `main`.

Preferred, if GitHub CLI is installed and authenticated:

```bash
gh pr create \
  --base main \
  --head <branch> \
  --title "Add day X ..." \
  --body "Summary:
- ...

Verification:
- ..."
```

If `gh` is not installed, either install/authenticate it with user approval or provide the GitHub compare URL:

```text
https://github.com/shitznikita/ai-course/compare/main...<branch>?expand=1
```

When creating PRs, include:

- What changed.
- How it was verified.
- Any known caveats, such as unsupported API parameters.
- Reminder that `.env` and `.certs/` were not committed if relevant.

## Root Skill Ideas

Good additions to the root project instructions:

- Course folder conventions and naming.
- Branch and PR rules.
- Commit message style.
- Security rules for API keys, OAuth tokens, `.env`, and generated certificates.
- Verification commands for each assignment.
- README checklist for every day.
- Submission checklist: code link, video link, model/API used, and expected output.
- Provider notes: Eliza, DeepSeek, OpenRouter, Hugging Face, GigaChat.

Avoid putting secrets, internal credentials, or long vendor docs into the root instructions.
