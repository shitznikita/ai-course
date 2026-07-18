#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(cd -- "$MODULE_DIR/.." && pwd)"
MODULE_NAME="day-35-ai-release-prep-kotlin"

BASE_ENV=("HOME=${HOME:?HOME is required}" "PATH=${PATH:?PATH is required}")
[[ -n "${TERM:-}" ]] && BASE_ENV+=("TERM=$TERM")
[[ -n "${LANG:-}" ]] && BASE_ENV+=("LANG=$LANG")
[[ -n "${LC_ALL:-}" ]] && BASE_ENV+=("LC_ALL=$LC_ALL")
[[ -n "${TMPDIR:-}" ]] && BASE_ENV+=("TMPDIR=$TMPDIR")

cd "$REPOSITORY_ROOT"
env -i "${BASE_ENV[@]}" ./gradlew --no-daemon --console=plain --quiet ":$MODULE_NAME:installDist"

APP_ENV=("${BASE_ENV[@]}")
[[ -n "${LLM_API_KEY+x}" ]] && APP_ENV+=("LLM_API_KEY=$LLM_API_KEY")
[[ -n "${LLM_API_URL+x}" ]] && APP_ENV+=("LLM_API_URL=$LLM_API_URL")
[[ -n "${LLM_MODEL+x}" ]] && APP_ENV+=("LLM_MODEL=$LLM_MODEL")
[[ -n "${LLM_AUTH_SCHEME+x}" ]] && APP_ENV+=("LLM_AUTH_SCHEME=$LLM_AUTH_SCHEME")
JAVA_BIN="$(command -v java)"
TRUSTSTORE="$MODULE_DIR/.certs/yandex-all-cas.jks"
if [[ -f "$TRUSTSTORE" ]]; then
  exec env -i "${APP_ENV[@]}" "$JAVA_BIN" \
    "-Djavax.net.ssl.trustStore=$TRUSTSTORE" \
    "-Djavax.net.ssl.trustStorePassword=${YANDEX_TRUSTSTORE_PASSWORD:-changeit}" \
    -cp "$MODULE_DIR/build/install/$MODULE_NAME/lib/*" ru.ai.course.day35.releaseprep.MainKt "$@"
fi

exec env -i "${APP_ENV[@]}" "$JAVA_BIN" \
  -cp "$MODULE_DIR/build/install/$MODULE_NAME/lib/*" ru.ai.course.day35.releaseprep.MainKt "$@"
