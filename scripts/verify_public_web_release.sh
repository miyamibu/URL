#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-https://miyamibu.xyz}"
ANDROID_PACKAGE="${ANDROID_PACKAGE:-jp.miyamibu.urlalbum}"
IOS_APP_ID="${IOS_APP_ID:-8R3B5675ZJ.com.mibu.codebridge.ios}"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

failures=0

check_contains() {
  local file="$1"
  local needle="$2"
  local label="$3"
  if grep -Fq "$needle" "$file"; then
    printf 'PASS %s\n' "$label"
  else
    printf 'FAIL %s: missing "%s"\n' "$label" "$needle"
    failures=$((failures + 1))
  fi
}

check_not_contains() {
  local file="$1"
  local needle="$2"
  local label="$3"
  if grep -Fq "$needle" "$file"; then
    printf 'FAIL %s: found stale "%s"\n' "$label" "$needle"
    failures=$((failures + 1))
  else
    printf 'PASS %s\n' "$label"
  fi
}

fetch_page() {
  local path="$1"
  local output="$2"
  local status
  status="$(curl -L -sS -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' -o "$output" -w '%{http_code}' "$BASE_URL$path")"
  if [[ "$status" == "200" ]]; then
    printf 'PASS %s HTTP 200\n' "$path"
  else
    printf 'FAIL %s HTTP %s\n' "$path" "$status"
    failures=$((failures + 1))
  fi
}

privacy_html="$tmp_dir/privacy.html"
account_html="$tmp_dir/account-deletion.html"
reset_html="$tmp_dir/reset-password.html"
reset_headers="$tmp_dir/reset-password.headers"
invite_html="$tmp_dir/invite.html"
assetlinks_json="$tmp_dir/assetlinks.json"
aasa_json="$tmp_dir/apple-app-site-association"

fetch_page "/privacy/" "$privacy_html"
fetch_page "/account-deletion/" "$account_html"
reset_status="$(curl -L -sS -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' -D "$reset_headers" -o "$reset_html" -w '%{http_code}' "$BASE_URL/auth/reset-password")"
if [[ "$reset_status" == "200" ]]; then
  printf 'PASS /auth/reset-password HTTP 200\n'
else
  printf 'FAIL /auth/reset-password HTTP %s\n' "$reset_status"
  failures=$((failures + 1))
fi
fetch_page "/invite/release-smoke-placeholder" "$invite_html"
fetch_page "/.well-known/assetlinks.json" "$assetlinks_json"
fetch_page "/.well-known/apple-app-site-association" "$aasa_json"

check_contains "$privacy_html" "Standard / Pro" "privacy discloses paid plans"
check_contains "$privacy_html" "Google Play Billing" "privacy discloses Google Play Billing"
check_contains "$privacy_html" "StoreKit" "privacy discloses StoreKit"
check_not_contains "$privacy_html" "本物の課金も行いません" "privacy has no stale no-real-billing sentence"
check_not_contains "$privacy_html" "本物の課金、" "privacy has no stale no-real-billing summary"

check_contains "$privacy_html" "りんばむ プライバシーポリシー" "privacy uses the user-facing brand"
check_contains "$account_html" "りんばむ アカウント削除" "account deletion title"
check_contains "$account_html" "共有タグクラウド" "account deletion cloud account wording"
check_contains "$reset_html" "/auth/reset-password/reset-password.js" "reset uses external JavaScript"
check_contains "$reset_html" "/auth/reset-password/styles.css" "reset uses external stylesheet"
check_not_contains "$reset_html" "<style>" "reset has no inline style"
check_contains "$invite_html" "招待リンクをコピー" "invite offers link copy recovery"
check_contains "$invite_html" "https://apps.apple.com/app/id6771251450" "invite links to the configured App Store listing"
check_contains "$invite_html" "https://play.google.com/store/apps/details?id=jp.miyamibu.urlalbum" "invite links to the configured Google Play listing"

reset_csp="$(tr -d '\r' <"$reset_headers" | grep -i '^content-security-policy:' | tail -n 1 | sed 's/^[^:]*:[[:space:]]*//')"
if [[ "$reset_csp" == *"script-src 'self' https://cdn.jsdelivr.net"* ]] \
  && [[ "$reset_csp" == *"style-src 'self'"* ]] \
  && [[ "$reset_csp" != *"unsafe-inline"* ]] \
  && [[ "$reset_csp" != *"sha256-"* ]]; then
  printf 'PASS reset response CSP matches externalized code\n'
else
  printf 'FAIL reset response CSP is missing or permits stale inline code\n'
  failures=$((failures + 1))
fi

python3 - "$assetlinks_json" "$ANDROID_PACKAGE" <<'PY'
import json
import sys

path, expected_package = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    data = json.load(handle)
packages = {
    item.get("target", {}).get("package_name")
    for item in data
    if isinstance(item, dict)
}
if expected_package not in packages:
    raise SystemExit(f"assetlinks missing package {expected_package}; found {sorted(packages)}")
print(f"PASS assetlinks includes {expected_package}")
PY

python3 - "$aasa_json" "$IOS_APP_ID" <<'PY'
import json
import sys

path, expected_app_id = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    data = json.load(handle)
details = data.get("applinks", {}).get("details", [])
app_ids = {
    item.get("appID")
    for item in details
    if isinstance(item, dict)
}
if expected_app_id not in app_ids:
    raise SystemExit(f"AASA missing appID {expected_app_id}; found {sorted(app_ids)}")
print(f"PASS AASA includes {expected_app_id}")
PY

if [[ "$failures" -gt 0 ]]; then
  printf 'FAIL public web release verification: %s issue(s)\n' "$failures"
  exit 1
fi

printf 'PASS public web release verification\n'
