#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

failures=0

fail() {
  printf 'FAIL %s\n' "$1" >&2
  failures=$((failures + 1))
}

pass() {
  printf 'OK %s\n' "$1"
}

tracked_forbidden_regex='(^|/)(local\.properties|.*\.ipa|.*\.dSYM(/.*)?|.*\.mobileprovision|.*\.xcarchive(/.*)?|.*\.db|.*\.sqlite|.*\.sqlite3|.*\.tgz|.*\.tar\.gz)$|^ios/build/|^app/build/|^build/|^ios/Config/URLSaverSecrets\.xcconfig$'
tracked_forbidden="$(git ls-files | grep -E "$tracked_forbidden_regex" || true)"
if [[ -n "$tracked_forbidden" ]]; then
  fail "tracked forbidden release artifact or secret-like file:"
  printf '%s\n' "$tracked_forbidden" >&2
else
  pass "no tracked forbidden release artifacts"
fi

if [[ -f ios/Config/URLSaverSecrets.local-only.xcconfig ]]; then
  if grep -Eq 'URLSAVER_SHARED_TAG_CLOUD_ENABLED[[:space:]]*=[[:space:]]*true' ios/Config/URLSaverSecrets.local-only.xcconfig; then
    fail "local-only xcconfig enables shared tag cloud"
  else
    pass "local-only xcconfig keeps shared tag cloud disabled"
  fi
  if grep -Eq 'URLSAVER_SUPABASE_(URL|ANON_KEY)[[:space:]]*=[[:space:]]*[^[:space:]]+' ios/Config/URLSaverSecrets.local-only.xcconfig; then
    fail "local-only xcconfig contains Supabase values"
  else
    pass "local-only xcconfig contains no Supabase values"
  fi
fi

privacy_required_reason_usage_found=false
if grep -R -E -q '(@AppStorage|UserDefaults)' ios/URLSaverShared ios/URLSaveriOS ios/URLSaverShareExtension; then
  privacy_required_reason_usage_found=true
fi

for privacy_manifest in ios/URLSaveriOS/PrivacyInfo.xcprivacy ios/URLSaverShareExtension/PrivacyInfo.xcprivacy; do
  if [[ ! -f "$privacy_manifest" ]]; then
    fail "NO_GO missing iOS privacy manifest: $privacy_manifest"
  elif ! python3 - "$privacy_manifest" >/dev/null 2>&1 <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as handle:
    plistlib.load(handle)
PY
  then
    fail "NO_GO invalid iOS privacy manifest: $privacy_manifest"
  else
    accessed_api_block="$(grep -A1 -F '<key>NSPrivacyAccessedAPITypes</key>' "$privacy_manifest" || true)"
    if [[ "$privacy_required_reason_usage_found" == true ]] && printf '%s\n' "$accessed_api_block" | grep -Eq '<array[[:space:]]*/>'; then
      fail "NO_GO iOS required-reason API usage is present but no approved reason is recorded in $privacy_manifest; do not guess a reason code"
    else
      pass "iOS privacy manifest is valid and has required-reason entries or no detected covered API usage: $privacy_manifest"
    fi
  fi
done

if python3 scripts/test_public_web_contract.py; then
  pass "public reset/invite pages have matching strict CSP, recovery paths, and local responses"
else
  fail "NO_GO public reset/invite web contract verification failed"
fi

grep -q 'applicationId = "jp.miyamibu.urlalbum"' app/build.gradle.kts \
  && pass "canonical Android applicationId is configured" \
  || fail "canonical Android applicationId is missing"

grep -q 'buildConfigField("boolean", "ADS_ENABLED", "false")' app/build.gradle.kts \
  && pass "release ADS_ENABLED=false is configured" \
  || fail "release ADS_ENABLED=false is missing"

if rg -n 'com\.google\.android\.gms\.ads|play-services-ads' app/src/main app/build.gradle.kts >/dev/null 2>&1; then
  fail "NO_GO release source still references Google Mobile Ads"
else
  pass "release source has no Google Mobile Ads dependency or type reference"
fi

grep -q 'tools:node="remove"' app/src/release/AndroidManifest.xml \
  && pass "release manifest removes debug/ad-only declarations" \
  || fail "release manifest removal rules are missing"

if python3 scripts/verify_release_manifest.py; then
  pass "release manifest matches Android/iOS sources, migration head, and current release docs"
else
  fail "release manifest does not match Android/iOS sources, migration head, or current release docs"
fi

[[ -f docs/release/release-manifest.json ]] \
  && pass "machine-readable release manifest exists" \
  || fail "machine-readable release manifest is missing"

[[ -f docs/release/launch-go-checklist.md ]] \
  && pass "current release readiness checklist exists" \
  || fail "current release readiness checklist is missing"

if [[ "$failures" -gt 0 ]]; then
  printf 'FAIL release hygiene: %s issue(s)\n' "$failures" >&2
  exit 1
fi

printf 'OK release hygiene checks passed\n'
