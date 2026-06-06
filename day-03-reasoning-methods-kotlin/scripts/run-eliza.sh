#!/usr/bin/env bash
set -euo pipefail

DAY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$DAY_DIR/.." && pwd)"
TRUSTSTORE="$DAY_DIR/.certs/yandex-all-cas.jks"
STORE_PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"

if [[ ! -f "$TRUSTSTORE" ]]; then
    echo "Truststore not found: $TRUSTSTORE" >&2
    echo "Run first: $DAY_DIR/scripts/setup-yandex-ca.sh" >&2
    exit 1
fi

if [[ ! -f "$DAY_DIR/.env" && -f "$REPO_ROOT/day-01-llm-rest-kotlin/.env" ]]; then
    echo "Using .env from day-01-llm-rest-kotlin"
    export LLM_ENV_FILE="$REPO_ROOT/day-01-llm-rest-kotlin/.env"
fi

cd "$REPO_ROOT"
exec ./gradlew \
    -PelizaTrustStore="$TRUSTSTORE" \
    -PelizaTrustStorePassword="$STORE_PASSWORD" \
    :day-03-reasoning-methods-kotlin:run \
    "$@"
