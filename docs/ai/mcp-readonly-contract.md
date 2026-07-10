# Read-only MCP Contract

## Goal
ChatGPT / OpenAI Apps SDK 向けのMCP面は、保存リンクの検索・取得だけに限定し、ユーザーデータを書き換えない。

## Context
Official Apps SDK MCP server guidance is implemented only as local source code in `web/admin`. Endpoint default is disabled unless `URLSAVER_MCP_ENABLED=true`. Deployment, OAuth production setup, and OpenAI submission are Manual steps and are not done by Codex.

## Tools
| Tool | Purpose | Writes data |
|---|---|---|
| `search` | authenticated user's personal saved links search | No |
| `fetch` | fetch one summary by opaque publicSafeId | No |
| `rinbam.list_tags` | list local personal-link tags | No |
| `rinbam.get_ai_receipt` | placeholder receipt lookup, no raw prompt/body | No |
| `rinbam.list_recent_saved_links` | list recent personal saved links | No |

## Constraints
- Every descriptor must set `readOnlyHint=true`, `destructiveHint=false`, `openWorldHint=false`, `idempotentHint=true`.
- Route is default disabled unless explicitly enabled.
- Enabled POST requires Bearer auth.
- Server verifies token with Supabase Auth before table reads.
- All table reads are scoped to authenticated `user_id`.
- API returns publicSafeId, not raw DB UUID.
- API must not return raw `fetched_body`.
- API must not live-refetch saved URLs.
- `includeSharedTags=true` is rejected without a future approved scope/feature flag.
- Per-user rate limit is enforced.
- No insert/update/delete/upsert/rpc writes in MCP lib.

## Validation method
- `python3 scripts/verify_mcp_contract.py`
- `npm run typecheck` in `web/admin`
- Disabled route should return `mcp_disabled`.
- Enabled unauthenticated request should return 401 with `WWW-Authenticate`.

## Failure handling
If a write operation, raw body leak, auth bypass, shared-tag default inclusion, or descriptor annotation mismatch is found, classify the repo as `NO_GO_INTERNAL`. Missing production deploy/OAuth/OpenAI submission is a Manual step, not a repo-internal blocker.
