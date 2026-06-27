#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

LOCAL_ENV="$PROJECT_DIR/.env"

if [[ -f "$LOCAL_ENV" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
  set +a
fi

export MCP_SERVER_URL="${MCP_SERVER_URL:-https://mcp.deepwiki.com/mcp}"
export MCP_CLIENT_NAME="${MCP_CLIENT_NAME:-ai-course-day-16-mcp-client}"
export MCP_TIMEOUT_SECONDS="${MCP_TIMEOUT_SECONDS:-30}"

cd "$ROOT_DIR"
GRADLE_ARGS=(":day-16-mcp-connection-kotlin:run")

if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "$@"
  else
    ./gradlew "${GRADLE_ARGS[@]}" --args="$*"
  fi
else
  ./gradlew "${GRADLE_ARGS[@]}"
fi
