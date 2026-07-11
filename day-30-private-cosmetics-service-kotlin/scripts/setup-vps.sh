#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_DIR="$PROJECT_DIR/deploy"

INSTALL_OLLAMA=false
CONFIGURE_UFW=false
SSH_PORT=22
DEPLOY_USER=""
COPY_ROOT_AUTHORIZED_KEYS=false

usage() {
  cat <<'EOF'
Usage: sudo ./scripts/setup-vps.sh [options]

Prepare a clean Ubuntu 24.04 VPS without deploying application code.

Options:
  --install-ollama       Download and run Ollama's official installer.
  --configure-ufw        Enable UFW after allowing the selected SSH port.
  --ssh-port PORT        SSH port to allow with --configure-ufw (default: 22).
  --deploy-user USER     Create/update a login user with sudo for deployments.
  --copy-root-authorized-keys
                         Copy root's existing authorized_keys to --deploy-user.
  -h, --help             Show this help.

The script never opens ports 8787 or 11434 and never creates an API token.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --install-ollama)
      INSTALL_OLLAMA=true
      shift
      ;;
    --configure-ufw)
      CONFIGURE_UFW=true
      shift
      ;;
    --ssh-port)
      [[ "$#" -ge 2 ]] || { echo "Missing value for --ssh-port" >&2; exit 2; }
      SSH_PORT="$2"
      shift 2
      ;;
    --deploy-user)
      [[ "$#" -ge 2 ]] || { echo "Missing value for --deploy-user" >&2; exit 2; }
      DEPLOY_USER="$2"
      shift 2
      ;;
    --copy-root-authorized-keys)
      COPY_ROOT_AUTHORIZED_KEYS=true
      shift
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
  echo "Run this setup script with sudo." >&2
  exit 1
fi

if [[ ! "$SSH_PORT" =~ ^[0-9]+$ ]] || (( SSH_PORT < 1 || SSH_PORT > 65535 )); then
  echo "Invalid SSH port: $SSH_PORT" >&2
  exit 2
fi

if [[ "$COPY_ROOT_AUTHORIZED_KEYS" == true && -z "$DEPLOY_USER" ]]; then
  echo "--copy-root-authorized-keys requires --deploy-user USER." >&2
  exit 2
