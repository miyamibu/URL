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
