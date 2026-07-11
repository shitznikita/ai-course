#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${APP_URL:-http://127.0.0.1:8787}"
REQUESTS="${LOAD_TEST_REQUESTS:-4}"
CONCURRENCY="${LOAD_TEST_CONCURRENCY:-2}"
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-360}"

usage() {
  cat <<'EOF'
Usage: ./scripts/load-test.sh [--requests N] [--concurrency N] [--base-url URL]

Send several real analysis requests and report HTTP codes and latency. Defaults
to four requests with concurrency two, matching the conservative Ollama queue.
Set APP_API_TOKEN or enter it at the hidden interactive prompt. Response bodies
and the token are never retained after the test.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --requests)
      [[ "$#" -ge 2 ]] || { echo "Missing value for --requests" >&2; exit 2; }
      REQUESTS="$2"
      shift 2
      ;;
    --concurrency)
      [[ "$#" -ge 2 ]] || { echo "Missing value for --concurrency" >&2; exit 2; }
      CONCURRENCY="$2"
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

if [[ ! "$REQUESTS" =~ ^[0-9]+$ ]] || (( REQUESTS < 1 || REQUESTS > 100 )); then
  echo '--requests must be between 1 and 100.' >&2
  exit 2
fi
if [[ ! "$CONCURRENCY" =~ ^[0-9]+$ ]] || (( CONCURRENCY < 1 || CONCURRENCY > REQUESTS )); then
  echo '--concurrency must be between 1 and the request count.' >&2
  exit 2
fi
if [[ ! "$REQUEST_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || (( REQUEST_TIMEOUT_SECONDS < 1 )); then
  echo 'REQUEST_TIMEOUT_SECONDS must be a positive integer.' >&2
  exit 2
fi

for command in curl jq; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done

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

health_status="$(
  printf 'Authorization: Bearer %s\n' "$APP_API_TOKEN" |
    curl --silent --show-error --max-time 20 \
      --header @- \
      --output /dev/null --write-out '%{http_code}' "$BASE_URL/api/health"
)"
if [[ "$health_status" != '200' ]]; then
  echo "Application is not healthy: HTTP $health_status" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

run_request() {
  local index="$1"
  local payload result
  payload="$(printf '{"inciText":"Aqua, Glycerin, Niacinamide, Panthenol","productName":"Load test %d","profile":{"skinType":"sensitive","sensitive":true,"allergies":[],"goals":["увлажнение"]}}' "$index")"

  if result="$(
    printf 'Authorization: Bearer %s\nContent-Type: application/json\n' "$APP_API_TOKEN" |
      curl --silent --show-error \
        --max-time "$REQUEST_TIMEOUT_SECONDS" \
        --request POST \
        --header @- \
        --data "$payload" \
        --output "$work_dir/body-$index.json" \
        --write-out '%{http_code} %{time_total}' \
        "$BASE_URL/api/analyze/text"
  )"; then
    printf '%s %s\n' "$index" "$result" >"$work_dir/result-$index.txt"
  else
    printf '%s 000 0\n' "$index" >"$work_dir/result-$index.txt"
  fi
}

echo "Running $REQUESTS request(s), concurrency $CONCURRENCY..."
pids=()
for ((index = 1; index <= REQUESTS; index++)); do
  run_request "$index" &
  pids+=("$!")
  if (( ${#pids[@]} >= CONCURRENCY )); then
    for pid in "${pids[@]}"; do
      wait "$pid" || true
    done
    pids=()
  fi
done
if (( ${#pids[@]} > 0 )); then
  for pid in "${pids[@]}"; do
    wait "$pid" || true
  done
fi

results_file="$work_dir/results.txt"
for ((index = 1; index <= REQUESTS; index++)); do
  if [[ -f "$work_dir/result-$index.txt" ]]; then
    cat "$work_dir/result-$index.txt" >>"$results_file"
  else
    printf '%s 000 0\n' "$index" >>"$results_file"
  fi
done

printf '\n%-9s %-8s %s\n' 'REQUEST' 'HTTP' 'SECONDS'
sort -n "$results_file" | awk '{ printf "%-9s %-8s %s\n", $1, $2, $3 }'

success_count="$(awk '$2 == 200 { count++ } END { print count + 0 }' "$results_file")"
rate_limited_count="$(awk '$2 == 429 { count++ } END { print count + 0 }' "$results_file")"
other_count=$((REQUESTS - success_count - rate_limited_count))
latency_summary="$(awk '$2 == 200 {
  count++
  total += $3
  if (count == 1 || $3 < min) min = $3
  if ($3 > max) max = $3
} END {
  if (count > 0) printf "min %.3fs, avg %.3fs, max %.3fs", min, total / count, max
  else print "no successful samples"
}' "$results_file")"

echo
echo "HTTP 200: $success_count; HTTP 429: $rate_limited_count; other: $other_count"
echo "Successful latency: $latency_summary"

invalid_contracts=0
for ((index = 1; index <= REQUESTS; index++)); do
  status="$(awk -v target="$index" '$1 == target { print $2 }' "$results_file")"
  if [[ "$status" == '200' ]] && ! jq -e '
    (.sessionId | type == "string" and length > 0) and
    (.report.status | type == "string" and length > 0) and
    (.model.name | type == "string" and length > 0)
  ' "$work_dir/body-$index.json" >/dev/null; then
    invalid_contracts=$((invalid_contracts + 1))
  fi
done

if (( success_count != REQUESTS || invalid_contracts > 0 )); then
  echo "Load test failed: expected $REQUESTS valid HTTP 200 responses; invalid contracts: $invalid_contracts." >&2
  exit 1
fi

echo 'Load test passed: all responses were successful and matched the API contract.'
