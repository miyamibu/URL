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

tracked_forbidden="$(git ls-files | grep -E -i '(^|/)(\.env($|\.)|.*\.(pem|p12|jks|mobileprovision|ipa|xcarchive|dSYM|db|sqlite|sqlite3))$' || true)"
if [[ -n "$tracked_forbidden" ]]; then
  printf '%s\n' "$tracked_forbidden" >&2
  fail "tracked secret or data-bearing file path"
else
  pass "no tracked secret or data-bearing file path"
fi

scan_tracked() {
  local label="$1"
  local pattern="$2"
  local hits
  hits="$(git grep -I -l -E -e "$pattern" -- . 2>/dev/null || true)"
  if [[ -n "$hits" ]]; then
    printf '%s\n' "$hits" >&2
    fail "tracked files contain a high-confidence $label marker"
  fi
}

scan_private_key_material() {
  local hits
  hits="$(git ls-files -z | python3 -c '
import os
import pathlib
import re
import sys

header = rb"-----BEGIN[ \t]+(?:RSA|EC|OPENSSH|DSA|PRIVATE)[ \t]+KEY-----"
footer = rb"-----END[ \t]+(?:RSA|EC|OPENSSH|DSA|PRIVATE)[ \t]+KEY-----"
pem = re.compile(header + rb"(?P<body>.*?)" + footer, re.DOTALL)

for raw_path in sys.stdin.buffer.read().split(b"\0"):
    if not raw_path:
        continue
    path = pathlib.Path(os.fsdecode(raw_path))
    try:
        data = path.read_bytes()
    except OSError:
        continue
    for match in pem.finditer(data):
        body = re.sub(rb"[^A-Za-z0-9+/=]", b"", match.group("body"))
        if len(body) >= 40:
            print(path)
            break
')"
  if [[ -n "$hits" ]]; then
    printf '%s\n' "$hits" >&2
    fail "tracked files contain high-confidence private-key material"
  fi
}

scan_private_key_material
scan_tracked "JWT" 'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}'
scan_tracked "secret assignment" '(SUPABASE_SERVICE_ROLE_KEY|OPENAI_API_KEY|RESEND_API_KEY|GOOGLE_PLAY_SERVICE_ACCOUNT_JSON)[[:space:]]*[:=][[:space:]]*[A-Za-z0-9+/=_-]{24,}'

if [[ "$failures" -gt 0 ]]; then
  printf 'FAIL secret hygiene: %s issue(s)\n' "$failures" >&2
  exit 1
fi

printf 'OK secret hygiene checks passed\n'
