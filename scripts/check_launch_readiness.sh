#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Usage: bash scripts/check_launch_readiness.sh [--level repo|internal|launch]

Levels:
  repo      Repo-local contract checks for PR/CI. Does not require a clean
            worktree, main branch, origin/main alignment, or current evidence.
  internal  Repo checks plus a clean worktree and current REPO_GO evidence.
            This is not INTERNAL_TEST_GO; device/store evidence remains manual.
  launch    Repo checks plus clean reviewed main/origin alignment and current
            REPO_GO evidence. This is LAUNCH_READY_REPO, not LAUNCH_GO.
USAGE
}

level="repo"
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --level)
      if [[ "$#" -lt 2 ]]; then
        printf 'FAIL --level requires repo, internal, or launch\n' >&2
        exit 2
      fi
      level="$2"
      shift 2
      ;;
    --level=*)
      level="${1#*=}"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'FAIL unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$level" in
  repo|internal|launch) ;;
  *)
    printf 'FAIL invalid readiness level: %s\n' "$level" >&2
    usage >&2
    exit 2
    ;;
esac

failures=0
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/urlsaver-launch-readiness.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}

ok() {
  echo "OK $1"
}

if [[ "$level" != "repo" ]]; then
  if [[ -n "$(git status --porcelain)" ]]; then
    fail "working tree is dirty; freeze and classify all changes before $level readiness"
  else
    ok "working tree is clean for $level readiness"
  fi
else
  ok "repo level does not require a clean worktree"
fi

if [[ "$level" == "launch" ]]; then
  current_branch="$(git symbolic-ref --short -q HEAD || echo "DETACHED")"
  if [[ "$current_branch" == "main" ]]; then
    ok "current branch is main"
  else
    fail "current branch is '$current_branch'; launch level requires reviewed main"
  fi

  read -r ahead behind < <(git rev-list --left-right --count HEAD...origin/main 2>/dev/null || echo "0 0")
  if [[ "${ahead:-0}" -ne 0 || "${behind:-0}" -ne 0 ]]; then
    fail "current main differs from origin/main (ahead=${ahead:-0}, behind=${behind:-0}); launch level requires aligned reviewed history"
  else
    ok "current main matches origin/main"
  fi
fi

active_untracked="$(git ls-files --others --exclude-standard | rg '^(app/src/main/|ios/|web/|supabase/|scripts/)' | rg -v '^(app/build/|ios/build/)' || true)"
if [[ -n "$active_untracked" ]]; then
  printf '%s\n' "$active_untracked"
  fail "active source files are untracked"
else
  ok "no active untracked source files"
fi

require_file() {
  local path="$1"
  if [[ -f "$path" ]]; then
    ok "found $path"
  else
    fail "missing $path"
  fi
}

repo_required_files=(
  "docs/release/repo-go-evidence.md"
  "docs/release/launch-status-model.md"
  "scripts/check_release_hygiene.sh"
  "scripts/check_secret_hygiene.sh"
  "scripts/create_clean_review_archive.sh"
  "scripts/verify_clean_review_archive.sh"
  "scripts/verify_mcp_contract.py"
  "scripts/verify_mobile_ui_contract.py"
  "scripts/verify_canonical_story_tracker.py"
  "scripts/verify_chapter13_fixture.py"
)

internal_required_files=(
  "docs/release/launch-go-checklist.md"
  "docs/release/manual-qa-matrix.md"
  "docs/release/android-internal-testing-checklist.md"
  "docs/release/ios-testflight-checklist.md"
)

launch_required_files=(
  "docs/release/staging-deploy-checklist.md"
  "docs/release/privacy-policy-and-store-disclosure-checklist.md"
  "docs/release/production-secrets-and-flags.md"
  "docs/release/rollback-plan.md"
  "docs/ai/mcp-staging-smoke-test.md"
  "docs/ai/openai-apps-developer-mode-test-plan.md"
  "docs/ai/openai-submission-readiness.md"
  "scripts/smoke_mcp_staging.sh"
)

for path in "${repo_required_files[@]}"; do
  require_file "$path"
done

if [[ "$level" != "repo" ]]; then
  for path in "${internal_required_files[@]}"; do
    require_file "$path"
  done
fi

if [[ "$level" == "launch" ]]; then
  for path in "${launch_required_files[@]}"; do
    require_file "$path"
  done
fi

if [[ "$level" == "repo" ]]; then
  ok "repo level does not promote evidence into REPO_GO or launch status"
