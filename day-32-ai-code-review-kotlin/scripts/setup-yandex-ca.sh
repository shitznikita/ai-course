#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$PROJECT_DIR/.certs"
CA_PEM="$CERT_DIR/yandex-all-cas.pem"
TRUSTSTORE="$CERT_DIR/yandex-all-cas.jks"
STORE_PASSWORD="${YANDEX_TRUSTSTORE_PASSWORD:-changeit}"
CA_URL="${YANDEX_CA_URL:-https://crls.yandex.net/allCAs.pem}"
KEYTOOL_BIN="${KEYTOOL:-keytool}"

mkdir -p "$CERT_DIR"
echo "Downloading Yandex CA bundle..."
curl -fsSL "$CA_URL" -o "$CA_PEM"

rm -f "$TRUSTSTORE" "$CERT_DIR"/ca-*.pem
awk -v cert_dir="$CERT_DIR" '
  /-----BEGIN CERTIFICATE-----/ {
    n++; file = sprintf("%s/ca-%03d.pem", cert_dir, n)
  }
  file != "" { print > file }
  /-----END CERTIFICATE-----/ { close(file); file = "" }
' "$CA_PEM"

cert_count="$(find "$CERT_DIR" -name 'ca-*.pem' -type f | wc -l | tr -d ' ')"
if [[ "$cert_count" == "0" ]]; then
  echo "No certificates found in $CA_PEM" >&2
  exit 1
fi

for cert_file in "$CERT_DIR"/ca-*.pem; do
  "$KEYTOOL_BIN" -importcert -noprompt -trustcacerts \
    -alias "yandex-$(basename "$cert_file" .pem)" \
    -file "$cert_file" -keystore "$TRUSTSTORE" -storepass "$STORE_PASSWORD" >/dev/null
done
rm -f "$CERT_DIR"/ca-*.pem

echo "Truststore created: $TRUSTSTORE"
