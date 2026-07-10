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

grep -q 'applicationId = "jp.miyamibu.urlalbum"' app/build.gradle.kts \
  && pass "canonical Android applicationId is configured" \
  || fail "canonical Android applicationId is missing"

grep -q 'buildConfigField("boolean", "ADS_ENABLED", "false")' app/build.gradle.kts \
  && pass "release ADS_ENABLED=false is configured" \
  || fail "release ADS_ENABLED=false is missing"

grep -q 'tools:node="remove"' app/src/release/AndroidManifest.xml \
  && pass "release manifest removes debug/ad-only declarations" \
  || fail "release manifest removal rules are missing"

grep -q 'CFBundleShortVersionString' ios/URLSaveriOS/Info.plist \
  && grep -q '<string>1.0.14</string>' ios/URLSaveriOS/Info.plist \
  && pass "iOS version baseline is present" \
  || fail "iOS version baseline check failed"

[[ -f docs/release/release-ops-readiness-2026-07-09.md ]] \
  && pass "current release readiness tracker exists" \
  || fail "current release readiness tracker is missing"

if [[ "$failures" -gt 0 ]]; then
  printf 'FAIL release hygiene: %s issue(s)\n' "$failures" >&2
  exit 1
fi

printf 'OK release hygiene checks passed\n'
