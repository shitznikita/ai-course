#!/usr/bin/env bash
set -euo pipefail

APP_URL="${APP_URL:-http://127.0.0.1:8787}"
OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
OLLAMA_MODEL="${OLLAMA_MODEL:-qwen3:4b}"
failures=0
warnings=0

pass() {
  printf '[PASS] %s\n' "$1"
}

warn() {
  printf '[WARN] %s\n' "$1" >&2
  warnings=$((warnings + 1))
}

fail() {
  printf '[FAIL] %s\n' "$1" >&2
  failures=$((failures + 1))
}

check_command() {
  if command -v "$1" >/dev/null 2>&1; then
    pass "$1 is installed"
  else
    fail "$1 is not installed"
  fi
}

echo 'Day 30 VPS diagnostics'
echo "Application: $APP_URL"
echo "Ollama:     $OLLAMA_BASE_URL"
echo

check_command curl
check_command jq
check_command java
check_command tesseract

if command -v java >/dev/null 2>&1; then
  java_version="$(java -version 2>&1 | head -n 1)"
  if [[ "$java_version" == *'version "21.'* || "$java_version" == *' 21.'* ]]; then
    pass "Java 21 is active ($java_version)"
  else
    fail "Java 21 is required; found $java_version"
  fi
fi

if command -v tesseract >/dev/null 2>&1; then
  languages="$(tesseract --list-langs 2>/dev/null || true)"
  if grep -qx 'eng' <<<"$languages" && grep -qx 'rus' <<<"$languages"; then
    pass 'Tesseract eng and rus data are installed'
  else
    fail 'Tesseract requires both eng and rus language data'
  fi
fi

if [[ -r /proc/meminfo ]]; then
  memory_kib="$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)"
  if [[ -n "$memory_kib" ]] && (( memory_kib >= 7 * 1024 * 1024 )); then
    pass "RAM is sufficient ($((memory_kib / 1024)) MiB total)"
  else
    warn "Less than 7 GiB RAM detected; qwen3:4b may be unstable"
  fi
fi

if command -v systemctl >/dev/null 2>&1; then
  for service in ollama.service cosmetics-ai.service; do
    if systemctl is-active --quiet "$service" 2>/dev/null; then
      pass "$service is active"
    else
      fail "$service is not active"
    fi
  done

  ollama_environment="$(systemctl show ollama.service --property=Environment --value 2>/dev/null || true)"
  if [[ "$ollama_environment" == *'OLLAMA_HOST=127.0.0.1:11434'* ]]; then
    pass 'Ollama systemd environment binds to loopback'
  else
    fail 'Ollama systemd environment does not enforce loopback OLLAMA_HOST'
  fi
fi

if command -v ss >/dev/null 2>&1; then
  ollama_listener_count=0
  while IFS= read -r address; do
    [[ -n "$address" ]] || continue
    ollama_listener_count=$((ollama_listener_count + 1))
    case "$address" in
      127.0.0.1:11434|\[::1\]:11434|\[::ffff:127.0.0.1\]:11434)
        pass "Ollama listener is private ($address)"
        ;;
      *)
        fail "Ollama is exposed by listener $address"
        ;;
    esac
  done < <(ss -H -ltn | awk '$4 ~ /:11434$/ { print $4 }')
  if (( ollama_listener_count == 0 )); then
    fail 'No Ollama listener found on port 11434'
  fi

  while IFS= read -r address; do
    [[ -n "$address" ]] || continue
    case "$address" in
      127.0.0.1:8787|\[::1\]:8787|\[::ffff:127.0.0.1\]:8787)
        pass "Application listener is private ($address)"
        ;;
      *)
        fail "Application bypasses the private proxy boundary at $address"
        ;;
    esac
  done < <(ss -H -ltn | awk '$4 ~ /:8787$/ { print $4 }')
else
  warn 'ss is unavailable; listener exposure could not be checked'
fi

if command -v curl >/dev/null 2>&1; then
  health_response="$(curl --silent --show-error --write-out $'\n%{http_code}' \
    --max-time 10 "$APP_URL/api/health/live" || true)"
  health_status="${health_response##*$'\n'}"
  health_body="${health_response%$'\n'*}"
  if [[ "$health_status" == '200' ]]; then
    if command -v jq >/dev/null 2>&1 && jq -e '.status == "alive"' >/dev/null <<<"$health_body"; then
      pass 'Application liveness reports an active process'
    else
      fail 'Application liveness returned HTTP 200 with an invalid contract'
    fi
  else
    fail "Application liveness returned ${health_status:-no response}"
  fi

  tags_body="$(curl --silent --show-error --max-time 10 "$OLLAMA_BASE_URL/api/tags" || true)"
  if [[ -n "$tags_body" ]]; then
    pass 'Ollama tags endpoint is reachable'
    if command -v jq >/dev/null 2>&1; then
      if jq -e --arg model "$OLLAMA_MODEL" \
        '.models[]? | select(.name == $model or .model == $model or .name == ($model + ":latest"))' \
        >/dev/null <<<"$tags_body"; then
        pass "Configured model is installed ($OLLAMA_MODEL)"
      else
        fail "Configured model is not installed ($OLLAMA_MODEL)"
      fi
    else
      warn 'jq is unavailable; installed model name was not verified'
    fi
  else
    fail 'Ollama tags endpoint is not reachable'
  fi
fi

if [[ -e /etc/cosmetics-ai/cosmetics-ai.env ]]; then
  if [[ -r /etc/cosmetics-ai/cosmetics-ai.env ]]; then
    env_metadata="$(stat -c '%U:%G %a' /etc/cosmetics-ai/cosmetics-ai.env 2>/dev/null || true)"
  elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    env_metadata="$(sudo -n stat -c '%U:%G %a' /etc/cosmetics-ai/cosmetics-ai.env 2>/dev/null || true)"
  else
    env_metadata=''
  fi

  if [[ "$env_metadata" == 'root:root 600' ]]; then
    pass 'Server environment file is root-owned with mode 0600'
  else
    warn 'Could not verify root:root 0600 on the server environment file'
  fi
fi

echo
printf 'Diagnostics complete: %d failure(s), %d warning(s).\n' "$failures" "$warnings"
(( failures == 0 ))
