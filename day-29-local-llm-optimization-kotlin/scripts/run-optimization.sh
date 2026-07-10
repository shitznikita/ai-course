#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
LOCAL_ENV="$PROJECT_DIR/.env"

OVERRIDE_VARS=(
  WEEK6_INDEX_FILE OLLAMA_BASE_URL OLLAMA_Q4_MODEL OLLAMA_Q8_MODEL
  OLLAMA_EMBED_MODEL OLLAMA_REQUEST_TIMEOUT_SECONDS OLLAMA_KEEP_ALIVE
  RETRIEVAL_TOP_K RAG_MAX_CONTEXT_TOKENS BENCHMARK_QUESTIONS_FILE BENCHMARK_RUNS REPORTS_DIR
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
    unset "$has_name" "$value_name"
  fi
done

export WEEK6_INDEX_FILE="${WEEK6_INDEX_FILE:-../day-21-document-indexing-kotlin/index/structured-index.json}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
export OLLAMA_Q4_MODEL="${OLLAMA_Q4_MODEL:-qwen3:14b}"
export OLLAMA_Q8_MODEL="${OLLAMA_Q8_MODEL:-qwen3:14b-q8_0}"
export OLLAMA_EMBED_MODEL="${OLLAMA_EMBED_MODEL:-nomic-embed-text}"
export OLLAMA_REQUEST_TIMEOUT_SECONDS="${OLLAMA_REQUEST_TIMEOUT_SECONDS:-300}"
export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:-5m}"
export RETRIEVAL_TOP_K="${RETRIEVAL_TOP_K:-4}"
export RAG_MAX_CONTEXT_TOKENS="${RAG_MAX_CONTEXT_TOKENS:-2200}"
export BENCHMARK_QUESTIONS_FILE="${BENCHMARK_QUESTIONS_FILE:-eval/benchmark-questions.json}"
export BENCHMARK_RUNS="${BENCHMARK_RUNS:-3}"
export REPORTS_DIR="${REPORTS_DIR:-reports}"

cd "$ROOT_DIR"
if [[ "$#" -gt 0 && "$1" == --args* ]]; then
  ./gradlew :day-29-local-llm-optimization-kotlin:run "$@"
elif [[ "$#" -gt 0 ]]; then
  ./gradlew :day-29-local-llm-optimization-kotlin:run --args="$*"
else
  ./gradlew :day-29-local-llm-optimization-kotlin:run --args="benchmark"
fi
