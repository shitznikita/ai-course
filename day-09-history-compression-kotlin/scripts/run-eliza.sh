#!/usr/bin/env bash
set -euo pipefail

DAY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$DAY_DIR/.." && pwd)"
STORE_PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"

if [[ -f "$DAY_DIR/.certs/yandex-all-cas.jks" ]]; then
    TRUSTSTORE="$DAY_DIR/.certs/yandex-all-cas.jks"
elif [[ -f "$REPO_ROOT/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks" ]]; then
    TRUSTSTORE="$REPO_ROOT/day-01-llm-rest-kotlin/.certs/yandex-all-cas.jks"
else
    TRUSTSTORE="$DAY_DIR/.certs/yandex-all-cas.jks"
fi

if [[ ! -f "$TRUSTSTORE" ]]; then
    echo "Truststore not found: $TRUSTSTORE" >&2
    echo "Run first: $DAY_DIR/scripts/setup-yandex-ca.sh" >&2
    exit 1
fi

if [[ ! -f "$DAY_DIR/.env" && -f "$REPO_ROOT/day-01-llm-rest-kotlin/.env" ]]; then
    echo "Using API key from day-01-llm-rest-kotlin/.env"
    export LLM_ENV_FILE="$REPO_ROOT/day-01-llm-rest-kotlin/.env"
fi

export LLM_AUTH_SCHEME="${LLM_AUTH_SCHEME:-OAuth}"
export LLM_API_URL="${LLM_API_URL:-https://api.eliza.yandex.net/openrouter/v1/chat/completions}"
export LLM_MODEL="${LLM_MODEL:-meta-llama/llama-3.3-70b-instruct}"
export RECENT_MESSAGES_LIMIT="${RECENT_MESSAGES_LIMIT:-10}"
export RECENT_MESSAGES_FILE="${RECENT_MESSAGES_FILE:-recent-messages.json}"
export SUMMARY_FILE="${SUMMARY_FILE:-context-summary.md}"

cd "$REPO_ROOT"
exec ./gradlew \
    --console=plain \
    --quiet \
    -PelizaTrustStore="$TRUSTSTORE" \
    -PelizaTrustStorePassword="$STORE_PASSWORD" \
    :day-09-history-compression-kotlin:run \
    "$@"
