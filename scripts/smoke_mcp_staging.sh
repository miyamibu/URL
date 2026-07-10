#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${MCP_STAGING_BASE_URL:-}"
TOKEN="${MCP_STAGING_BEARER_TOKEN:-}"
CONFIRM="${MCP_STAGING_CONFIRM_URL:-}"

if [[ -z "$BASE_URL" ]]; then
  echo "SKIP MCP_STAGING_BASE_URL is not set"
  exit 2
fi

if [[ -z "$TOKEN" ]]; then
  echo "SKIP MCP_STAGING_BEARER_TOKEN is not set"
  exit 2
fi

if [[ "$CONFIRM" != "I_UNDERSTAND_THIS_IS_STAGING" ]]; then
  echo "SKIP set MCP_STAGING_CONFIRM_URL=I_UNDERSTAND_THIS_IS_STAGING to run against staging"
  exit 2
fi

if [[ "$BASE_URL" != https://* ]]; then
  echo "FAIL MCP_STAGING_BASE_URL must be HTTPS"
  exit 1
fi

if [[ "$BASE_URL" =~ ^https://(www\.)?miyamibu\.xyz/?$ ]]; then
  echo "FAIL refusing to smoke test the production root URL"
  exit 1
fi

ENDPOINT="${BASE_URL%/}/api/mcp"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

curl_status() {
  local name="$1"
  local method="$2"
  local output="$3"
  shift 3
  local status
  status="$(curl -sS -o "$output" -w "%{http_code}" -X "$method" "$@" "$ENDPOINT")"
  echo "$name status=$status" >&2
  printf "%s" "$status"
}

fail_if_sensitive() {
  local file="$1"
  if grep -Eiq 'fetchedBody|rawPrompt|refresh_token|access_token|service_role|sb_secret|BEGIN PRIVATE KEY|SUPABASE_SERVICE|OPENAI_API_KEY' "$file"; then
    echo "FAIL sensitive marker appeared in sanitized MCP response"
    exit 1
  fi
}

descriptor="$TMP_DIR/descriptor.json"
descriptor_status="$(curl_status descriptor GET "$descriptor")"
if [[ "$descriptor_status" != "200" && "$descriptor_status" != "404" ]]; then
  echo "FAIL descriptor expected 200 when enabled or 404 when disabled"
  exit 1
fi
fail_if_sensitive "$descriptor"

noauth="$TMP_DIR/noauth.json"
noauth_status="$(curl_status noauth POST "$noauth" -H 'Content-Type: application/json' --data '{"tool":"search","args":{"query":"AI","limit":1}}')"
if [[ "$noauth_status" != "401" && "$noauth_status" != "404" ]]; then
  echo "FAIL noauth request must be rejected or disabled"
  exit 1
fi
fail_if_sensitive "$noauth"

invalid="$TMP_DIR/invalid.json"
invalid_status="$(curl_status invalid-token POST "$invalid" -H 'Content-Type: application/json' -H 'Authorization: Bearer invalid-staging-token' --data '{"tool":"search","args":{"query":"AI","limit":1}}')"
if [[ "$descriptor_status" == "200" && "$invalid_status" != "401" ]]; then
  echo "FAIL invalid token must return 401 when MCP is enabled"
  exit 1
fi
fail_if_sensitive "$invalid"

if [[ "$descriptor_status" == "404" ]]; then
  echo "OK MCP is disabled safely; authenticated smoke skipped"
  exit 0
fi

search="$TMP_DIR/search.json"
search_status="$(curl_status search POST "$search" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" --data '{"tool":"search","args":{"query":"AI","limit":5}}')"
if [[ "$search_status" != "200" ]]; then
  echo "FAIL authenticated search did not return 200"
  exit 1
fi
fail_if_sensitive "$search"

shared="$TMP_DIR/shared.json"
shared_status="$(curl_status shared-tag-rejection POST "$shared" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" --data '{"tool":"search","args":{"query":"tag","limit":1,"includeSharedTags":true}}')"
if [[ "$shared_status" != "400" ]]; then
  echo "FAIL includeSharedTags=true must be rejected without explicit scope"
  exit 1
fi
fail_if_sensitive "$shared"

receipt="$TMP_DIR/receipt.json"
receipt_status="$(curl_status ai-receipt POST "$receipt" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" --data '{"tool":"rinbam.get_ai_receipt","args":{"id":"staging-smoke"}}')"
if [[ "$receipt_status" != "200" && "$receipt_status" != "404" ]]; then
  echo "FAIL AI receipt metadata request returned unexpected status"
  exit 1
fi
fail_if_sensitive "$receipt"

echo "OK MCP staging smoke passed without printing token or raw body"
