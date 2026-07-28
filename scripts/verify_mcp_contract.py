#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
source = (root / "web/admin/lib/rinbamMcp.ts").read_text(encoding="utf-8")
route_source = (root / "web/admin/app/api/mcp/route.ts").read_text(encoding="utf-8")
smoke_source = (root / "scripts/smoke_mcp_staging.sh").read_text(encoding="utf-8")
migrations = "\n".join(path.read_text(encoding="utf-8") for path in (root / "supabase/migrations").glob("*.sql"))
fixed_rate_limit_migration = (
    root / "supabase/migrations/20260728154000_mcp_rate_limit_fixed_contract.sql"
).read_text(encoding="utf-8")

required_tools = [
    "search",
    "fetch",
    "rinbam.list_tags",
    "rinbam.get_ai_receipt",
    "rinbam.list_recent_saved_links",
]

failures = []
for tool in required_tools:
    if f'name: "{tool}"' not in source:
        failures.append(f"missing tool descriptor: {tool}")

required_annotations = {
    "readOnlyHint": "true",
    "destructiveHint": "false",
    "openWorldHint": "false",
    "idempotentHint": "true",
}
for key, value in required_annotations.items():
    if not re.search(rf"{key}:\s*{value}\b", source):
        failures.append(f"missing annotation {key}: {value}")

for forbidden in ["insert(", "delete(", "upsert(", ".insert(", ".delete(", ".upsert("]:
    if forbidden in source:
        failures.append(f"read-only MCP lib contains forbidden Supabase write call {forbidden}")

for match in re.finditer(r"\.update\s*\(", source):
    window = source[max(0, match.start() - 120) : match.end()]
    if "createHmac" not in window:
        failures.append("read-only MCP lib contains forbidden Supabase write call update(")

allowed_rpcs = {
    "consume_rinbam_mcp_rate_limit_for_user",
    "mcp_search_active_personal_saved_links_for_user",
    "mcp_list_active_personal_link_tags_for_user",
}
for rpc_name in re.findall(r'\.rpc\("([^"]+)"', source):
    if rpc_name not in allowed_rpcs:
        failures.append(f"unexpected MCP RPC call: {rpc_name}")

if "fetched_body" in source and "rawBodyReturned: false" not in source:
    failures.append("MCP code references fetched_body without explicit rawBodyReturned=false")

if 'optionalEnv("URLSAVER_MCP_ENABLED") === "true"' not in source:
    failures.append("MCP endpoint is not default-disabled by URLSAVER_MCP_ENABLED")
for required in [
    'optionalEnv("URLSAVER_MCP_AUTHORIZATION_SERVER_READY") === "true"',
    'optionalEnv("URLSAVER_MCP_TOKEN_AUDIENCE")',
    'RINBAM_MCP_REQUIRED_SCOPE = "links:read"',
    "tokenHasAudience",
    "tokenScopes",
    "issuerMatchesSupabaseProject",
    "claims.exp",
    "claims.sub !== data.user.id",
    "createServiceSupabaseClient()",
]:
    if required not in source:
        failures.append(f"MCP auth is missing its fail-closed token contract: {required}")
for forbidden in ["createMcpUserSupabaseClient", "Authorization: `Bearer ${accessToken}`", "token: string;"]:
    if forbidden in source:
        failures.append(f"MCP must not pass the incoming bearer to PostgREST: {forbidden}")
for required in [
    'p_user_id: ctx.userId',
    '.eq("user_id", ctx.userId)',
]:
    if required not in source:
        failures.append(f"MCP service-role data path lacks an explicit verified-user boundary: {required}")

if "MAX_MCP_BODY_BYTES" not in route_source or "request_body_too_large" not in route_source:
    failures.append("MCP route does not cap request body before JSON parsing")

if "isRinbamMcpEnabled()" not in route_source or "mcp_disabled" not in route_source:
    failures.append("MCP route does not reject requests when disabled")

if "requireRinbamMcpUser(request.headers.get(\"authorization\"))" not in route_source:
    failures.append("MCP route does not require bearer auth before tool execution")

if "checkRinbamMcpRateLimit(ctx)" not in route_source:
    failures.append("MCP route does not enforce default rate limiting")
