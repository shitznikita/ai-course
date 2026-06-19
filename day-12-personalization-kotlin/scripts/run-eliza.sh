#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

LOCAL_ENV="$PROJECT_DIR/.env"
SHARED_ENV="$ROOT_DIR/day-01-llm-rest-kotlin/.env"

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
else
  echo "No .env found. Copy .env.example or reuse day-01-llm-rest-kotlin/.env." >&2
  exit 1
fi

export LLM_AUTH_SCHEME="${LLM_AUTH_SCHEME:-OAuth}"
export LLM_API_URL="${LLM_API_URL:-https://api.eliza.yandex.net/openrouter/v1/chat/completions}"
export LLM_MODEL="${LLM_MODEL:-meta-llama/llama-3.3-70b-instruct}"
export MEMORY_ROOT="${MEMORY_ROOT:-memory}"
export PROFILES_ROOT="${PROFILES_ROOT:-profiles}"
export SHORT_TERM_MESSAGES_LIMIT="${SHORT_TERM_MESSAGES_LIMIT:-8}"

TRUST_STORE_PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"
if [[ -f "$PROJECT_DIR/.certs/yandex-all-cas.jks" ]]; then
  TRUST_STORE="$PROJECT_DIR/.certs/yandex-all-cas.jks"
elif [[ -f "$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" ]]; then
  TRUST_STORE="$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks"
else
  TRUST_STORE="$PROJECT_DIR/.certs/yandex-all-cas.jks"
fi

if [[ ! -f "$TRUST_STORE" ]]; then
  echo "Truststore not found: $TRUST_STORE" >&2
  echo "Run first: $PROJECT_DIR/scripts/setup-yandex-ca.sh" >&2
  exit 1
fi

GRADLE_ARGS=(":day-12-personalization-kotlin:run")
GRADLE_ARGS=("-PelizaTrustStore=$TRUST_STORE" "-PelizaTrustStorePassword=$TRUST_STORE_PASSWORD" "${GRADLE_ARGS[@]}")

cd "$ROOT_DIR"
if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "$@"
  else
    ./gradlew "${GRADLE_ARGS[@]}" --args="$*"
  fi
else
  ./gradlew "${GRADLE_ARGS[@]}"
fi
