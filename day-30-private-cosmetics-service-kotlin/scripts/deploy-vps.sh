#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
DEPLOY_DIR="$PROJECT_DIR/deploy"
MODULE="day-30-private-cosmetics-service-kotlin"
DIST_DIR="$PROJECT_DIR/build/install/$MODULE"
ENV_FILE="/etc/cosmetics-ai/cosmetics-ai.env"
RELEASES_DIR="/opt/cosmetics-ai/releases"
CURRENT_LINK="/opt/cosmetics-ai/current"

SKIP_BUILD=false
PULL_MODEL=false
MODEL=""

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-vps.sh [options]

Build and install a versioned release on the current VPS. Run this as a normal
sudo-capable user from the checked-out repository, not as root.

Options:
  --skip-build          Deploy an already prepared Gradle installDist.
  --pull-model          Pull the configured Ollama model before app restart.
  --model NAME          Override the env model used by --pull-model.
  -h, --help            Show this help.

Old releases are kept for manual rollback; this script never deletes them.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --pull-model)
      PULL_MODEL=true
      shift
      ;;
    --model)
      [[ "$#" -ge 2 ]] || { echo "Missing value for --model" >&2; exit 2; }
      MODEL="$2"
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

if [[ "$EUID" -eq 0 ]]; then
  echo "Run deploy-vps.sh as a normal sudo-capable user so Gradle never runs as root." >&2
  exit 1
fi

for command in sudo curl jq systemctl; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done

for file in \
  "$DEPLOY_DIR/cosmetics-ai.service" \
  "$DEPLOY_DIR/ollama.service.d/override.conf"; do
  [[ -f "$file" ]] || { echo "Missing deployment asset: $file" >&2; exit 1; }
done

if ! sudo -n true; then
  echo "Passwordless sudo is required for non-interactive deployment." >&2
  exit 1
fi

if ! sudo test -f "$ENV_FILE"; then
  echo "Missing $ENV_FILE." >&2
  echo "Create it from /etc/cosmetics-ai/cosmetics-ai.env.example and set mode 0600." >&2
  exit 1
fi

env_owner="$(sudo stat -c '%U:%G' "$ENV_FILE")"
env_mode="$(sudo stat -c '%a' "$ENV_FILE")"
if [[ "$env_owner" != "root:root" || "$env_mode" != "600" ]]; then
  echo "$ENV_FILE must be owned by root:root with mode 0600; found $env_owner $env_mode." >&2
  exit 1
fi

if ! sudo grep -qx 'APP_HOST=127.0.0.1' "$ENV_FILE"; then
  echo "APP_HOST must be exactly 127.0.0.1 on the VPS." >&2
  exit 1
fi

if ! sudo grep -qx 'OLLAMA_BASE_URL=http://127.0.0.1:11434' "$ENV_FILE"; then
  echo "OLLAMA_BASE_URL must remain on loopback." >&2
  exit 1
fi

for expected in \
  'APP_PORT=8787' \
  'APP_ALLOW_INSECURE_NO_AUTH=false' \
  'OLLAMA_MAX_LOADED_MODELS=1' \
  'OLLAMA_NUM_PARALLEL=1' \
  'OLLAMA_MAX_QUEUE=2' \
  'OLLAMA_CONTEXT_LENGTH=8192' \
  'MAX_CONTEXT_TOKENS=8192' \
  'OLLAMA_MAX_OUTPUT_TOKENS=192'; do
  if ! sudo grep -qx "$expected" "$ENV_FILE"; then
    echo "Required conservative VPS setting is missing: $expected" >&2
    exit 1
  fi
done

token_lines="$(sudo grep -c '^APP_API_TOKEN=' "$ENV_FILE" || true)"
if [[ "$token_lines" != '1' ]]; then
  echo "APP_API_TOKEN must occur exactly once in $ENV_FILE." >&2
  exit 1
fi

token_length="$(sudo awk -F= '
  $1 == "APP_API_TOKEN" {
    value = substr($0, index($0, "=") + 1)
    print length(value)
    exit
  }
' "$ENV_FILE")"
if (( token_length < 32 )) || sudo grep -q '^APP_API_TOKEN=replace-' "$ENV_FILE"; then
  echo "APP_API_TOKEN must be replaced with at least 32 random characters." >&2
  exit 1
fi

