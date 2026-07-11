#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE="$PROJECT_DIR/deploy/Caddyfile.example"

PUBLIC_HOSTNAME=""
INSTALL_CADDY=false
CONFIGURE_UFW=false
WAIT_SECONDS=180
SSH_PORT=22

usage() {
  cat <<'EOF'
Usage: sudo ./scripts/enable-public-https.sh --hostname HOST [options]

Publish the loopback-only Day 30 application through Caddy automatic HTTPS.
Ollama and the Kotlin application remain private on ports 11434 and 8787.

Required:
  --hostname HOST       Public DNS hostname whose A/AAAA records point here.

Options:
  --install-caddy       Install Caddy from its official stable Ubuntu repository.
  --configure-ufw       Open TCP 80 and 443 in the existing UFW firewall.
  --wait-seconds N      Maximum certificate wait, 30-600 seconds (default: 180).
  --ssh-port PORT       Existing SSH allow port to preserve (default: 22).
  -h, --help            Show this help.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --hostname)
      [[ "$#" -ge 2 ]] || { echo 'Missing value for --hostname.' >&2; exit 2; }
      PUBLIC_HOSTNAME="${2,,}"
      shift 2
      ;;
    --install-caddy)
      INSTALL_CADDY=true
      shift
      ;;
    --configure-ufw)
      CONFIGURE_UFW=true
      shift
      ;;
    --wait-seconds)
      [[ "$#" -ge 2 ]] || { echo 'Missing value for --wait-seconds.' >&2; exit 2; }
      WAIT_SECONDS="$2"
      shift 2
      ;;
    --ssh-port)
      [[ "$#" -ge 2 ]] || { echo 'Missing value for --ssh-port.' >&2; exit 2; }
      SSH_PORT="$2"
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

if [[ "$EUID" -ne 0 ]]; then
  echo 'Run this script with sudo.' >&2
  exit 1
