#!/usr/bin/env bash
set -euo pipefail

failures=0

fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}

ok() {
  echo "OK $1"
}

if [[ -n "$(git status --porcelain)" ]]; then
  fail "working tree is dirty; freeze and classify all changes before launch"
else
  ok "working tree is clean"
fi

current_branch="$(git symbolic-ref --short -q HEAD || echo "DETACHED")"
if [[ "$current_branch" == "main" ]]; then
  ok "current branch is main"
else
  fail "current branch is '$current_branch'; integrate the release candidate into main before launch"
fi

read -r ahead behind < <(git rev-list --left-right --count HEAD...origin/main 2>/dev/null || echo "0 0")
if [[ "${ahead:-0}" -ne 0 || "${behind:-0}" -ne 0 ]]; then
  fail "current main differs from origin/main (ahead=${ahead:-0}, behind=${behind:-0}); publish the reviewed release commit before launch"
else
  ok "current main matches origin/main"
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

required_files=(
  "docs/release/repo-go-evidence.md"
  "docs/release/launch-status-model.md"
  "docs/release/launch-go-checklist.md"
  "docs/release/manual-qa-matrix.md"
  "docs/release/staging-deploy-checklist.md"
  "docs/release/android-internal-testing-checklist.md"
  "docs/release/ios-testflight-checklist.md"
  "docs/release/privacy-policy-and-store-disclosure-checklist.md"
  "docs/release/production-secrets-and-flags.md"
  "docs/release/rollback-plan.md"
  "docs/ai/mcp-staging-smoke-test.md"
  "docs/ai/openai-apps-developer-mode-test-plan.md"
  "docs/ai/openai-submission-readiness.md"
  "scripts/smoke_mcp_staging.sh"
  "scripts/check_release_hygiene.sh"
  "scripts/create_clean_review_archive.sh"
)

for path in "${required_files[@]}"; do
  require_file "$path"
done

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

if rg -n 'contentReference\[oaicite|oaicite' docs .agents/skills AGENTS.md >/tmp/rinbam-oaicite-check.txt 2>/dev/null; then
  cat /tmp/rinbam-oaicite-check.txt
  fail "invalid citation marker found"
else
  ok "no invalid citation marker"
fi

if [[ -f scripts/check_release_hygiene.sh ]]; then
  bash scripts/check_release_hygiene.sh || fail "release hygiene failed"
fi

if [[ -f scripts/verify_mcp_contract.py ]]; then
  python3 scripts/verify_mcp_contract.py || fail "MCP contract failed"
fi

if [[ -f scripts/verify_mobile_ui_contract.py ]]; then
  python3 scripts/verify_mobile_ui_contract.py || fail "mobile UI contract failed"
fi

if [[ "$failures" -gt 0 ]]; then
  echo "NO_GO_INTERNAL launch readiness failures=$failures"
  exit 1
fi

echo "LAUNCH_READY_REPO checks passed"
