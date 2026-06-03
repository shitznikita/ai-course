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

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStorePassword=$STORE_PASSWORD"

cd "$REPO_ROOT"
exec ./gradlew :day-01-llm-rest-kotlin:run "$@"
