#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${APP_URL:-http://127.0.0.1:8787}"
ATTEMPTS="${RATE_LIMIT_TEST_ATTEMPTS:-15}"

usage() {
  cat <<'EOF'
Usage: ./scripts/rate-limit-test.sh [--attempts N] [--base-url URL]

Verify application rate limiting without invoking the LLM. The probe sends an
authenticated but incomplete JSON body, so accepted attempts fail validation
cheaply and the rate limiter eventually returns HTTP 429 with Retry-After.

Set APP_API_TOKEN or enter it at the hidden prompt. Running this intentionally
consumes the current rate-limit window; wait for Retry-After before deep tests.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --attempts)
      [[ "$#" -ge 2 ]] || { echo "Missing value for --attempts" >&2; exit 2; }
      ATTEMPTS="$2"
      shift 2
      ;;
    --base-url)
      [[ "$#" -ge 2 ]] || { echo "Missing value for --base-url" >&2; exit 2; }
      BASE_URL="${2%/}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! "$ATTEMPTS" =~ ^[0-9]+$ ]] || (( ATTEMPTS < 2 || ATTEMPTS > 1000 )); then
  echo '--attempts must be between 2 and 1000.' >&2
  exit 2
fi
command -v curl >/dev/null 2>&1 || { echo 'Missing command: curl' >&2; exit 1; }

if [[ -z "${APP_API_TOKEN:-}" ]]; then
  if [[ ! -t 0 ]]; then
    echo 'Set APP_API_TOKEN or run interactively.' >&2
    exit 1
  fi
  read -r -s -p 'API token (input is hidden and not stored): ' APP_API_TOKEN
  echo
fi
if (( ${#APP_API_TOKEN} < 24 )); then
  echo 'APP_API_TOKEN is unexpectedly short.' >&2
  exit 1
fi

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

found_429=false
retry_after=''
for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
  headers_file="$work_dir/headers-$attempt.txt"
  status="$(
    printf 'Authorization: Bearer %s\nContent-Type: application/json\n' "$APP_API_TOKEN" |
      curl --silent --show-error \
        --max-time 15 \
        --request POST \
        --header @- \
        --data '{}' \
        --dump-header "$headers_file" \
        --output /dev/null \
        --write-out '%{http_code}' \
        "$BASE_URL/api/analyze/text"
  )"
  printf 'Attempt %02d: HTTP %s\n' "$attempt" "$status"

  case "$status" in
    400|422)
      ;;
    429)
      found_429=true
      retry_after="$(awk '
        tolower($0) ~ /^retry-after:/ {
          sub(/^[^:]+:[[:space:]]*/, "")
          sub(/\r$/, "")
          print
          exit
        }
      ' "$headers_file")"
      break
      ;;
    401)
      echo 'The supplied API token was rejected.' >&2
      exit 1
      ;;
    *)
      echo "Unexpected HTTP status before the limit: $status" >&2
      exit 1
      ;;
  esac
done

if [[ "$found_429" != true ]]; then
  echo "No HTTP 429 after $ATTEMPTS attempts; rate limiting was not demonstrated." >&2
  exit 1
fi
if [[ ! "$retry_after" =~ ^[0-9]+$ ]] || (( retry_after < 1 )); then
  echo 'HTTP 429 did not include a positive Retry-After header.' >&2
  exit 1
fi

echo "[PASS] Rate limit returned HTTP 429 with Retry-After: $retry_after seconds"