if "await checkRinbamMcpRateLimit(ctx)" not in route_source:
    failures.append("MCP route does not await distributed rate limiting")
if '"Retry-After"' not in route_source:
    failures.append("MCP route does not return Retry-After for rate limiting")
if 'rpc("consume_rinbam_mcp_rate_limit_for_user"' not in source:
    failures.append("MCP rate limit must call the server-owned fixed RPC without client parameters")
if "p_window_seconds:" in source or "p_max_requests:" in source:
    failures.append("MCP client can still send rate-limit boundary parameters")

for required in [
    "window_seconds constant integer := 60",
    "max_requests constant integer := 60",
    "Legacy arguments are ignored",
    "consume_rinbam_mcp_rate_limit_for_user",
    "mcp_search_active_personal_saved_links_for_user",
    "mcp_list_active_personal_link_tags_for_user",
    "from public, anon, authenticated",
    "to service_role",
]:
    if required not in fixed_rate_limit_migration:
        failures.append(f"fixed MCP rate-limit migration is missing: {required}")
for forbidden in ["coalesce(p_window_seconds", "coalesce(p_max_requests"]:
    if forbidden in fixed_rate_limit_migration:
        failures.append(f"fixed MCP rate-limit migration still trusts client input: {forbidden}")

if ".eq(\"user_id\", ctx.userId)" not in source:
    failures.append("MCP data queries are missing user_id boundary")

if "include_shared_tags_requires_explicit_scope" not in source:
    failures.append("MCP includeSharedTags=true is not explicitly rejected")

for forbidden in ["loadRows", ".limit(200)", ".limit(500)", "rateLimitBuckets", "includeArchived"]:
    if forbidden in source:
        failures.append(f"MCP still contains removed broad-scan/archive option: {forbidden}")

for required in [
    "public_safe_id",
    'eq("record_state", "ACTIVE")',
    'neq("record_state", "PENDING_DELETE")',
    "validateRinbamMcpToolArguments",
    "sanitizeRinbamMcpOutput",
    'parsed.method.startsWith("notifications/")',
    "RinbamMcpJsonRpcRequestId = Exclude<RinbamMcpJsonRpcId, null>",
    'RinbamMcpJsonRpcError(-32602, "Unknown tool")',
]:
    if required not in source:
        failures.append(f"MCP source is missing required contract guard: {required}")

for required in [
    "public_safe_id",
    "mcp_search_active_personal_saved_links",
    "mcp_list_active_personal_link_tags",
    "consume_rinbam_mcp_rate_limit",
    "record_state = 'ACTIVE'",
    "record_state <> 'PENDING_DELETE'",
    "disabled_at is null",
    "deleted_at is null",
]:
    if required not in migrations:
        failures.append(f"Supabase migration is missing required MCP contract guard: {required}")

if "SAVED_SNAPSHOT_NOTICE" not in source or "savedSnapshotNotice" not in source:
    failures.append("MCP fetch output does not expose saved-time snapshot notice")

if "rawPromptReturned: false" not in source:
    failures.append("MCP AI receipt response lacks explicit rawPromptReturned=false")

if "rawBodyReturned: false" not in source:
    failures.append("MCP outputs lack explicit rawBodyReturned=false")

for forbidden in ["globalThis.fetch(", "await fetch(", "fetch(\"http", "fetch('http"]:
    if forbidden in source:
        failures.append(f"MCP read-only lib contains forbidden external network call pattern {forbidden}")

if 'RinbamMcpTransportError(403, "origin_not_allowed")' not in route_source:
    failures.append("MCP untrusted Origin does not have an explicit HTTP 403 boundary")
for required in [
    'shared_status" != "200"',
    '"isError"[[:space:]]*:[[:space:]]*true',
    '"code"[[:space:]]*:[[:space:]]*-32602',
    '"code"[[:space:]]*:[[:space:]]*-32600',
    'origin_status" != "403"',
]:
    if required not in smoke_source:
        failures.append(f"MCP staging smoke is missing protocol assertion: {required}")

if failures:
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    sys.exit(1)

print("OK MCP contract checks passed")
