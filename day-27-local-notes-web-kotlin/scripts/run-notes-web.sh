#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

cd "$ROOT_DIR"
GRADLE_TASK=":day-27-local-notes-web-kotlin:run"

if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "$GRADLE_TASK" "$@"
  else
    ./gradlew "$GRADLE_TASK" --args="$*"
  fi
else
  ./gradlew "$GRADLE_TASK" --args="serve"
fi