fi
if [[ -z "$PUBLIC_HOSTNAME" || ${#PUBLIC_HOSTNAME} -gt 253 || "$PUBLIC_HOSTNAME" != *.* || \
      "$PUBLIC_HOSTNAME" == *..* || "$PUBLIC_HOSTNAME" == .* || "$PUBLIC_HOSTNAME" == *. ]]; then
  echo 'A valid public DNS hostname is required.' >&2
  exit 2
fi
if [[ "$PUBLIC_HOSTNAME" =~ ^[0-9.]+$ ]]; then
  echo 'Use a DNS hostname, not an IP address.' >&2
  exit 2
fi
IFS='.' read -r -a hostname_labels <<<"$PUBLIC_HOSTNAME"
for label in "${hostname_labels[@]}"; do
  if [[ ! "$label" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]]; then
    echo "Invalid DNS label in hostname: $label" >&2
    exit 2
  fi
done
if [[ ! "$WAIT_SECONDS" =~ ^[0-9]+$ ]] || (( WAIT_SECONDS < 30 || WAIT_SECONDS > 600 )); then
  echo '--wait-seconds must be between 30 and 600.' >&2
  exit 2
fi
if [[ ! "$SSH_PORT" =~ ^[0-9]+$ ]] || (( SSH_PORT < 1 || SSH_PORT > 65535 )); then
  echo '--ssh-port must be between 1 and 65535.' >&2
  exit 2
fi

[[ -r /etc/os-release ]] || { echo 'Cannot identify the operating system.' >&2; exit 1; }
# shellcheck disable=SC1091
source /etc/os-release
if [[ "${ID:-}" != 'ubuntu' || "${VERSION_ID:-}" != '24.04' ]]; then
  echo "This script supports Ubuntu 24.04; found ${PRETTY_NAME:-unknown}." >&2
  exit 1
fi
[[ -f "$TEMPLATE" ]] || { echo "Missing Caddy template: $TEMPLATE" >&2; exit 1; }

for command in curl getent hostname install jq runuser sed systemctl ufw; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done

if ! curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8787/api/health/live >/dev/null; then
  echo 'The loopback application is not alive on 127.0.0.1:8787.' >&2
  exit 1
fi

mapfile -t resolved_addresses < <(getent ahosts "$PUBLIC_HOSTNAME" | awk '{ print $1 }' | sort -u)
mapfile -t local_addresses < <(hostname -I | tr ' ' '\n' | sed '/^$/d' | sort -u)
if (( ${#resolved_addresses[@]} == 0 )); then
  echo "Hostname does not resolve: $PUBLIC_HOSTNAME" >&2
  exit 1
fi
all_addresses_local=true
for resolved in "${resolved_addresses[@]}"; do
  matched_local=false
  for local_address in "${local_addresses[@]}"; do
    if [[ "$resolved" == "$local_address" ]]; then
      matched_local=true
    fi
  done
  if [[ "$matched_local" != true ]]; then
    all_addresses_local=false
  fi
done
if [[ "$all_addresses_local" != true ]]; then
  echo "Every resolved address for $PUBLIC_HOSTNAME must be assigned to this VPS." >&2
  printf 'Resolved: %s\n' "${resolved_addresses[*]}" >&2
  printf 'Local:    %s\n' "${local_addresses[*]}" >&2
  exit 1
fi

if ! grep -Eq '^IPV6=yes$' /etc/default/ufw; then
  echo 'UFW must have IPV6=yes before publishing a hostname with an AAAA record.' >&2
  exit 1
fi
ufw_status="$(ufw status verbose)"
if ! grep -Eq '^Status: active$' <<<"$ufw_status"; then
  echo 'UFW must already be active before Caddy is installed or started.' >&2
  exit 1
fi
if ! grep -Eq '^Default: deny \(incoming\)' <<<"$ufw_status"; then
  echo 'UFW default incoming policy must be deny.' >&2
  exit 1
fi
if ! grep -Eq "^${SSH_PORT}/tcp[[:space:]]+ALLOW" <<<"$ufw_status" || \
   ! grep -Eq "^${SSH_PORT}/tcp \\(v6\\)[[:space:]]+ALLOW" <<<"$ufw_status"; then
  echo "UFW must preserve SSH allow rules for TCP $SSH_PORT over IPv4 and IPv6." >&2
  exit 1
fi

has_80_v4=false
has_80_v6=false
has_443_v4=false
has_443_v6=false
grep -Eq '^80/tcp[[:space:]]+ALLOW' <<<"$ufw_status" && has_80_v4=true
grep -Eq '^80/tcp \(v6\)[[:space:]]+ALLOW' <<<"$ufw_status" && has_80_v6=true
grep -Eq '^443/tcp[[:space:]]+ALLOW' <<<"$ufw_status" && has_443_v4=true
grep -Eq '^443/tcp \(v6\)[[:space:]]+ALLOW' <<<"$ufw_status" && has_443_v6=true
if [[ "$has_80_v4" != "$has_80_v6" || "$has_443_v4" != "$has_443_v6" ]]; then
  echo 'UFW has inconsistent IPv4/IPv6 public web rules; fix them manually before continuing.' >&2
  exit 1
fi
if [[ "$CONFIGURE_UFW" != true && ( "$has_80_v4" != true || "$has_443_v4" != true ) ]]; then
  echo 'TCP 80/443 are not allowed. Re-run with --configure-ufw.' >&2
  exit 1
fi

work_dir="$(mktemp -d)"
chmod 0755 "$work_dir"
config_replaced=false
added_80=false
added_443=false
completed=false
caddy_service_was_active=false
caddy_service_was_enabled=false
had_previous_config=false

if systemctl is-active --quiet caddy.service 2>/dev/null; then
  caddy_service_was_active=true
fi
if systemctl is-enabled --quiet caddy.service 2>/dev/null; then
  caddy_service_was_enabled=true
fi
if [[ -f /etc/caddy/Caddyfile ]]; then
  install -o root -g root -m 0600 /etc/caddy/Caddyfile "$work_dir/original.Caddyfile"
  had_previous_config=true
fi

finish() {
  status=$?
  trap - EXIT
  set +e
  if (( status != 0 )) && [[ "$completed" != true ]]; then
    if [[ "$config_replaced" == true ]]; then
      if [[ "$had_previous_config" == true ]]; then
        install -o root -g caddy -m 0640 "$work_dir/original.Caddyfile" /etc/caddy/Caddyfile
      else
        rm -f -- /etc/caddy/Caddyfile
      fi
    fi
    if [[ "$added_443" == true ]]; then
      ufw --force delete allow 443/tcp >/dev/null 2>&1 || true
    fi
    if [[ "$added_80" == true ]]; then
      ufw --force delete allow 80/tcp >/dev/null 2>&1 || true
    fi
    if [[ "$caddy_service_was_enabled" == true ]]; then
      systemctl enable caddy.service >/dev/null 2>&1 || true
    else
      systemctl disable caddy.service >/dev/null 2>&1 || true
    fi
    if [[ "$caddy_service_was_active" == true ]]; then
      systemctl start caddy.service >/dev/null 2>&1 || true
      systemctl reload caddy.service >/dev/null 2>&1 || true
    else
      systemctl stop caddy.service >/dev/null 2>&1 || true
    fi
  fi
  rm -rf -- "$work_dir"
  exit "$status"
}
trap finish EXIT

if [[ "$INSTALL_CADDY" == true ]]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y --no-install-recommends \
    apt-transport-https \
    ca-certificates \
    curl \
    debian-archive-keyring \
    debian-keyring \
    gnupg

  curl --fail --silent --show-error --location \
    https://dl.cloudsmith.io/public/caddy/stable/gpg.key \
    --output "$work_dir/caddy-stable.asc"
  gpg --batch --yes --dearmor \
    --output "$work_dir/caddy-stable-archive-keyring.gpg" \
    "$work_dir/caddy-stable.asc"
  install -o root -g root -m 0644 \
    "$work_dir/caddy-stable-archive-keyring.gpg" \
    /usr/share/keyrings/caddy-stable-archive-keyring.gpg

  curl --fail --silent --show-error --location \
    https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt \
    --output "$work_dir/caddy-stable.list"
  install -o root -g root -m 0644 \
    "$work_dir/caddy-stable.list" \
    /etc/apt/sources.list.d/caddy-stable.list
  apt-get update
  apt-get install -y --no-install-recommends caddy
fi

command -v caddy >/dev/null 2>&1 || {
  echo 'Caddy is not installed. Re-run with --install-caddy.' >&2
  exit 1
}
getent passwd caddy >/dev/null 2>&1 || { echo 'The official caddy system user is missing.' >&2; exit 1; }

candidate="$work_dir/Caddyfile"
sed "s/__PUBLIC_HOSTNAME__/$PUBLIC_HOSTNAME/g" "$TEMPLATE" >"$candidate"
caddy fmt --overwrite "$candidate"
runuser -u caddy -- caddy validate --config "$candidate" --adapter caddyfile

install -d -o root -g caddy -m 0750 /etc/caddy
install -o root -g caddy -m 0640 "$candidate" /etc/caddy/Caddyfile.candidate
runuser -u caddy -- caddy validate --config /etc/caddy/Caddyfile.candidate --adapter caddyfile
if [[ -f /etc/caddy/Caddyfile ]]; then
  cp --archive /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.backup.$(date -u +%Y%m%dT%H%M%SZ)"
fi
mv /etc/caddy/Caddyfile.candidate /etc/caddy/Caddyfile
config_replaced=true

systemctl enable --now caddy.service
systemctl reload caddy.service

if [[ "$CONFIGURE_UFW" == true ]]; then
  if [[ "$has_80_v4" != true ]]; then
    ufw allow 80/tcp comment 'Caddy HTTP redirect and ACME'
    added_80=true
  fi
  if [[ "$has_443_v4" != true ]]; then
    ufw allow 443/tcp comment 'Caddy HTTPS'
    added_443=true
  fi
fi

systemctl reload caddy.service

deadline=$((SECONDS + WAIT_SECONDS))
while (( SECONDS < deadline )); do
  health_body="$(
    curl --fail --silent --show-error \
      --connect-timeout 3 \
      --max-time 8 \
      "https://$PUBLIC_HOSTNAME/api/health/live" 2>/dev/null || true
  )"
  if jq -e '.status == "alive"' >/dev/null 2>&1 <<<"$health_body"; then
    completed=true
    echo "Server-side HTTPS is ready: https://$PUBLIC_HOSTNAME"
    echo 'The application and Ollama remain loopback-only.'
    echo 'Run scripts/check-public-url.sh from another computer to prove external IPv4/IPv6 access.'
    exit 0
  fi
  sleep 2
done

echo "Caddy did not obtain a working certificate for $PUBLIC_HOSTNAME within $WAIT_SECONDS seconds." >&2
systemctl status caddy.service --no-pager >&2 || true
journalctl -u caddy.service -n 100 --no-pager >&2 || true
exit 1
