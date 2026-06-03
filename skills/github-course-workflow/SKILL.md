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

- Open one pull request per assignment. Merge after verification and README updates.

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

## Push Over SSH

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

Then push:

```bash
git push -u origin <branch>
```

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
