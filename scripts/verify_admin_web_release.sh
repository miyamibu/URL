#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-https://rinbamu-admin.vercel.app}"
if [[ "$#" -gt 1 ]]; then
  printf 'Usage: %s [base-url]\n' "$0" >&2
  exit 2
fi
BASE_URL="${BASE_URL%/}"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

failures=0

fetch_status() {
  local path="$1"
  local output="$2"
  local status
  if status="$(curl -L -sS \
    --request GET \
    --connect-timeout 10 \
    --max-time 20 \
    -H 'Cache-Control: no-cache' \
    -H 'Pragma: no-cache' \
    -o "$output" \
    -w '%{http_code}' \
    "$BASE_URL$path" 2>/dev/null)"; then
    printf '%s' "$status"
  else
    printf '000'
  fi
}

check_admin_page() {
  local path="/"
  local output="$tmp_dir/admin.html"
  local status
  status="$(fetch_status "$path" "$output")"

  if [[ "$status" != "200" ]]; then
    printf 'FAIL %s HTTP %s (管理画面を取得できませんでした)\n' "$path" "$status"
    failures=$((failures + 1))
    return
  fi

  if grep -Fq 'URL Saver 管理' "$output"; then
    printf 'PASS %s HTTP 200 (管理画面の識別文言を確認)\n' "$path"
  else
    printf 'FAIL %s HTTP 200 (管理画面の識別文言がありません)\n' "$path"
    failures=$((failures + 1))
  fi
}

check_unauthenticated_api() {
  local path="$1"
  local output="$tmp_dir/api-${2}.body"
  local status
  status="$(fetch_status "$path" "$output")"

  if [[ "$status" == "401" ]]; then
    printf 'PASS %s HTTP 401 (認証要求を確認)\n' "$path"
  else
    printf 'FAIL %s HTTP %s (認証なしGETが401ではありません)\n' "$path" "$status"
    failures=$((failures + 1))
  fi
}

check_mcp_endpoint() {
  local path="/api/mcp"
  local output="$tmp_dir/mcp.body"
  local status
  status="$(fetch_status "$path" "$output")"

  if [[ "$status" == "404" ]]; then
    printf 'PASS %s HTTP 404 (MCP無効を確認)\n' "$path"
  else
    printf 'FAIL %s HTTP %s (本番MCP default-offのHTTP 404ではありません)\n' "$path" "$status"
    failures=$((failures + 1))
  fi
}

check_admin_page
check_unauthenticated_api "/api/admin/audit" "audit"
check_unauthenticated_api "/api/admin/support" "support"
check_unauthenticated_api "/api/admin/moderation" "moderation"
check_unauthenticated_api "/api/admin/promo-codes" "promo-codes"
check_unauthenticated_api "/api/admin/users" "users"
check_unauthenticated_api "/api/admin/users/00000000-0000-0000-0000-000000000000" "user-detail"
check_mcp_endpoint

if [[ "$failures" -gt 0 ]]; then
  printf 'FAIL admin web release verification: %s issue(s)\n' "$failures"
  exit 1
fi

printf 'PASS admin web release verification\n'
