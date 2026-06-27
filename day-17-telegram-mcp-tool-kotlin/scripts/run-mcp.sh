#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

LOCAL_ENV="$PROJECT_DIR/.env"
SHARED_ENV="$ROOT_DIR/day-01-llm-rest-kotlin/.env"
OVERRIDE_VARS=(
  MCP_SERVER_HOST
  MCP_SERVER_PORT
  MCP_CLIENT_NAME
  MCP_TIMEOUT_SECONDS
  TELEGRAM_BACKEND
  TELEGRAM_CHAT
  TELEGRAM_LIMIT
  TELEGRAM_API_ID
  TELEGRAM_API_HASH
  TELEGRAM_PHONE
  TELEGRAM_CODE
  TELEGRAM_PASSWORD
  TELEGRAM_RESEND_CODE
  TELEGRAM_QR_WAIT_SECONDS
  TDLIB_LIBRARY_PATH
  TDLIB_SESSION_DIR
  TDLIB_FILES_DIR
  LLM_API_KEY
  LLM_AUTH_SCHEME
  LLM_API_URL
  LLM_MODEL
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
elif [[ -f "$SHARED_ENV" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$SHARED_ENV"
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

export MCP_SERVER_HOST="${MCP_SERVER_HOST:-127.0.0.1}"
export MCP_SERVER_PORT="${MCP_SERVER_PORT:-3017}"
export MCP_CLIENT_NAME="${MCP_CLIENT_NAME:-ai-course-day-17-agent}"
export MCP_TIMEOUT_SECONDS="${MCP_TIMEOUT_SECONDS:-30}"
export TELEGRAM_BACKEND="${TELEGRAM_BACKEND:-fixture}"
export TELEGRAM_CHAT="${TELEGRAM_CHAT:-fixture-course-chat}"
export TELEGRAM_LIMIT="${TELEGRAM_LIMIT:-10}"
export LLM_AUTH_SCHEME="${LLM_AUTH_SCHEME:-OAuth}"
export LLM_API_URL="${LLM_API_URL:-https://api.eliza.yandex.net/openrouter/v1/chat/completions}"
export LLM_MODEL="${LLM_MODEL:-meta-llama/llama-3.3-70b-instruct}"

TRUST_STORE_PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"
TRUST_STORE_ARGS=()
if [[ -f "$PROJECT_DIR/.certs/yandex-all-cas.jks" ]]; then
  TRUST_STORE_ARGS=("-PelizaTrustStore=$PROJECT_DIR/.certs/yandex-all-cas.jks" "-PelizaTrustStorePassword=$TRUST_STORE_PASSWORD")
elif [[ -f "$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" ]]; then
  TRUST_STORE_ARGS=("-PelizaTrustStore=$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" "-PelizaTrustStorePassword=$TRUST_STORE_PASSWORD")
fi

cd "$ROOT_DIR"
GRADLE_ARGS=("${TRUST_STORE_ARGS[@]}" ":day-17-telegram-mcp-tool-kotlin:run")

if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "$@"
  else
    ./gradlew "${GRADLE_ARGS[@]}" --args="$*"
  fi
else
  ./gradlew "${GRADLE_ARGS[@]}" --args="fixture-demo"
fi
