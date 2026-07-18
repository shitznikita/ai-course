#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(cd -- "$MODULE_DIR/.." && pwd)"

if [[ -f "$MODULE_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$MODULE_DIR/.env"
  set +a
fi

cd "$REPOSITORY_ROOT"
exec ./gradlew :day-34-project-file-assistant-kotlin:run "$@"
