#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat >&2 <<'USAGE'
Usage: bash scripts/verify_clean_review_archive.sh PATH_TO_ZIP

Inspect one generated clean-review ZIP by extracting it into a private temporary
directory outside the repository. The verifier prints paths and counts only; it
never prints archive contents.
USAGE
}

if [[ "$#" -ne 1 || "$1" == "--help" || "$1" == "-h" ]]; then
  usage
  [[ "$#" -eq 1 ]] && exit 0
  exit 2
fi

archive_path="$1"
if [[ ! -f "$archive_path" ]]; then
  printf 'FAIL clean review archive is missing: %s\n' "$archive_path" >&2
  exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
  printf 'FAIL unzip is required to inspect the clean review archive\n' >&2
  exit 1
fi

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/urlsaver-clean-review-check.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT
members="$tmpdir/members.txt"
duplicates="$tmpdir/duplicates.txt"
extract_dir="$tmpdir/extracted"

if ! unzip -tq "$archive_path" >/dev/null 2>&1; then
  printf 'FAIL clean review archive is not a valid ZIP: %s\n' "$archive_path" >&2
  exit 1
fi

unzip -Z1 "$archive_path" >"$members"
if [[ ! -s "$members" ]]; then
  printf 'FAIL clean review archive is empty: %s\n' "$archive_path" >&2
  exit 1
fi

sort "$members" | uniq -d >"$duplicates"
if [[ -s "$duplicates" ]]; then
  printf '%s\n' "$duplicates" >&2
  printf 'FAIL clean review archive contains duplicate members\n' >&2
  exit 1
fi

forbidden_members='(^|/)(\.git/|\.claude/|\.codex/|\.idea/|node_modules/|\.next/|\.vercel/|artifacts/|\.env($|[^/]*)|local\.properties$|URLSaverSecrets\.xcconfig$|.*\.(db|sqlite|sqlite3|tgz|tar\.gz|ipa|mobileprovision|xcarchive|dSYM)(/|$))'
if grep -E -q -- "$forbidden_members" "$members"; then
  grep -E -- "$forbidden_members" "$members" >&2
  printf 'FAIL clean review archive contains a forbidden path\n' >&2
  exit 1
fi

if grep -E -q -- '(^/|(^|/)\.\.?/|(^|/)\.\./)' "$members"; then
  grep -E -- '(^/|(^|/)\.\.?/|(^|/)\.\./)' "$members" >&2
  printf 'FAIL clean review archive contains an absolute or traversal path\n' >&2
  exit 1
fi

mkdir -p "$extract_dir"
if ! unzip -q "$archive_path" -d "$extract_dir"; then
  printf 'FAIL clean review archive could not be extracted into its private temporary directory\n' >&2
  exit 1
fi
if find "$extract_dir" -type l -print -quit | grep -q .; then
  printf 'FAIL clean review archive contains a symlink; archive contents were not read\n' >&2
  exit 1
fi

required_members=(
  "URLSaver/.github/workflows/ci.yml"
  "URLSaver/scripts/check_launch_readiness.sh"
  "URLSaver/scripts/check_release_hygiene.sh"
  "URLSaver/docs/release/launch-status-model.md"
)
for member in "${required_members[@]}"; do
  if ! grep -F -x -q -- "$member" "$members"; then
    printf 'FAIL clean review archive is missing required member: %s\n' "$member" >&2
    exit 1
  fi
  member_path="$extract_dir/$member"
  if [[ ! -f "$member_path" ]]; then
    printf 'FAIL clean review archive required member is not a regular file: %s\n' "$member" >&2
    exit 1
  fi
  member_size="$(wc -c <"$member_path" | tr -d '[:space:]')"
  if [[ "${member_size:-0}" -eq 0 ]]; then
    printf 'FAIL clean review archive contains an empty required member: %s\n' "$member" >&2
    exit 1
  fi
done

contains_private_key_material() {
  python3 -c '
import re
import sys

header = rb"-----BEGIN[ \t]+(?:RSA|EC|OPENSSH|DSA|PRIVATE)[ \t]+KEY-----"
footer = rb"-----END[ \t]+(?:RSA|EC|OPENSSH|DSA|PRIVATE)[ \t]+KEY-----"
pem = re.compile(header + rb"(?P<body>.*?)" + footer, re.DOTALL)
data = sys.stdin.buffer.read()
for match in pem.finditer(data):
    body = re.sub(rb"[^A-Za-z0-9+/=]", b"", match.group("body"))
    if len(body) >= 40:
        sys.exit(0)
sys.exit(1)
'
}

scan_patterns=(
  'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}'
  '(SUPABASE_SERVICE_ROLE_KEY|OPENAI_API_KEY|RESEND_API_KEY|GOOGLE_PLAY_SERVICE_ACCOUNT_JSON)[[:space:]]*[:=][[:space:]]*[A-Za-z0-9+/=_-]{24,}'
)
while IFS= read -r member; do
  case "$member" in
    *.md|*.sh|*.py|*.kt|*.swift|*.ts|*.tsx|*.js|*.json|*.yaml|*.yml|*.toml|*.xml|*.plist|*.xcconfig|*.txt|*.html|*.sql|*.properties|*.gradle|*.gradle.kts|*.rb)
      member_path="$extract_dir/$member"
      if [[ ! -f "$member_path" ]]; then
        continue
      fi
      if contains_private_key_material <"$member_path"; then
        printf 'FAIL clean review archive contains high-confidence private-key material in: %s\n' "$member" >&2
        exit 1
      fi
      for pattern in "${scan_patterns[@]}"; do
        if LC_ALL=C grep -E -I -q -- "$pattern" "$member_path"; then
          printf 'FAIL clean review archive contains a high-confidence secret marker in: %s\n' "$member" >&2
          exit 1
        fi
      done
      ;;
  esac
done <"$members"

member_count="$(wc -l <"$members" | tr -d '[:space:]')"
printf 'OK clean review archive verified: %s members=%s\n' "$archive_path" "${member_count:-0}"
