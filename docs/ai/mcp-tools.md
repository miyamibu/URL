# MCP Tools Contract

## Goal
ChatGPT/OpenAI Apps向けに、りんばむ保存リンクをread-onlyで検索/取得できるrepo内contractを固定する。

## Context
production deploy、OAuth client登録、OpenAI submissionはManual steps。repo defaultではMCP endpointは無効。

## Tools
| Tool | Mode | Notes |
|---|---|---|
| `search` | read-only | Authenticated user's personal saved links only. `includeSharedTags=true` is rejected. |
| `fetch` | read-only | Fetch by opaque `publicSafeId`; raw body and live URL refetch are not returned. |
| `rinbam.list_tags` | read-only | Local personal tags only. |
| `rinbam.get_ai_receipt` | read-only | Metadata-only placeholder; raw prompt/body never returned. |
| `rinbam.list_recent_saved_links` | read-only | Recent personal saved links; shared tags excluded by default. |

## Constraints
- `readOnlyHint: true`, `openWorldHint: false`, `destructiveHint: false`, `idempotentHint: true` are required.
- Missing/null annotations are validation errors.
- No write tools, no Supabase insert/update/delete/upsert/rpc.
- No live URL refetch and no external network call from tool execution.
- No raw `fetched_body`.
- Prompt injection text is treated as text only; it cannot trigger writes or external calls.
- Rate limit is enforced per authenticated user.

## Validation
`python3 scripts/verify_mcp_contract.py` and `cd web/admin && npm run typecheck`.

## Failure handling
Any write path, noauth personal data, missing annotation, shared tag default inclusion, or raw body output is `NO_GO_INTERNAL`.