else
  if grep -q "Final status: REPO_GO" docs/release/repo-go-evidence.md 2>/dev/null; then
    ok "REPO_GO evidence status recorded"
  else
    fail "repo-go evidence does not record Final status: REPO_GO"
  fi

  today="$(date +%F)"
  if rg -q "$today" docs/release/repo-go-evidence.md 2>/dev/null; then
    ok "REPO_GO evidence is dated today"
  else
    fail "REPO_GO evidence is stale for today ($today)"
  fi
fi

if git diff --check; then
  ok "git diff has no whitespace errors"
else
  fail "git diff contains whitespace errors"
fi

if rg -q 'manual single-file apply from' supabase/migrations -g '*.sql' 2>/dev/null; then
  fail "placeholder migration blocks fresh replay"
else
  ok "no placeholder migration blocks fresh replay"
fi

if python3 - <<'PY'
import json
from pathlib import Path

manifest = Path("artifacts/store-assets/screenshots/2026-05-13/manifest.json")
data = json.loads(manifest.read_text(encoding="utf-8"))
missing = []
for platform in ("android", "ios"):
    section = data.get(platform, {})
    directory = Path(section.get("directory", ""))
    for item in section.get("screenshots", []):
        filename = item.get("file")
        if not filename:
            continue
        if not (directory / filename).is_file():
            missing.append(str(directory / filename))
if missing:
    print("\n".join(missing))
    raise SystemExit(1)
PY
then
  ok "all manifest screenshot references exist"
else
  fail "manifest contains missing screenshot references"
fi

if find . -maxdepth 1 -type f \( -name '*clean-review*.zip' -o -name '*review*.zip' \) | grep -q .; then
  fail "review archive exists in repo root"
else
  ok "no review archive in repo root"
fi

if find . \
  -path './.git' -prune -o \
  -path './build' -prune -o \
  -path './app/build' -prune -o \
  -path './web/admin/node_modules' -prune -o \
  -type f -name '.env.production' -print | grep -q .; then
  fail ".env.production exists in repo"
else
  ok "no .env.production file"
fi

if rg -n 'contentReference\[oaicite|oaicite' docs .agents/skills AGENTS.md >"$tmpdir/oaicite-check.txt" 2>/dev/null; then
  cat "$tmpdir/oaicite-check.txt"
  fail "invalid citation marker found"
else
  ok "no invalid citation marker"
fi

if [[ -f scripts/check_release_hygiene.sh ]]; then
  bash scripts/check_release_hygiene.sh || fail "release hygiene failed"
fi

if [[ -f scripts/check_secret_hygiene.sh ]]; then
  bash scripts/check_secret_hygiene.sh || fail "secret hygiene failed"
fi

if [[ -n "${URLSAVER_CLEAN_REVIEW_ARCHIVE:-}" ]]; then
  if [[ -f scripts/verify_clean_review_archive.sh ]]; then
    bash scripts/verify_clean_review_archive.sh "$URLSAVER_CLEAN_REVIEW_ARCHIVE" || fail "clean review archive verification failed"
  else
    fail "clean review archive verifier is missing"
  fi
else
  ok "clean review archive is not created by readiness checks; use the explicit archive command"
fi

if [[ -f scripts/verify_mcp_contract.py ]]; then
  python3 scripts/verify_mcp_contract.py || fail "MCP contract failed"
fi

if [[ -f scripts/verify_mobile_ui_contract.py ]]; then
  python3 scripts/verify_mobile_ui_contract.py || fail "mobile UI contract failed"
fi

if [[ -f scripts/verify_canonical_story_tracker.py ]]; then
  python3 scripts/verify_canonical_story_tracker.py || fail "canonical story tracker failed"
fi

if [[ -f scripts/verify_chapter13_fixture.py ]]; then
  python3 scripts/verify_chapter13_fixture.py || fail "Chapter 13 fixture parity failed"
fi

if [[ "$failures" -gt 0 ]]; then
  echo "NO_GO_INTERNAL level=$level readiness failures=$failures"
  exit 1
fi

case "$level" in
  repo)
    echo "REPO_VALIDATION_PASS checks passed"
    ;;
  internal)
    echo "INTERNAL_TEST_REPO_READY checks passed; INTERNAL_TEST_GO remains manual"
    ;;
  launch)
    echo "LAUNCH_READY_REPO checks passed; LAUNCH_GO remains external"
    ;;
esac
