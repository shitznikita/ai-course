#!/usr/bin/env bash
set -euo pipefail

DAY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$DAY_DIR/.certs"
CERT_FILE="$CERT_DIR/yandex-all-cas.pem"
TRUSTSTORE="$CERT_DIR/yandex-all-cas.jks"
STORE_PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"

mkdir -p "$CERT_DIR"

echo "Downloading Yandex CA bundle..."
curl -fsSL "https://storage.yandexcloud.net/cloud-certs/CA.pem" -o "$CERT_FILE"

rm -f "$TRUSTSTORE"
keytool \
    -importcert \
    -noprompt \
    -trustcacerts \
    -alias yandex-all-cas \
    -file "$CERT_FILE" \
    -keystore "$TRUSTSTORE" \
    -storepass "$STORE_PASSWORD"

echo "Truststore created: $TRUSTSTORE"
