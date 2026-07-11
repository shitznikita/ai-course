#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${APP_URL:-http://127.0.0.1:8787}"
DEEP=false
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-360}"

usage() {
  cat <<'EOF'
Usage: ./scripts/smoke-test.sh [--deep] [--base-url URL]

Always verifies shallow health and the unauthenticated 401 boundary.
If --deep is passed or APP_API_TOKEN is set, also runs a real model-backed
cosmetics analysis and validates its JSON response. The token is never printed
or written to disk.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --deep)
      DEEP=true
      shift
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

for command in curl head jq tr; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

health_body="$work_dir/liveness.json"
health_status="$(curl --silent --show-error \
  --max-time 15 \
  --output "$health_body" \
  --write-out '%{http_code}' \
  "$BASE_URL/api/health/live")"
if [[ "$health_status" != '200' ]]; then
  echo "Health check failed with HTTP $health_status" >&2
  sed -n '1,20p' "$health_body" >&2
  exit 1
fi
if ! jq -e '.status == "alive"' "$health_body" >/dev/null; then
  echo 'Liveness endpoint returned HTTP 200 with an invalid contract.' >&2
  jq . "$health_body" >&2
  exit 1
fi
echo '[PASS] GET /api/health/live reports an active process'

payload='{"inciText":"Aqua, Glycerin, Niacinamide, Panthenol","productName":"Demo serum","profile":{"skinType":"sensitive","sensitive":true,"allergies":[],"goals":["увлажнение"]}}'
unauthorized_body="$work_dir/unauthorized.json"
unauthorized_status="$(curl --silent --show-error \
  --max-time 15 \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "$payload" \
  --output "$unauthorized_body" \
  --write-out '%{http_code}' \
  "$BASE_URL/api/analyze/text")"
if [[ "$unauthorized_status" != '401' ]]; then
  echo "Expected HTTP 401 without a token, got $unauthorized_status" >&2
  sed -n '1,20p' "$unauthorized_body" >&2
  exit 1
fi
echo '[PASS] Protected endpoint rejects missing Bearer token with HTTP 401'

if [[ -n "${APP_API_TOKEN:-}" ]]; then
  DEEP=true
fi

if [[ "$DEEP" == false ]]; then
  echo '[SKIP] Deep inference was not requested and APP_API_TOKEN is unset'
  exit 0
fi

if [[ -z "${APP_API_TOKEN:-}" ]]; then
  if [[ ! -t 0 ]]; then
    echo 'Set APP_API_TOKEN or run interactively to perform --deep smoke testing.' >&2
    exit 1
  fi
  read -r -s -p 'API token (input is hidden and not stored): ' APP_API_TOKEN
  echo
fi

if (( ${#APP_API_TOKEN} < 24 )); then
  echo 'APP_API_TOKEN is unexpectedly short.' >&2
  exit 1
fi

readiness_body="$work_dir/readiness.json"
readiness_status="$(
  printf 'Authorization: Bearer %s\n' "$APP_API_TOKEN" |
    curl --silent --show-error \
      --max-time 20 \
      --header @- \
      --output "$readiness_body" \
      --write-out '%{http_code}' \
      "$BASE_URL/api/health"
)"
if [[ "$readiness_status" != '200' ]] || ! jq -e \
  '.status == "ready" and .modelInstalled == true and .ocrReady == true' \
  "$readiness_body" >/dev/null; then
  echo "Authenticated readiness failed with HTTP $readiness_status" >&2
  jq . "$readiness_body" >&2 || true
  exit 1
fi
echo '[PASS] Authenticated readiness reports installed model and OCR'

oversized_inci="$(head -c 12001 /dev/zero | tr '\0' 'A')"
oversized_payload="$(jq -nc --arg inci "$oversized_inci" '{inciText: $inci, productName: "Oversized probe"}')"
oversized_body="$work_dir/oversized.json"
oversized_status="$(
  printf 'Authorization: Bearer %s\nContent-Type: application/json\n' "$APP_API_TOKEN" |
    curl --silent --show-error \
      --max-time 15 \
      --request POST \
      --header @- \
      --data "$oversized_payload" \
      --output "$oversized_body" \
      --write-out '%{http_code}' \
      "$BASE_URL/api/analyze/text"
)"
if [[ "$oversized_status" != '422' ]]; then
  echo "Expected oversized INCI to be rejected with HTTP 422, got $oversized_status" >&2
  sed -n '1,20p' "$oversized_body" >&2
  exit 1
fi
echo '[PASS] Oversized INCI is rejected before model inference with HTTP 422'

analysis_body="$work_dir/analysis.json"
analysis_status="$(
  printf 'Authorization: Bearer %s\nContent-Type: application/json\n' "$APP_API_TOKEN" |
    curl --silent --show-error \
      --max-time "$REQUEST_TIMEOUT_SECONDS" \
      --request POST \
      --header @- \
      --data "$payload" \
      --output "$analysis_body" \
      --write-out '%{http_code}' \
      "$BASE_URL/api/analyze/text"
)"

if [[ "$analysis_status" != '200' ]]; then
  echo "Deep analysis failed with HTTP $analysis_status" >&2
  sed -n '1,40p' "$analysis_body" >&2
  exit 1
fi

if ! jq -e '
  (.sessionId | type == "string" and length > 0) and
  (.report.status | type == "string" and length > 0) and
  (.model.name | type == "string" and length > 0)
' "$analysis_body" >/dev/null; then
  echo 'Deep analysis returned HTTP 200 but its response contract is invalid.' >&2
  jq . "$analysis_body" >&2
  exit 1
fi

echo '[PASS] Authenticated analysis returned sessionId, report.status, and model metrics'
jq '{sessionId, status: .report.status, model: .model.name, latencyMs: .model.latencyMs}' "$analysis_body"
