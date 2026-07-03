#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

LOCAL_ENV="$PROJECT_DIR/.env"
SHARED_ENV="$ROOT_DIR/day-01-llm-rest-kotlin/.env"
OVERRIDE_VARS=(
  DOCUMENTS_DIR
  INDEX_DIR
  REPORTS_DIR
  CONTROL_QUESTIONS_FILE
  EMBEDDING_BACKEND
  OLLAMA_BASE_URL
  OLLAMA_EMBED_MODEL
  RAG_CHUNK_STRATEGY
  RETRIEVAL_TOP_K_BEFORE
  RERANK_TOP_K_AFTER
  RERANK_MIN_SCORE
  QUERY_REWRITE_MODE
  RERANK_MODE
  RAG_MAX_CONTEXT_TOKENS
  CORPUS_MAX_FILES
  STRUCTURED_MAX_TOKENS
  LLM_API_KEY
  LLM_AUTH_SCHEME
  LLM_API_URL
  LLM_MODEL
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

export DOCUMENTS_DIR="${DOCUMENTS_DIR:-..}"
export INDEX_DIR="${INDEX_DIR:-index}"
export REPORTS_DIR="${REPORTS_DIR:-reports}"
export CONTROL_QUESTIONS_FILE="${CONTROL_QUESTIONS_FILE:-eval/control-questions.json}"
export EMBEDDING_BACKEND="${EMBEDDING_BACKEND:-hash}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
export OLLAMA_EMBED_MODEL="${OLLAMA_EMBED_MODEL:-nomic-embed-text}"
export RAG_CHUNK_STRATEGY="${RAG_CHUNK_STRATEGY:-structured}"
export RETRIEVAL_TOP_K_BEFORE="${RETRIEVAL_TOP_K_BEFORE:-10}"
export RERANK_TOP_K_AFTER="${RERANK_TOP_K_AFTER:-4}"
export RERANK_MIN_SCORE="${RERANK_MIN_SCORE:-0.55}"
export QUERY_REWRITE_MODE="${QUERY_REWRITE_MODE:-local}"
export RERANK_MODE="${RERANK_MODE:-heuristic}"
export RAG_MAX_CONTEXT_TOKENS="${RAG_MAX_CONTEXT_TOKENS:-2200}"
export CORPUS_MAX_FILES="${CORPUS_MAX_FILES:-400}"
export STRUCTURED_MAX_TOKENS="${STRUCTURED_MAX_TOKENS:-700}"
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
GRADLE_ARGS=("${TRUST_STORE_ARGS[@]}" ":day-23-reranking-filtering-kotlin:run")

if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "$@"
  else
    ./gradlew "${GRADLE_ARGS[@]}" --args="$*"
  fi
else
  ./gradlew "${GRADLE_ARGS[@]}" --args="fixture-demo"
fi
