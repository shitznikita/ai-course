#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

LOCAL_ENV="$PROJECT_DIR/.env"
SHARED_ENV="$ROOT_DIR/day-01-llm-rest-kotlin/.env"
OVERRIDE_VARS=(
  WEEK6_INDEX_FILE
  OLLAMA_BASE_URL
  OLLAMA_MODEL
  OLLAMA_EMBED_MODEL
  OLLAMA_REQUEST_TIMEOUT_SECONDS
  OLLAMA_KEEP_ALIVE
  RETRIEVAL_TOP_K
  RAG_MAX_CONTEXT_TOKENS
  BENCHMARK_QUESTIONS_FILE
  BENCHMARK_RUNS
  REPORTS_DIR
  LLM_API_KEY
  LLM_AUTH_SCHEME
  LLM_API_URL
  LLM_MODEL
  CLOUD_TEMPERATURE
  CLOUD_REQUEST_TIMEOUT_SECONDS
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

for name in "${OVERRIDE_VARS[@]}"; do
  if [[ -n "${!name+x}" ]]; then
    export "CALLER_HAS_${name}=1"
    export "CALLER_VALUE_${name}=${!name}"
  fi
done

source_env_file "$SHARED_ENV"
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

export WEEK6_INDEX_FILE="${WEEK6_INDEX_FILE:-../day-21-document-indexing-kotlin/index/structured-index.json}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
export OLLAMA_MODEL="${OLLAMA_MODEL:-qwen3:14b}"
export OLLAMA_EMBED_MODEL="${OLLAMA_EMBED_MODEL:-nomic-embed-text}"
export OLLAMA_REQUEST_TIMEOUT_SECONDS="${OLLAMA_REQUEST_TIMEOUT_SECONDS:-300}"
export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:-5m}"
export RETRIEVAL_TOP_K="${RETRIEVAL_TOP_K:-4}"
export RAG_MAX_CONTEXT_TOKENS="${RAG_MAX_CONTEXT_TOKENS:-2200}"
export BENCHMARK_QUESTIONS_FILE="${BENCHMARK_QUESTIONS_FILE:-eval/benchmark-questions.json}"
export BENCHMARK_RUNS="${BENCHMARK_RUNS:-3}"
export REPORTS_DIR="${REPORTS_DIR:-reports}"
export LLM_AUTH_SCHEME="${LLM_AUTH_SCHEME:-OAuth}"
export LLM_API_URL="${LLM_API_URL:-https://api.eliza.yandex.net/openrouter/v1/chat/completions}"
export LLM_MODEL="${LLM_MODEL:-meta-llama/llama-3.3-70b-instruct}"
export CLOUD_REQUEST_TIMEOUT_SECONDS="${CLOUD_REQUEST_TIMEOUT_SECONDS:-300}"

TRUST_STORE_PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"
TRUST_STORE_ARGS=()
if [[ -f "$PROJECT_DIR/.certs/yandex-all-cas.jks" ]]; then
  TRUST_STORE_ARGS=("-PelizaTrustStore=$PROJECT_DIR/.certs/yandex-all-cas.jks" "-PelizaTrustStorePassword=$TRUST_STORE_PASSWORD")
elif [[ -f "$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" ]]; then
  TRUST_STORE_ARGS=("-PelizaTrustStore=$ROOT_DIR/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" "-PelizaTrustStorePassword=$TRUST_STORE_PASSWORD")
fi

cd "$ROOT_DIR"
GRADLE_ARGS=("${TRUST_STORE_ARGS[@]}" ":day-28-local-rag-kotlin:run")

if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "$@"
  else
    ./gradlew "${GRADLE_ARGS[@]}" --args="$*"
  fi
else
  ./gradlew "${GRADLE_ARGS[@]}" --args="benchmark"
fi
