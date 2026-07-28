#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat >&2 <<'USAGE'
Usage: CI=true GITHUB_ACTIONS=true bash scripts/verify_supabase_local.sh --ci

Runs Supabase migration replay, local lint, and pgTAP only in a fresh CI
environment. It intentionally refuses to reset or stop a developer's local
Supabase project.
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
if [[ "${1:-}" != "--ci" ]]; then
  usage
  exit 2
fi
if [[ "${CI:-false}" != "true" || "${GITHUB_ACTIONS:-false}" != "true" || -z "${GITHUB_RUN_ID:-}" ]]; then
  printf 'FAIL Supabase local verification requires a GitHub Actions runner; existing local data was not touched\n' >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1; then
  printf 'FAIL Docker is required for Supabase local verification\n' >&2
  exit 1
fi

SUPABASE_CLI_VERSION="${SUPABASE_CLI_VERSION:-2.104.0}"
if command -v supabase >/dev/null 2>&1; then
  supabase_cmd=(supabase)
elif command -v npx >/dev/null 2>&1; then
  supabase_cmd=(npx --yes "supabase@${SUPABASE_CLI_VERSION}")
else
  printf 'FAIL Supabase CLI is unavailable and npx is not installed\n' >&2
  exit 1
fi

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/urlsaver-supabase-ci.XXXXXX")"
log_file="$tmpdir/command.log"
started=true

cleanup() {
  local exit_status="$1"
  if [[ "$started" == true ]]; then
    "${supabase_cmd[@]}" stop --project-id rinbam --no-backup >"$tmpdir/stop.log" 2>&1 || true
  fi
  rm -rf "$tmpdir"
  return "$exit_status"
}
trap 'cleanup "$?"' EXIT

run_step() {
  local label="$1"
  shift
  if ! "$@" >"$log_file" 2>&1; then
    printf 'FAIL Supabase %s (details suppressed to avoid exposing generated credentials)\n' "$label" >&2
    exit 1
  fi
  printf 'OK Supabase %s\n' "$label"
}

run_step "local containers started" "${supabase_cmd[@]}" start \
  --exclude kong,rest,realtime,storage,imgproxy,meta,studio,inbucket,analytics,vector,edge-runtime,functions
run_step "migration replay" "${supabase_cmd[@]}" db reset --local --no-seed
run_step "local database lint" "${supabase_cmd[@]}" db lint --local --fail-on warning
non_pgtap_files=()
while IFS= read -r path; do
  [[ -z "$path" ]] || non_pgtap_files+=("$path")
done < <(rg --files-without-match 'extensions\.plan' supabase/tests -g '*.sql' | sort)

if [[ "${#non_pgtap_files[@]}" -gt 0 ]]; then
  printf 'FAIL Supabase test fixtures without an extensions.plan:\n' >&2
  printf '%s\n' "${non_pgtap_files[@]}" >&2
  exit 1
fi

run_step "pgTAP suite" "${supabase_cmd[@]}" test db --local supabase/tests

started=false
printf 'OK Supabase local validation passed without external credentials\n'
