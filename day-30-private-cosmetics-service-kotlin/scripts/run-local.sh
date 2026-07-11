#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
LOCAL_ENV="$PROJECT_DIR/.env"

if [[ -f "$LOCAL_ENV" ]]; then
  set -a
  # The local .env is user-controlled and intentionally ignored by git.
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
  set +a
fi

export APP_HOST="${APP_HOST:-127.0.0.1}"
export APP_PORT="${APP_PORT:-8787}"
export APP_ALLOW_INSECURE_NO_AUTH="${APP_ALLOW_INSECURE_NO_AUTH:-false}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
export OLLAMA_MODEL="${OLLAMA_MODEL:-qwen3:4b}"
export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-1}"
export OLLAMA_NUM_PARALLEL="${OLLAMA_NUM_PARALLEL:-1}"
export OLLAMA_MAX_QUEUE="${OLLAMA_MAX_QUEUE:-2}"
export OLLAMA_CONTEXT_LENGTH="${OLLAMA_CONTEXT_LENGTH:-8192}"
MODE="${1:-serve}"

if [[ "$APP_HOST" != "127.0.0.1" && "$APP_HOST" != "::1" ]]; then
  echo "run-local.sh only permits a loopback APP_HOST." >&2
  exit 1
fi

if [[ "$OLLAMA_BASE_URL" != "http://127.0.0.1:11434" && "$OLLAMA_BASE_URL" != "http://localhost:11434" ]]; then
  echo "run-local.sh only permits loopback Ollama." >&2
  exit 1
fi

if [[ -z "${APP_API_TOKEN:-}" ]]; then
  if [[ "$MODE" == "serve" && "$APP_ALLOW_INSECURE_NO_AUTH" != "true" ]]; then
    echo "Set APP_API_TOKEN in day-30-private-cosmetics-service-kotlin/.env." >&2
    echo "For loopback-only development, explicitly set APP_ALLOW_INSECURE_NO_AUTH=true." >&2
    exit 1
  fi
elif [[ "$MODE" == "serve" ]] && (( ${#APP_API_TOKEN} < 24 )); then
  echo "APP_API_TOKEN is too short for local use." >&2
  exit 1
else
  export APP_API_TOKEN
fi

cd "$ROOT_DIR"
if [[ "$#" -gt 0 ]]; then
  exec ./gradlew :day-30-private-cosmetics-service-kotlin:run --args="$*"
else
  exec ./gradlew :day-30-private-cosmetics-service-kotlin:run --args="serve"
fi
