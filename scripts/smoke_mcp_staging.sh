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

initialize_body='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"rinbam-staging-smoke","version":"1.0"}}}'
descriptor="$TMP_DIR/initialize.json"
descriptor_status="$(curl_status initialize POST "$descriptor" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H "Authorization: Bearer $TOKEN" --data "$initialize_body")"
if [[ "$descriptor_status" != "200" && "$descriptor_status" != "404" ]]; then
  echo "FAIL initialize expected 200 when enabled or 404 when disabled"
  exit 1
fi
fail_if_sensitive "$descriptor"

noauth="$TMP_DIR/noauth.json"
noauth_status="$(curl_status noauth POST "$noauth" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' --data "$initialize_body")"
if [[ "$noauth_status" != "401" && "$noauth_status" != "404" ]]; then
  echo "FAIL noauth request must be rejected or disabled"
  exit 1
fi
fail_if_sensitive "$noauth"

invalid="$TMP_DIR/invalid.json"
invalid_status="$(curl_status invalid-token POST "$invalid" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H 'Authorization: Bearer invalid-staging-token' --data "$initialize_body")"
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
search_status="$(curl_status search POST "$search" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H "Authorization: Bearer $TOKEN" --data '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search","arguments":{"query":"AI","limit":5}}}')"
if [[ "$search_status" != "200" ]]; then
  echo "FAIL authenticated search did not return 200"
  exit 1
fi
fail_if_sensitive "$search"

shared="$TMP_DIR/shared.json"
shared_status="$(curl_status shared-tag-rejection POST "$shared" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H "Authorization: Bearer $TOKEN" --data '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search","arguments":{"query":"tag","limit":1,"includeSharedTags":true}}}')"
if [[ "$shared_status" != "200" ]]; then
  echo "FAIL tools/call input rejection must use an HTTP 200 MCP tool result"
  exit 1
fi
if ! grep -Eq '"isError"[[:space:]]*:[[:space:]]*true' "$shared"; then
  echo "FAIL includeSharedTags=true must return an MCP isError result"
  exit 1
fi
fail_if_sensitive "$shared"

unknown="$TMP_DIR/unknown.json"
unknown_status="$(curl_status unknown-tool POST "$unknown" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H "Authorization: Bearer $TOKEN" --data '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"rinbam.unknown","arguments":{}}}')"
if [[ "$unknown_status" != "400" ]] || ! grep -Eq '"code"[[:space:]]*:[[:space:]]*-32602' "$unknown"; then
  echo "FAIL unknown tools must return JSON-RPC -32602"
  exit 1
fi
fail_if_sensitive "$unknown"

null_id="$TMP_DIR/null-id.json"
null_id_status="$(curl_status null-id POST "$null_id" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H "Authorization: Bearer $TOKEN" --data '{"jsonrpc":"2.0","id":null,"method":"ping"}')"
if [[ "$null_id_status" != "400" ]] || ! grep -Eq '"code"[[:space:]]*:[[:space:]]*-32600' "$null_id"; then
  echo "FAIL JSON-RPC id=null must be rejected as Invalid Request"
  exit 1
fi
fail_if_sensitive "$null_id"

origin="$TMP_DIR/origin.json"
origin_status="$(curl_status origin POST "$origin" -H 'Origin: https://untrusted.invalid' -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H "Authorization: Bearer $TOKEN" --data "$initialize_body")"
if [[ "$origin_status" != "403" ]]; then
  echo "FAIL an untrusted Origin must return HTTP 403"
  exit 1
fi
fail_if_sensitive "$origin"

receipt="$TMP_DIR/receipt.json"
receipt_status="$(curl_status ai-receipt POST "$receipt" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-11-25' -H "Authorization: Bearer $TOKEN" --data '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"rinbam.get_ai_receipt","arguments":{"id":"staging-smoke"}}}')"
if [[ "$receipt_status" != "200" ]]; then
  echo "FAIL AI receipt metadata request returned unexpected status"
  exit 1
fi
fail_if_sensitive "$receipt"

echo "OK MCP staging smoke passed without printing token or raw body"
