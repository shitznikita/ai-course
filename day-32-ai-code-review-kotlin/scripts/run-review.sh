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
  LLM_TIMEOUT_SECONDS
  LLM_MAX_OUTPUT_TOKENS
  REPOSITORY_ROOT
  GITHUB_REPOSITORY
  PR_NUMBER
  PR_BASE_SHA
  PR_HEAD_SHA
  GITHUB_API_URL
  REVIEW_MAX_CHANGED_FILES
  REVIEW_MAX_FILE_BYTES
  REVIEW_MAX_TOTAL_CHANGED_BYTES
  REVIEW_PROMPT_BYTES
  REVIEW_MAX_BATCHES
  RAG_MAX_FILES
  RAG_MAX_BYTES
  RAG_MAX_FILE_BYTES
  RAG_MAX_CHUNKS
  RAG_CHUNK_LINES
  RAG_EVIDENCE_BYTES
  GITHUB_TOKEN
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

# A caller (notably CI) wins over a developer's local .env.
for name in "${OVERRIDE_VARS[@]}"; do
  if [[ -n "${!name+x}" ]]; then
    export "CALLER_HAS_${name}=1"
    export "CALLER_VALUE_${name}=${!name}"
  fi
done

SHARED_LLM_API_KEY=""
if [[ -f "$SHARED_ENV" ]]; then
  SHARED_LLM_API_KEY="$(
    unset LLM_API_KEY LLM_AUTH_SCHEME LLM_API_URL LLM_MODEL
    set -a
    # shellcheck disable=SC1090
    source "$SHARED_ENV"
    set +a
    printf '%s' "${LLM_API_KEY:-}"
  )"
fi

source_env_file "$LOCAL_ENV"

if [[ -z "${LLM_API_KEY:-}" && -n "$SHARED_LLM_API_KEY" ]]; then
  export LLM_API_KEY="$SHARED_LLM_API_KEY"
fi

for name in "${OVERRIDE_VARS[@]}"; do
  has_name="CALLER_HAS_${name}"
  value_name="CALLER_VALUE_${name}"
  if [[ -n "${!has_name:-}" ]]; then
    export "$name=${!value_name}"
    unset "$has_name" "$value_name"
  fi
done
unset SHARED_LLM_API_KEY

export LLM_AUTH_SCHEME="${LLM_AUTH_SCHEME:-OAuth}"
export LLM_API_URL="${LLM_API_URL:-https://api.eliza.yandex.net/openrouter/v1/chat/completions}"
export LLM_MODEL="${LLM_MODEL:-meta-llama/llama-3.3-70b-instruct}"
export REPOSITORY_ROOT="${REPOSITORY_ROOT:-$ROOT_DIR}"

TRUSTSTORE="$PROJECT_DIR/.certs/yandex-all-cas.jks"
if [[ ! -f "$TRUSTSTORE" && -f "$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" ]]; then
  TRUSTSTORE="$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks"
fi

GRADLE_ARGS=(":day-32-ai-code-review-kotlin:run")
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
