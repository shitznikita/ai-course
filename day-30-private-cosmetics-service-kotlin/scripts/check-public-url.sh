#!/usr/bin/env bash
set -euo pipefail

PUBLIC_HOSTNAME="${PUBLIC_HOSTNAME:-}"
failures=0

usage() {
  cat <<'EOF'
Usage: ./scripts/check-public-url.sh --hostname HOST

Run this from a computer outside the VPS. It verifies public DNS, a trusted TLS
certificate, IPv4/IPv6 liveness, HTTP-to-HTTPS redirect, and security headers.
No access token is read or sent.
EOF
}

pass() { printf '[PASS] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --hostname)
      [[ "$#" -ge 2 ]] || { echo 'Missing value for --hostname.' >&2; exit 2; }
      PUBLIC_HOSTNAME="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
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

if [[ -z "$PUBLIC_HOSTNAME" || ${#PUBLIC_HOSTNAME} -gt 253 || "$PUBLIC_HOSTNAME" != *.* || \
      "$PUBLIC_HOSTNAME" == *..* || "$PUBLIC_HOSTNAME" == .* || "$PUBLIC_HOSTNAME" == *. ]]; then
  echo 'A valid public DNS hostname is required.' >&2
  exit 2
fi
IFS='.' read -r -a hostname_labels <<<"$PUBLIC_HOSTNAME"
for label in "${hostname_labels[@]}"; do
  [[ "$label" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || {
    echo "Invalid DNS label in hostname: $label" >&2
    exit 2
  }
done
for command in curl dig jq; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done

a_records="$(dig +short A "$PUBLIC_HOSTNAME" | sort -u | tr '\n' ' ')"
aaaa_records="$(dig +short AAAA "$PUBLIC_HOSTNAME" | sort -u | tr '\n' ' ')"
if [[ -n "$a_records" ]]; then
  pass "Public A record(s): ${a_records% }"
else
  fail 'No public A record found'
fi
if [[ -n "$aaaa_records" ]]; then
  pass "Public AAAA record(s): ${aaaa_records% }"
else
  fail 'No public AAAA record found'
fi

work_dir="$(mktemp -d)"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

for ip in $a_records; do
  safe_name="$(tr '.:' '__' <<<"$ip")"
  body_file="$work_dir/body-4-$safe_name.json"
  error_file="$work_dir/curl-4-$safe_name.err"
  if curl -4 --fail --silent --show-error \
      --connect-timeout 8 --max-time 20 \
      --resolve "$PUBLIC_HOSTNAME:443:$ip" \
      --output "$body_file" \
      "https://$PUBLIC_HOSTNAME/api/health/live" 2>"$error_file" && \
     jq -e '.status == "alive"' "$body_file" >/dev/null 2>&1; then
    pass "Trusted public HTTPS works through IPv4 $ip"
  else
    fail "Public HTTPS failed through IPv4 $ip: $(tr '\n' ' ' <"$error_file" | cut -c1-240)"
  fi
done
for ip in $aaaa_records; do
  safe_name="$(tr '.:' '__' <<<"$ip")"
  body_file="$work_dir/body-6-$safe_name.json"
  error_file="$work_dir/curl-6-$safe_name.err"
  if curl -6 --fail --silent --show-error \
      --connect-timeout 8 --max-time 20 \
      --resolve "$PUBLIC_HOSTNAME:443:[$ip]" \
      --output "$body_file" \
      "https://$PUBLIC_HOSTNAME/api/health/live" 2>"$error_file" && \
     jq -e '.status == "alive"' "$body_file" >/dev/null 2>&1; then
    pass "Trusted public HTTPS works through IPv6 $ip"
  else
    fail "Public HTTPS failed through IPv6 $ip: $(tr '\n' ' ' <"$error_file" | cut -c1-240)"
  fi
done

redirect_result="$(
  curl --silent --show-error --output /dev/null \
    --connect-timeout 8 --max-time 20 \
    --write-out '%{http_code} %{redirect_url}' \
    "http://$PUBLIC_HOSTNAME" 2>"$work_dir/redirect.err" || true
)"
if [[ "$redirect_result" == "308 https://$PUBLIC_HOSTNAME/"* ]]; then
  pass 'Public HTTP redirects permanently to HTTPS'
else
  fail "Unexpected public HTTP result: ${redirect_result:-$(tr '\n' ' ' <"$work_dir/redirect.err")}"
fi

if curl --fail --silent --show-error \
    --connect-timeout 8 --max-time 20 \
    --dump-header "$work_dir/headers.txt" \
    --output /dev/null \
    "https://$PUBLIC_HOSTNAME/api/health/live"; then
  if grep -Eqi '^strict-transport-security:[[:space:]]*max-age=31536000' "$work_dir/headers.txt"; then
    pass 'HSTS is present'
  else
    fail 'HSTS header is missing'
  fi
  if grep -Eqi '^server:' "$work_dir/headers.txt"; then
    fail 'Server identity header is exposed'
  else
    pass 'Server identity header is removed'
  fi
else
  fail 'Could not fetch HTTPS response headers'
fi

if (( failures > 0 )); then
  echo "External public URL check complete: $failures failure(s)." >&2
  exit 1
fi
echo 'External public URL check complete: 0 failures.'
