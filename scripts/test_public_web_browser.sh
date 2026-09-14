#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

chrome="${CHROME_BIN:-}"
if [[ -z "$chrome" ]]; then
  for candidate in \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    "$(command -v google-chrome 2>/dev/null || true)" \
    "$(command -v chromium 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      chrome="$candidate"
      break
    fi
  done
fi

if [[ -z "$chrome" || ! -x "$chrome" ]]; then
  printf 'SKIP Chrome/Chromium is unavailable; set CHROME_BIN to run the browser smoke.\n'
  exit 2
fi

preview_port="${RINBAM_WEB_PREVIEW_PORT:-4178}"
temp_dir="$(mktemp -d)"
server_pid=""
base_url="${BASE_URL:-}"
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT

if [[ -z "$base_url" ]]; then
  base_url="http://127.0.0.1:$preview_port"
  python3 scripts/serve_public_web_preview.py --port "$preview_port" --quiet >"$temp_dir/server.log" 2>&1 &
  server_pid="$!"
  for _ in {1..50}; do
    if curl -fsS "$base_url/" >/dev/null; then
      break
    fi
    sleep 0.1
  done
else
  base_url="${base_url%/}"
fi
curl -fsS "$base_url/" >/dev/null

dump_dom() {
  local url="$1"
  local output="$2"
  "$chrome" \
    --headless=new \
    --disable-gpu \
    --disable-background-networking \
    --disable-component-update \
    --disable-breakpad \
    --disable-crash-reporter \
    --disable-default-apps \
    --disable-domain-reliability \
    --disable-sync \
    --metrics-recording-only \
    --no-pings \
    --no-first-run \
    --no-default-browser-check \
    --user-data-dir="$temp_dir/chrome-profile-${output##*/}" \
    --virtual-time-budget=1000 \
    --dump-dom "$url" >"$output" 2>>"$temp_dir/chrome.log" &
  local chrome_pid="$!"
  local completed=false
  for _ in {1..80}; do
    if ! kill -0 "$chrome_pid" 2>/dev/null; then
      completed=true
      break
    fi
    sleep 0.1
  done
  if [[ "$completed" == false ]]; then
    kill -TERM "$chrome_pid" 2>/dev/null || true
  fi
  wait "$chrome_pid" 2>/dev/null || true
  if [[ ! -s "$output" ]]; then
    printf 'FAIL Chrome could not render %s\n' "$url" >&2
    tail -n 20 "$temp_dir/chrome.log" >&2
    exit 1
  fi
}

assert_contains() {
  local file="$1"
  local expected="$2"
  local label="$3"
  if ! grep -Fq "$expected" "$file"; then
    printf 'FAIL %s\n' "$label" >&2
    exit 1
  fi
}

assert_matches() {
  local file="$1"
  local expected="$2"
  local label="$3"
  if ! grep -Eq "$expected" "$file"; then
    printf 'FAIL %s\n' "$label" >&2
    exit 1
  fi
}

dump_dom "$base_url/auth/reset-password" "$temp_dir/reset.html"
assert_contains "$temp_dir/reset.html" 'id="status" class="notice error"' "reset invalid-link state is rendered"
assert_contains "$temp_dir/reset.html" 'もう一度メールを送信してください' "reset page explains recovery"
printf 'PASS browser renders reset invalid-link recovery instead of hanging\n'

dump_dom "$base_url/auth/reset-password#access_token=ordinary-browser-token&token_type=bearer" "$temp_dir/reset-ordinary-token.html"
assert_contains "$temp_dir/reset-ordinary-token.html" 'id="status" class="notice error"' "ordinary access token is rejected"
assert_matches "$temp_dir/reset-ordinary-token.html" 'id="form"[^>]*hidden' "ordinary access token keeps the reset form hidden"
printf 'PASS browser rejects a non-recovery implicit access token\n'

dump_dom "$base_url/invite/browser-smoke-token" "$temp_dir/invite-valid.html"
assert_contains "$temp_dir/invite-valid.html" 'href="urlsaver://invite/browser-smoke-token"' "valid invite builds the app deep link"
if grep -Eq 'id="invite-actions"[^>]*hidden' "$temp_dir/invite-valid.html"; then
  printf 'FAIL browser kept valid invite actions hidden\n' >&2
  exit 1
fi
assert_contains "$temp_dir/invite-valid.html" 'App Store' "valid invite renders App Store recovery"
assert_contains "$temp_dir/invite-valid.html" 'Google Play' "valid invite renders Google Play recovery"
assert_contains "$temp_dir/invite-valid.html" '招待リンクをコピー' "valid invite renders copy recovery"
printf 'PASS browser renders app/store/copy/manual invite recovery\n'

dump_dom "$base_url/invite/" "$temp_dir/invite-invalid.html"
assert_contains "$temp_dir/invite-invalid.html" '招待情報を確認できません' "invalid invite renders an explicit error"
assert_matches "$temp_dir/invite-invalid.html" 'id="invite-actions"[^>]*hidden' "invalid invite keeps actions hidden"
printf 'PASS browser keeps invalid invite actions unavailable\n'

printf 'PASS public web browser smoke\n'
