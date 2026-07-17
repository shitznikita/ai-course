#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
LOCAL_ENV="$PROJECT_DIR/.env"
SHARED_ENV="$ROOT_DIR/day-01-llm-rest-kotlin/.env"

OVERRIDE_VARS=(
  LLM_API_KEY
  LLM_AUTH_SCHEME
  LLM_API_URL
  LLM_MODEL
  LLM_CONNECT_TIMEOUT_SECONDS
  LLM_REQUEST_TIMEOUT_SECONDS
  LLM_MAX_RESPONSE_BYTES
  LLM_MAX_OUTPUT_TOKENS
  MCP_HOST
  MCP_PORT
  MCP_CONNECT_TIMEOUT_MILLIS
  MCP_REQUEST_TIMEOUT_MILLIS
  SUPPORT_FIXTURE_PATH
  SUPPORT_KNOWLEDGE_DIR
  SUPPORT_RAG_INDEX_PATH
  SUPPORT_EVAL_PATH
  RAG_TOP_K
  RAG_MIN_RELEVANCE
  RAG_EMBEDDING_DIMENSIONS
  RAG_MAX_CHUNK_CHARS
  RAG_MAX_EVIDENCE_CHARS
  PROMPT_MAX_CHARS
  QUESTION_MAX_CHARS
  CHAT_HISTORY_TURNS
  REPOSITORY_ROOT
)

source_env_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$file"
    set +a
  fi
}

# Caller-provided values win over the module's trusted local .env.
for name in "${OVERRIDE_VARS[@]}"; do
  if [[ -n "${!name+x}" ]]; then
    export "CALLER_HAS_${name}=1"
    export "CALLER_VALUE_${name}=${!name}"
  fi
done

source_env_file "$LOCAL_ENV"

for name in "${OVERRIDE_VARS[@]}"; do
  has_name="CALLER_HAS_${name}"
  value_name="CALLER_VALUE_${name}"
  if [[ -n "${!has_name:-}" ]]; then
    export "$name=${!value_name}"
    unset "$has_name" "$value_name"
  fi
done

export LLM_AUTH_SCHEME="${LLM_AUTH_SCHEME:-OAuth}"
REVIEWED_LLM_API_URL="https://api.eliza.yandex.net/openrouter/v1/chat/completions"
export LLM_API_URL="${LLM_API_URL:-$REVIEWED_LLM_API_URL}"
if [[ "$LLM_API_URL" != "$REVIEWED_LLM_API_URL" ]]; then
  echo "LLM_API_URL must be the reviewed Eliza endpoint: $REVIEWED_LLM_API_URL" >&2
  exit 1
fi

if [[ -z "${LLM_API_KEY:-}" && -f "$SHARED_ENV" ]]; then
  SHARED_LLM_API_KEY="$(
    unset LLM_API_KEY LLM_AUTH_SCHEME LLM_API_URL LLM_MODEL
    set -a
    # shellcheck disable=SC1090
    source "$SHARED_ENV"
    set +a
    printf '%s' "${LLM_API_KEY:-}"
  )"
  if [[ -n "$SHARED_LLM_API_KEY" ]]; then
    export LLM_API_KEY="$SHARED_LLM_API_KEY"
  fi
  unset SHARED_LLM_API_KEY
fi

export LLM_MODEL="${LLM_MODEL:-meta-llama/llama-3.3-70b-instruct}"
export MCP_HOST="${MCP_HOST:-127.0.0.1}"
export MCP_PORT="${MCP_PORT:-3033}"
export REPOSITORY_ROOT="${REPOSITORY_ROOT:-$ROOT_DIR}"

TRUSTSTORE="$PROJECT_DIR/.certs/yandex-all-cas.jks"
if [[ ! -f "$TRUSTSTORE" && -f "$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" ]]; then
  TRUSTSTORE="$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks"
fi

GRADLE_ARGS=(":day-33-support-assistant-kotlin:run")
if [[ -f "$TRUSTSTORE" ]]; then
  GRADLE_ARGS=(
    "-PelizaTrustStore=$TRUSTSTORE"
    "-PelizaTrustStorePassword=${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"
    "${GRADLE_ARGS[@]}"
  )
fi

cd "$ROOT_DIR"
if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    exec ./gradlew --console=plain "${GRADLE_ARGS[@]}" "$@"
  else
    exec ./gradlew --console=plain "${GRADLE_ARGS[@]}" --args="$*"
  fi
fi

exec ./gradlew --console=plain "${GRADLE_ARGS[@]}" --args="fixture-demo"
