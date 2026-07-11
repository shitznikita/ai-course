#!/usr/bin/env bash
set -euo pipefail

PUBLIC_HOSTNAME="${PUBLIC_HOSTNAME:-}"
failures=0

usage() {
  cat <<'EOF'
Usage: ./scripts/diagnose-public-https.sh --hostname HOST

Verify public IPv4/IPv6 HTTPS, HTTP redirect, Caddy, UFW, and private backend
listeners. This script never reads or prints APP_API_TOKEN.
EOF
}

pass() { printf '[PASS] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --hostname)
      [[ "$#" -ge 2 ]] || { echo 'Missing value for --hostname.' >&2; exit 2; }
      PUBLIC_HOSTNAME="${2,,}"
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
for command in caddy curl getent jq ss systemctl; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done
if ! sudo -n true; then
  echo 'Passwordless sudo is required for diagnostics.' >&2
  exit 1
fi

if systemctl is-active --quiet caddy.service; then
  pass 'caddy.service is active'
else
  fail 'caddy.service is not active'
fi
if systemctl is-enabled --quiet caddy.service; then
  pass 'caddy.service is enabled'
else
  fail 'caddy.service is not enabled'
fi
if sudo -u caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null; then
  pass 'Installed Caddyfile validates'
else
  fail 'Installed Caddyfile does not validate'
fi

resolved_addresses="$(getent ahosts "$PUBLIC_HOSTNAME" | awk '{ print $1 }' | sort -u | tr '\n' ' ')"
if [[ -n "$resolved_addresses" ]]; then
  pass "Server resolver addresses: ${resolved_addresses% }"
else
  fail 'Server resolver returned no public addresses'
fi

work_dir="$(mktemp -d)"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

for family in 4 6; do
  body_file="$work_dir/body-$family.json"
  error_file="$work_dir/curl-$family.err"
  if curl "-$family" --fail --silent --show-error \
      --connect-timeout 5 --max-time 15 \
      --output "$body_file" \
      "https://$PUBLIC_HOSTNAME/api/health/live" 2>"$error_file" && \
     jq -e '.status == "alive"' "$body_file" >/dev/null 2>&1; then
    pass "Server-side HTTPS reaches the public IPv$family address"
  else
    fail "Server-side HTTPS failed over IPv$family: $(tr '\n' ' ' <"$error_file" | cut -c1-240)"
  fi
done

redirect_result="$(
  curl --silent --show-error --output /dev/null \
    --connect-timeout 5 --max-time 15 \
    --write-out '%{http_code} %{redirect_url}' \
    "http://$PUBLIC_HOSTNAME" 2>/dev/null || true
)"
if [[ "$redirect_result" == "308 https://$PUBLIC_HOSTNAME/"* ]]; then
  pass 'HTTP redirects permanently to HTTPS'
else
  fail "Unexpected HTTP redirect result: ${redirect_result:-none}"
fi

tcp_listeners="$(sudo ss -H -ltnp)"
for port in 80 443; do
  caddy_listener=false
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    address="$(awk '{ print $4 }' <<<"$line")"
    if [[ "$line" == *'"caddy"'* ]]; then
      case "$address" in
        127.0.0.1:"$port"|\[::1\]:"$port"|\[::ffff:127.0.0.1\]:"$port")
          ;;
        *)
          caddy_listener=true
          ;;
      esac
    fi
  done < <(awk -v suffix=":$port" '$4 ~ suffix "$"' <<<"$tcp_listeners")
  if [[ "$caddy_listener" == true ]]; then
    pass "Caddy owns a non-loopback TCP listener on port $port"
  else
    fail "Caddy has no non-loopback TCP listener on port $port"
  fi
done

for port in 8787 11434; do
  listener_count=0
  private_only=true
  while IFS= read -r address; do
    [[ -n "$address" ]] || continue
    listener_count=$((listener_count + 1))
    case "$address" in
      127.0.0.1:"$port"|\[::1\]:"$port"|\[::ffff:127.0.0.1\]:"$port")
        ;;
      *)
        private_only=false
        fail "Private backend port $port is exposed by listener $address"
        ;;
    esac
  done < <(ss -H -ltn | awk -v suffix=":$port" '$4 ~ suffix "$" { print $4 }')
  if (( listener_count > 0 )) && [[ "$private_only" == true ]]; then
    pass "Backend port $port remains loopback-only"
  elif (( listener_count == 0 )); then
    fail "No backend listener found on port $port"
  fi
done

admin_count=0
admin_private=true
while IFS= read -r line; do
  [[ -n "$line" ]] || continue
  admin_count=$((admin_count + 1))
  address="$(awk '{ print $4 }' <<<"$line")"
  case "$address" in
    127.0.0.1:2019|\[::1\]:2019|\[::ffff:127.0.0.1\]:2019)
      ;;
    *)
      admin_private=false
      fail "Caddy admin API is exposed by listener $address"
      ;;
  esac
done < <(awk '$4 ~ /:2019$/ && /"caddy"/' <<<"$tcp_listeners")
if (( admin_count > 0 )) && [[ "$admin_private" == true ]]; then
  pass 'Caddy admin API remains loopback-only'
elif (( admin_count == 0 )); then
  fail 'Caddy admin API listener was not found'
fi

if sudo ss -H -lunp | awk '$4 ~ /:443$/ { found=1 } END { exit(found ? 0 : 1) }'; then
  fail 'UDP 443 is listening even though HTTP/3 is disabled'
else
  pass 'UDP 443 is closed because HTTP/3 is disabled'
fi

ufw_status="$(sudo ufw status verbose)"
if grep -Eq '^Status: active$' <<<"$ufw_status" && grep -Eq '^Default: deny \(incoming\)' <<<"$ufw_status"; then
  pass 'UFW is active with default deny incoming'
else
  fail 'UFW is not active with default deny incoming'
fi
if grep -Eq '^80/tcp[[:space:]]+ALLOW' <<<"$ufw_status" && \
   grep -Eq '^80/tcp \(v6\)[[:space:]]+ALLOW' <<<"$ufw_status"; then
  pass 'UFW allows TCP 80 over IPv4 and IPv6'
else
  fail 'UFW does not allow TCP 80 over both IPv4 and IPv6'
fi
if grep -Eq '^443/tcp[[:space:]]+ALLOW' <<<"$ufw_status" && \
   grep -Eq '^443/tcp \(v6\)[[:space:]]+ALLOW' <<<"$ufw_status"; then
  pass 'UFW allows TCP 443 over IPv4 and IPv6'
else
  fail 'UFW does not allow TCP 443 over both IPv4 and IPv6'
fi
if grep -Eq '^(8787|11434)(/tcp)?[[:space:]]+ALLOW' <<<"$ufw_status"; then
  fail 'UFW explicitly allows a private backend port'
else
  pass 'UFW does not allow backend ports 8787 or 11434'
fi

if (( failures > 0 )); then
  echo "Public HTTPS diagnostics complete: $failures failure(s)." >&2
  exit 1
fi
echo 'Public HTTPS diagnostics complete: 0 failures.'