configured_model="$(sudo awk -F= '$1 == "OLLAMA_MODEL" { print substr($0, index($0, "=") + 1); exit }' "$ENV_FILE")"
if [[ -z "$configured_model" ]]; then
  echo "OLLAMA_MODEL is missing from $ENV_FILE." >&2
  exit 1
fi
if [[ -z "$MODEL" ]]; then
  MODEL="$configured_model"
fi

if [[ "$SKIP_BUILD" == false ]]; then
  cd "$ROOT_DIR"
  ./gradlew ":${MODULE}:installDist"
fi

launcher="$DIST_DIR/bin/$MODULE"
if [[ ! -x "$launcher" ]]; then
  echo "Missing executable installDist launcher: $launcher" >&2
  exit 1
fi
for runtime_file in \
  knowledge/ingredient-cards.json \
  knowledge/ocr-corrections.json \
  knowledge/sources.json \
  catalog/products.json; do
  if [[ ! -f "$DIST_DIR/$runtime_file" ]]; then
    echo "installDist is missing required runtime data: $runtime_file" >&2
    exit 1
  fi
done

git_suffix="nogit"
if command -v git >/dev/null 2>&1 && git -C "$ROOT_DIR" rev-parse --short HEAD >/dev/null 2>&1; then
  git_suffix="$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
fi
release_id="$(date -u +%Y%m%dT%H%M%SZ)-${git_suffix}"
release_dir="$RELEASES_DIR/$release_id"

sudo install -d -o root -g root -m 0755 "$RELEASES_DIR" "$release_dir"
sudo cp -a "$DIST_DIR/." "$release_dir/"
sudo chown -R root:root "$release_dir"
sudo chmod 0755 "$release_dir/bin/$MODULE"

sudo install -o root -g root -m 0644 \
  "$DEPLOY_DIR/cosmetics-ai.service" \
  /etc/systemd/system/cosmetics-ai.service
sudo install -d -o root -g root -m 0755 /etc/systemd/system/ollama.service.d
sudo install -o root -g root -m 0644 \
  "$DEPLOY_DIR/ollama.service.d/override.conf" \
  /etc/systemd/system/ollama.service.d/override.conf

# -n prevents replacement of a real directory if the target was modified manually.
sudo systemctl daemon-reload
sudo systemctl enable --now ollama.service
sudo systemctl restart ollama.service

for _ in {1..30}; do
  if curl --fail --silent http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
curl --fail --silent --show-error http://127.0.0.1:11434/api/tags >/dev/null

if [[ "$PULL_MODEL" == true ]]; then
  command -v ollama >/dev/null 2>&1 || { echo "Ollama CLI is not installed." >&2; exit 1; }
  OLLAMA_HOST=http://127.0.0.1:11434 ollama pull "$MODEL"
fi

previous_release="$(sudo readlink -f "$CURRENT_LINK" 2>/dev/null || true)"
# GNU ln -T refuses to treat an accidentally created real directory as a target.
sudo ln -sfnT "$release_dir" "$CURRENT_LINK"
sudo systemctl enable cosmetics-ai.service
sudo systemctl restart cosmetics-ai.service

health_token="$(sudo awk -F= '$1 == "APP_API_TOKEN" { print substr($0, index($0, "=") + 1); exit }' "$ENV_FILE")"
for _ in {1..12}; do
  health_body="$(
    printf 'Authorization: Bearer %s\n' "$health_token" |
      curl --fail --silent --show-error \
        --max-time 20 \
        --header @- \
        http://127.0.0.1:8787/api/health \
        2>/dev/null || true
  )"
  if jq -e '.status == "ready" and .modelInstalled == true and .ocrReady == true' \
    >/dev/null 2>&1 <<<"$health_body"; then
    unset health_token
    echo "Release $release_id is healthy."
    echo "Current release: $release_dir"
    echo "Run scripts/diagnose.sh and scripts/smoke-test.sh next."
    exit 0
  fi
  sleep 5
done
unset health_token

echo "Application health check failed for $release_dir." >&2
sudo systemctl status cosmetics-ai.service --no-pager >&2 || true
sudo journalctl -u cosmetics-ai.service -n 100 --no-pager >&2 || true
if [[ -n "$previous_release" && -d "$previous_release" ]]; then
  echo "Rolling back to $previous_release..." >&2
  sudo ln -sfnT "$previous_release" "$CURRENT_LINK"
  sudo systemctl restart cosmetics-ai.service
else
  echo 'No previous release was available for automatic rollback.' >&2
fi
exit 1
