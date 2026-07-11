#!/usr/bin/env bash
set -euo pipefail
umask 077

ENV_FILE="/etc/cosmetics-ai/cosmetics-ai.env"

if [[ "$EUID" -ne 0 ]]; then
  echo 'Run this script with sudo. The token is read from hidden stdin, never from command arguments.' >&2
  exit 1
fi
for command in curl jq systemctl; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE." >&2
  exit 1
fi
if [[ "$(stat -c '%U:%G %a' "$ENV_FILE")" != 'root:root 600' ]]; then
  echo "$ENV_FILE must be root:root 0600." >&2
  exit 1
fi
if [[ "$(grep -c '^APP_API_TOKEN=' "$ENV_FILE" || true)" != '1' ]]; then
  echo "$ENV_FILE must contain exactly one APP_API_TOKEN line." >&2
  exit 1
fi

if [[ -t 0 ]]; then
  read -r -s -p 'New permanent access token (32-128 letters, digits, _ or -): ' ACCESS_TOKEN
  echo
else
  IFS= read -r ACCESS_TOKEN
fi
if [[ ! "$ACCESS_TOKEN" =~ ^[A-Za-z0-9_-]{32,128}$ ]]; then
  unset ACCESS_TOKEN
  echo 'Token must contain 32-128 letters, digits, underscores, or hyphens.' >&2
  exit 2
fi
normalized_token="${ACCESS_TOKEN,,}"
if [[ "$normalized_token" == *replace* || "$normalized_token" == *change-me* ]]; then
  unset ACCESS_TOKEN normalized_token
  echo 'Token must not contain placeholder words such as replace or change-me.' >&2
  exit 2
fi
unset normalized_token

work_dir="$(mktemp -d /run/cosmetics-token.XXXXXX)"
chmod 0700 "$work_dir"
candidate="$(mktemp /etc/cosmetics-ai/.cosmetics-ai.env.XXXXXX)"
env_replaced=false
completed=false
finish() {
  status=$?
  trap - EXIT
  set +e
  if (( status != 0 )) && [[ "$env_replaced" == true && "$completed" != true ]]; then
    install -o root -g root -m 0600 "$work_dir/original.env" "$ENV_FILE"
    systemctl restart cosmetics-ai.service >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work_dir"
  rm -f -- "$candidate"
  exit "$status"
}
trap finish EXIT

install -o root -g root -m 0600 "$ENV_FILE" "$work_dir/original.env"
chmod 0600 "$candidate"
chown root:root "$candidate"
replaced=0
while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ "$line" == APP_API_TOKEN=* ]]; then
    printf 'APP_API_TOKEN=%s\n' "$ACCESS_TOKEN" >>"$candidate"
    replaced=$((replaced + 1))
  else
    printf '%s\n' "$line" >>"$candidate"
  fi
done <"$ENV_FILE"
if [[ "$replaced" != '1' ]]; then
  unset ACCESS_TOKEN
  echo 'Refusing to replace an ambiguous APP_API_TOKEN line.' >&2
  exit 1
fi

mv "$candidate" "$ENV_FILE"
env_replaced=true
systemctl restart cosmetics-ai.service

healthy=false
for _ in {1..12}; do
  health_body="$(
    printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN" |
      curl --fail --silent --show-error \
        --max-time 20 \
        --header @- \
        http://127.0.0.1:8787/api/health \
        2>/dev/null || true
  )"
  if jq -e '.status == "ready" and .modelInstalled == true and .ocrReady == true' \
    >/dev/null 2>&1 <<<"$health_body"; then
    healthy=true
    break
  fi
  sleep 2
done

if [[ "$healthy" != true ]]; then
  unset ACCESS_TOKEN
  echo 'The new token failed the readiness check; the previous token was restored.' >&2
  exit 1
fi

unset ACCESS_TOKEN
completed=true
echo 'Permanent access token updated; previously authorized browsers will need the new value.'