fi
if [[ -n "$DEPLOY_USER" ]]; then
  if [[ ! "$DEPLOY_USER" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || [[ "$DEPLOY_USER" == "root" || "$DEPLOY_USER" == "cosmetics-ai" ]]; then
    echo "Invalid or reserved deploy username: $DEPLOY_USER" >&2
    exit 2
  fi
fi

if [[ ! -r /etc/os-release ]]; then
  echo "Cannot identify the operating system." >&2
  exit 1
fi

# shellcheck disable=SC1091
source /etc/os-release
if [[ "${ID:-}" != "ubuntu" || "${VERSION_ID:-}" != "24.04" ]]; then
  echo "This setup is intentionally limited to Ubuntu 24.04; found ${PRETTY_NAME:-unknown}." >&2
  exit 1
fi

for file in \
  "$DEPLOY_DIR/cosmetics-ai.service" \
  "$DEPLOY_DIR/cosmetics-ai.env.example" \
  "$DEPLOY_DIR/ollama.service.d/override.conf"; do
  [[ -f "$file" ]] || { echo "Missing deployment asset: $file" >&2; exit 1; }
done

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates \
  curl \
  git \
  iproute2 \
  jq \
  openjdk-21-jdk-headless \
  sudo \
  tesseract-ocr \
  tesseract-ocr-eng \
  tesseract-ocr-rus \
  unzip

if [[ "$CONFIGURE_UFW" == true ]]; then
  apt-get install -y --no-install-recommends ufw
fi

if [[ "$INSTALL_OLLAMA" == true ]] && ! command -v ollama >/dev/null 2>&1; then
  installer_dir="$(mktemp -d)"
  cleanup_installer() {
    rm -rf -- "$installer_dir"
  }
  trap cleanup_installer EXIT

  echo "Downloading Ollama's official installer for explicit local execution..."
  curl --fail --show-error --silent --location \
    https://ollama.com/install.sh \
    --output "$installer_dir/install-ollama.sh"
  chmod 0700 "$installer_dir/install-ollama.sh"
  /bin/sh "$installer_dir/install-ollama.sh"
fi

if ! getent group cosmetics-ai >/dev/null 2>&1; then
  groupadd --system cosmetics-ai
fi

if ! id cosmetics-ai >/dev/null 2>&1; then
  useradd \
    --system \
    --gid cosmetics-ai \
    --home-dir /var/lib/cosmetics-ai \
    --shell /usr/sbin/nologin \
    cosmetics-ai
fi

if [[ -n "$DEPLOY_USER" ]]; then
  if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash "$DEPLOY_USER"
  fi
  usermod --append --groups sudo --shell /bin/bash "$DEPLOY_USER"

  deploy_home="$(getent passwd "$DEPLOY_USER" | cut -d: -f6)"
  deploy_group="$(id -gn "$DEPLOY_USER")"
  if [[ -z "$deploy_home" || ! -d "$deploy_home" ]]; then
    echo "Deploy user $DEPLOY_USER has no usable home directory." >&2
    exit 1
  fi

  if [[ "$COPY_ROOT_AUTHORIZED_KEYS" == true ]]; then
    if [[ ! -s /root/.ssh/authorized_keys ]]; then
      echo "Root has no authorized_keys to copy. Add the operator public key first." >&2
      exit 1
    fi
    install -d -o "$DEPLOY_USER" -g "$deploy_group" -m 0700 "$deploy_home/.ssh"
    install -o "$DEPLOY_USER" -g "$deploy_group" -m 0600 \
      /root/.ssh/authorized_keys \
      "$deploy_home/.ssh/authorized_keys"
  fi

  sudoers_tmp="$(mktemp)"
  printf '%s ALL=(ALL:ALL) NOPASSWD: ALL\n' "$DEPLOY_USER" >"$sudoers_tmp"
  chmod 0440 "$sudoers_tmp"
  visudo -cf "$sudoers_tmp" >/dev/null
  install -o root -g root -m 0440 \
    "$sudoers_tmp" \
    "/etc/sudoers.d/cosmetics-ai-${DEPLOY_USER}"
  rm -f -- "$sudoers_tmp"
fi

install -d -o root -g root -m 0755 /opt/cosmetics-ai /opt/cosmetics-ai/releases
install -d -o cosmetics-ai -g cosmetics-ai -m 0750 /var/lib/cosmetics-ai
install -d -o root -g cosmetics-ai -m 0750 /etc/cosmetics-ai
install -o root -g root -m 0644 \
  "$DEPLOY_DIR/cosmetics-ai.service" \
  /etc/systemd/system/cosmetics-ai.service
install -o root -g root -m 0600 \
  "$DEPLOY_DIR/cosmetics-ai.env.example" \
  /etc/cosmetics-ai/cosmetics-ai.env.example

install -d -o root -g root -m 0755 /etc/systemd/system/ollama.service.d
install -o root -g root -m 0644 \
  "$DEPLOY_DIR/ollama.service.d/override.conf" \
  /etc/systemd/system/ollama.service.d/override.conf

systemctl daemon-reload

if systemctl list-unit-files ollama.service --no-legend 2>/dev/null | grep -q '^ollama.service'; then
  systemctl enable --now ollama.service
  systemctl restart ollama.service

  for _ in {1..30}; do
    if curl --fail --silent http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done

  if ! curl --fail --silent --show-error http://127.0.0.1:11434/api/tags >/dev/null; then
    echo "Ollama did not become healthy. Inspect: journalctl -u ollama -n 100" >&2
    exit 1
  fi
else
  echo "Ollama is not installed. Re-run with --install-ollama before deployment." >&2
fi

if command -v ss >/dev/null 2>&1; then
  while IFS= read -r address; do
    [[ -n "$address" ]] || continue
    case "$address" in
      127.0.0.1:11434|\[::1\]:11434)
        ;;
      *)
        echo "Unsafe Ollama listener detected: $address" >&2
        echo "Refusing to continue while port 11434 is not loopback-only." >&2
        exit 1
        ;;
    esac
  done < <(ss -H -ltn | awk '$4 ~ /:11434$/ { print $4 }')
fi

if [[ "$CONFIGURE_UFW" == true ]]; then
  ufw allow "${SSH_PORT}/tcp" comment 'SSH'
  ufw --force enable
  echo "UFW enabled. Only the explicitly allowed SSH port was added by this script."
fi

cat <<'EOF'

VPS prerequisites are ready. No application token has been generated or stored.

Next:
  1. Copy /etc/cosmetics-ai/cosmetics-ai.env.example to cosmetics-ai.env.
  2. Replace APP_API_TOKEN with a unique random value and keep mode 0600.
  3. Run scripts/deploy-vps.sh --pull-model as the normal sudo-capable user.

For private access, keep the app on 127.0.0.1 and use an SSH tunnel:
  ssh -L 8787:127.0.0.1:8787 USER@SERVER
EOF

if [[ -n "$DEPLOY_USER" ]]; then
  echo "Deployment login is ready: ssh ${DEPLOY_USER}@SERVER"
  echo "Place the repository under $deploy_home (not /root), then run deploy-vps.sh as $DEPLOY_USER."
fi
