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
assetlinks_json="$tmp_dir/assetlinks.json"
aasa_json="$tmp_dir/apple-app-site-association"

fetch_page "/privacy/" "$privacy_html"
fetch_page "/account-deletion/" "$account_html"
fetch_page "/.well-known/assetlinks.json" "$assetlinks_json"
fetch_page "/.well-known/apple-app-site-association" "$aasa_json"

check_contains "$privacy_html" "Standard / Pro" "privacy discloses paid plans"
check_contains "$privacy_html" "Google Play Billing" "privacy discloses Google Play Billing"
check_contains "$privacy_html" "StoreKit" "privacy discloses StoreKit"
check_not_contains "$privacy_html" "本物の課金も行いません" "privacy has no stale no-real-billing sentence"
check_not_contains "$privacy_html" "本物の課金、" "privacy has no stale no-real-billing summary"

check_contains "$account_html" "URL Saver アカウント削除" "account deletion title"
check_contains "$account_html" "共有タグクラウド" "account deletion cloud account wording"

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
