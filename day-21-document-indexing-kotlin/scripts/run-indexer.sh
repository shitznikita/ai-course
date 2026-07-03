#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"

LOCAL_ENV="$PROJECT_DIR/.env"
OVERRIDE_VARS=(
  DOCUMENTS_DIR
  INDEX_DIR
  EMBEDDING_BACKEND
  OLLAMA_BASE_URL
  OLLAMA_EMBED_MODEL
  FIXED_CHUNK_TOKENS
  FIXED_CHUNK_OVERLAP
  STRUCTURED_MAX_TOKENS
  RETRIEVAL_TOP_K
  CORPUS_MAX_FILES
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
export EMBEDDING_BACKEND="${EMBEDDING_BACKEND:-hash}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
export OLLAMA_EMBED_MODEL="${OLLAMA_EMBED_MODEL:-nomic-embed-text}"
export FIXED_CHUNK_TOKENS="${FIXED_CHUNK_TOKENS:-450}"
export FIXED_CHUNK_OVERLAP="${FIXED_CHUNK_OVERLAP:-75}"
export STRUCTURED_MAX_TOKENS="${STRUCTURED_MAX_TOKENS:-700}"
export RETRIEVAL_TOP_K="${RETRIEVAL_TOP_K:-5}"
export CORPUS_MAX_FILES="${CORPUS_MAX_FILES:-140}"

cd "$ROOT_DIR"
GRADLE_ARGS=(":day-21-document-indexing-kotlin:run")

if [[ "$#" -gt 0 ]]; then
  if [[ "$1" == --args* ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "$@"
  else
    ./gradlew "${GRADLE_ARGS[@]}" --args="$*"
  fi
else
  ./gradlew "${GRADLE_ARGS[@]}" --args="fixture-demo"
fi
