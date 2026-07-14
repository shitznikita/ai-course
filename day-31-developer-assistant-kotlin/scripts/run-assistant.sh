#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
LOCAL_ENV="$PROJECT_DIR/.env"
OVERRIDE_VARS=(
  PROJECT_ROOT
  MCP_HOST
  MCP_PORT
  MCP_TIMEOUT_SECONDS
  OLLAMA_BASE_URL
  OLLAMA_MODEL
  OLLAMA_EMBED_MODEL
  OLLAMA_REQUEST_TIMEOUT_SECONDS
  OLLAMA_CONTEXT_LENGTH
  OLLAMA_MAX_OUTPUT_TOKENS
  PROMPT_RESERVE_TOKENS
  EMBEDDING_BACKEND
  RAG_INDEX_FILE
  RAG_TOP_K
  RAG_CANDIDATE_COUNT
  RAG_MIN_RELEVANCE
  RAG_CHUNK_MAX_TOKENS
  MAX_DOCUMENT_BYTES
  MAX_CONTEXT_TOKENS
  MAX_FILE_LIST
)

for name in "${OVERRIDE_VARS[@]}"; do
  if [[ -n "${!name+x}" ]]; then
    export "CALLER_HAS_${name}=1"
    export "CALLER_VALUE_${name}=${!name}"
  fi
done

if [[ -f "$LOCAL_ENV" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
  set +a
fi

for name in "${OVERRIDE_VARS[@]}"; do
  has_name="CALLER_HAS_${name}"
  value_name="CALLER_VALUE_${name}"
  if [[ -n "${!has_name:-}" ]]; then
    export "$name=${!value_name}"
    unset "$has_name"
    unset "$value_name"
  fi
done

export PROJECT_ROOT="${PROJECT_ROOT:-$ROOT_DIR}"
export MCP_HOST="${MCP_HOST:-127.0.0.1}"
export MCP_PORT="${MCP_PORT:-3031}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
export OLLAMA_MODEL="${OLLAMA_MODEL:-qwen3:4b}"
export EMBEDDING_BACKEND="${EMBEDDING_BACKEND:-hash}"

if [[ "$MCP_HOST" != "127.0.0.1" && "$MCP_HOST" != "localhost" && "$MCP_HOST" != "::1" ]]; then
  echo "run-assistant.sh only permits a loopback MCP_HOST." >&2
  exit 1
fi

case "$OLLAMA_BASE_URL" in
  http://127.0.0.1:*|http://localhost:*|http://\[::1\]:*) ;;
  *)
    echo "run-assistant.sh only permits loopback Ollama." >&2
    exit 1
    ;;
esac

cd "$ROOT_DIR"
if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    exec ./gradlew :day-31-developer-assistant-kotlin:run "$@"
  else
    exec ./gradlew :day-31-developer-assistant-kotlin:run --args="$*"
  fi
else
  exec ./gradlew :day-31-developer-assistant-kotlin:run --args="chat"
fi
