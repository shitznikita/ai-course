#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

LOCAL_ENV="$PROJECT_DIR/.env"
ELIZA_ENV="$ROOT_DIR/day-01-llm-rest-kotlin/.env"
TELEGRAM_ENV="$ROOT_DIR/day-17-telegram-mcp-tool-kotlin/.env"
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
  COURSE_DAY
  STATE_DIR
  PIPELINE_REQUEST
  PIPELINE_MAX_STEPS
  LLM_API_KEY
  LLM_AUTH_SCHEME
  LLM_API_URL
  LLM_MODEL
)
TELEGRAM_ENV_VARS=(
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

is_selected_key() {
  local key="$1"
  shift
  local selected
  for selected in "$@"; do
    if [[ "$key" == "$selected" ]]; then
      return 0
    fi
  done
  return 1
}

trim_value() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"
  printf '%s' "$value"
}

import_selected_env() {
  local file="$1"
  shift
  [[ -f "$file" ]] || return 0

  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="$(trim_value "$line")"
    [[ -z "$line" || "$line" == \#* || "$line" != *=* ]] && continue
    key="$(trim_value "${line%%=*}")"
    is_selected_key "$key" "$@" || continue
    value="$(trim_value "${line#*=}")"
    export "$key=$value"
  done < "$file"
}

reuse_day17_tdlib_paths() {
  [[ -f "$TELEGRAM_ENV" ]] || return 0
  local day17_dir="$ROOT_DIR/day-17-telegram-mcp-tool-kotlin"

  export TDLIB_SESSION_DIR="${TDLIB_SESSION_DIR:-$day17_dir/telegram-session}"
  export TDLIB_FILES_DIR="${TDLIB_FILES_DIR:-$day17_dir/telegram-files}"

  if [[ "$TDLIB_SESSION_DIR" != /* ]]; then
    export TDLIB_SESSION_DIR="$day17_dir/$TDLIB_SESSION_DIR"
  fi
  if [[ "$TDLIB_FILES_DIR" != /* ]]; then
    export TDLIB_FILES_DIR="$day17_dir/$TDLIB_FILES_DIR"
  fi
}

for name in "${OVERRIDE_VARS[@]}"; do
  if [[ -n "${!name+x}" ]]; then
    export "CALLER_HAS_${name}=1"
    export "CALLER_VALUE_${name}=${!name}"
  fi
done

source_env_file "$ELIZA_ENV"
import_selected_env "$TELEGRAM_ENV" "${TELEGRAM_ENV_VARS[@]}"
reuse_day17_tdlib_paths
source_env_file "$LOCAL_ENV"

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
export MCP_SERVER_PORT="${MCP_SERVER_PORT:-3019}"
export MCP_CLIENT_NAME="${MCP_CLIENT_NAME:-ai-course-day-19-pipeline-agent}"
export MCP_TIMEOUT_SECONDS="${MCP_TIMEOUT_SECONDS:-120}"
export TELEGRAM_BACKEND="${TELEGRAM_BACKEND:-fixture}"
export TELEGRAM_CHAT="${TELEGRAM_CHAT:-fixture-course-chat}"
export TELEGRAM_LIMIT="${TELEGRAM_LIMIT:-100}"
export COURSE_DAY="${COURSE_DAY:-auto}"
export STATE_DIR="${STATE_DIR:-state}"
export PIPELINE_REQUEST="${PIPELINE_REQUEST:-Найди обсуждение задания дня курса в Telegram, сделай итог и execution prompt, затем сохрани результат в файл.}"
export PIPELINE_MAX_STEPS="${PIPELINE_MAX_STEPS:-3}"
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
GRADLE_ARGS=("${TRUST_STORE_ARGS[@]}" ":day-19-mcp-tool-composition-kotlin:run")

if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "$@"
  else
    ./gradlew "${GRADLE_ARGS[@]}" --args="$*"
  fi
else
  ./gradlew "${GRADLE_ARGS[@]}" --args="fixture-demo"
fi
