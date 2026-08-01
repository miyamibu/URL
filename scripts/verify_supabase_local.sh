#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [[ "${CI:-false}" != "true" && "${URLSAVER_ALLOW_LOCAL_SUPABASE_RESET:-false}" != "true" ]]; then
  echo "SKIP local Supabase validation: set CI=true or URLSAVER_ALLOW_LOCAL_SUPABASE_RESET=true for an isolated database"
  exit 0
fi

if [[ -n "${SUPABASE_DB_URL:-}" || "${URLSAVER_SUPABASE_LINKED:-false}" == "true" ]]; then
  echo "FAIL refusing Supabase validation with a linked or explicitly supplied database URL" >&2
  exit 1
fi

if ! command -v supabase >/dev/null 2>&1; then
  echo "FAIL supabase CLI is required for local SQL/RLS validation" >&2
  exit 1
fi

cleanup() {
  supabase stop --project-id rinbam >/dev/null 2>&1 || true
}
trap cleanup EXIT

supabase start \
  --exclude edge-runtime,imgproxy,logflare,mailpit,postgres-meta,realtime,storage-api,studio,supavisor,vector
supabase db reset --local --yes --no-seed
supabase db lint --local --schema public,private --level error --fail-on error
supabase test db --local supabase/tests

echo "OK local Supabase migration, SQL/RLS lint, and pgTAP tests passed"
