#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
source = (root / "web/admin/lib/rinbamMcp.ts").read_text(encoding="utf-8")
route_source = (root / "web/admin/app/api/mcp/route.ts").read_text(encoding="utf-8")

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

for forbidden in ["insert(", "upsert(", "rpc("]:
    if forbidden in source:
        failures.append(f"read-only MCP lib contains forbidden Supabase write call {forbidden}")

# The MCP rate limiter is an in-memory Map and legitimately deletes expired
# buckets. Detect Supabase-style delete calls without rejecting that cleanup.
if "rateLimitBuckets.delete(" in source:
    source_without_rate_limit_cleanup = source.replace("rateLimitBuckets.delete(", "")
else:
    source_without_rate_limit_cleanup = source
if "delete(" in source_without_rate_limit_cleanup:
    failures.append("read-only MCP lib contains forbidden Supabase write call delete(")

for match in re.finditer(r"\.update\s*\(", source):
    window = source[max(0, match.start() - 120) : match.end()]
    if "createHmac" not in window:
        failures.append("read-only MCP lib contains forbidden Supabase write call update(")

if "fetched_body" in source and "rawBodyReturned: false" not in source:
    failures.append("MCP code references fetched_body without explicit rawBodyReturned=false")

if 'optionalEnv("URLSAVER_MCP_ENABLED") === "true"' not in source:
    failures.append("MCP endpoint is not default-disabled by URLSAVER_MCP_ENABLED")

if "isRinbamMcpEnabled()" not in route_source or "mcp_disabled" not in route_source:
    failures.append("MCP route does not reject requests when disabled")

if "requireRinbamMcpUser(request.headers.get(\"authorization\"))" not in route_source:
    failures.append("MCP route does not require bearer auth before tool execution")

if "checkRinbamMcpRateLimit(ctx)" not in route_source:
    failures.append("MCP route does not enforce default rate limiting")

if ".eq(\"user_id\", ctx.userId)" not in source:
    failures.append("MCP data queries are missing user_id boundary")

if "include_shared_tags_requires_explicit_scope" not in source:
    failures.append("MCP includeSharedTags=true is not explicitly rejected")

if '.in("record_state", allowedStates)' not in source or 'const allowedStates = includeArchived ? ["ACTIVE", "ARCHIVED"] : ["ACTIVE"]' not in source:
    failures.append("MCP link queries do not enforce an explicit ACTIVE/ARCHIVED state boundary")

if "MCP_SEARCH_MAX_ROWS" not in source or "MCP_FETCH_MAX_ROWS" not in source or "lookupTruncated" not in source:
    failures.append("MCP search/fetch does not expose bounded pagination/truncation state")

if "MCP_REFERENCE_BATCH_SIZE" not in source or "linkIds.slice(offset, offset + MCP_REFERENCE_BATCH_SIZE)" not in source:
    failures.append("MCP tag-reference lookup is not split into bounded link-id batches")

if "SAVED_SNAPSHOT_NOTICE" not in source or "savedSnapshotNotice" not in source:
    failures.append("MCP fetch output does not expose saved-time snapshot notice")

if "rawPromptReturned: false" not in source:
    failures.append("MCP AI receipt response lacks explicit rawPromptReturned=false")

if "rawBodyReturned: false" not in source:
    failures.append("MCP outputs lack explicit rawBodyReturned=false")

for forbidden in ["globalThis.fetch(", "await fetch(", "fetch(\"http", "fetch('http"]:
    if forbidden in source:
        failures.append(f"MCP read-only lib contains forbidden external network call pattern {forbidden}")

if failures:
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    sys.exit(1)

print("OK MCP contract checks passed")
