#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CERT_DIR="$PROJECT_DIR/.certs"
CERT_FILE="$CERT_DIR/yandex-all-cas.pem"
TRUST_STORE="$CERT_DIR/yandex-all-cas.jks"
PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"

mkdir -p "$CERT_DIR"
curl -fsSL "https://storage.yandexcloud.net/cloud-certs/CA.pem" -o "$CERT_FILE"

keytool -importcert \
  -noprompt \
  -trustcacerts \
  -alias yandex-root-ca \
  -file "$CERT_FILE" \
  -keystore "$TRUST_STORE" \
  -storepass "$PASSWORD"

echo "Truststore created: $TRUST_STORE"
