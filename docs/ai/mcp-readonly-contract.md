# Read-only MCP Contract

## Goal
ChatGPT / OpenAI Apps SDK 向けのMCP面は、保存リンクの検索・取得だけに限定し、ユーザーデータを書き換えない。

## Context
Official Apps SDK MCP resource-server guidance is implemented only as local source code in `web/admin`. The endpoint remains disabled unless the enable flag, Authorization Server readiness flag, and dedicated non-generic token audience are all configured. This repo does not implement the production Authorization Server; deployment, OAuth production setup, and OpenAI submission are separate gates.

## Tools
| Tool | Purpose | Writes data |
|---|---|---|
| `search` | authenticated user's ACTIVE personal saved links search | No |
| `fetch` | fetch one summary by opaque publicSafeId | No |
| `rinbam.list_tags` | list local personal-link tags attached to ACTIVE links | No |
| `rinbam.get_ai_receipt` | placeholder receipt lookup, no raw prompt/body | No |
| `rinbam.list_recent_saved_links` | list recent ACTIVE personal saved links | No |

## Constraints
- Every descriptor must set `readOnlyHint=true`, `destructiveHint=false`, `openWorldHint=false`, `idempotentHint=true`.
- Route is default disabled and fails closed when its dedicated MCP auth configuration is incomplete.
- Enabled POST requires Bearer auth.
- Server requires the dedicated audience and fixed `links:read` scope, validates issuer/subject/expiry, and uses the token only with Supabase Auth. The token is not forwarded to PostgREST; data access uses service-role-only RPCs/direct queries with the verified `user_id` fixed explicitly.
- All table reads/RPCs are scoped to authenticated `user_id`; every data tool excludes `deleted_at`, `disabled_at`, and `PENDING_DELETE`, and returns ACTIVE records only.
- API returns publicSafeId, not raw DB UUID.
- Search and tag listing use allowlisted, service-role-only, verified-user-bound Supabase RPCs; fetch uses explicit `user_id` plus opaque `public_safe_id` equality. The endpoint does not load a fixed latest-200/latest-500 window in application memory.
- API must not return raw `fetched_body`.
- API must not live-refetch saved URLs.
- `includeSharedTags=true` is rejected without a future approved scope/feature flag.
- Unknown tool input fields are rejected and all tool output passes a recursive secret/raw-body sanitizer.
- Per-user rate limit is enforced by an atomic distributed service-role-only Supabase RPC at a fixed server-owned 60 requests per 60 seconds; client parameters cannot relax it. The only stateful RPC is the private rate-limit bucket, not user data.
- No insert/update/delete/upsert or business-data write is allowed in MCP lib.
- JSON-RPC notifications (methods beginning with `notifications/`) receive no response body.
- JSON-RPC `id=null` is invalid, unknown tool names return `-32602`, and tool input errors use HTTP 200 with `isError=true`.

## Validation method
- `python3 scripts/verify_mcp_contract.py`
- `npm run typecheck` in `web/admin`
- Disabled route should return `mcp_disabled`.
- Enabled unauthenticated request should return 401 with `WWW-Authenticate`.

## Failure handling
If a write operation, raw body leak, auth bypass, shared-tag default inclusion, or descriptor annotation mismatch is found, classify the repo as `NO_GO_INTERNAL`. Missing production Authorization Server, dedicated token issuance, deploy, provider registration, or OpenAI submission is `NO_GO_EXTERNAL`; do not enable production MCP.
