# MCP Tools Contract

## Goal
ChatGPT/OpenAI Apps向けに、りんばむ保存リンクをread-onlyで検索/取得できるrepo内contractを固定する。

## Context
production deploy、OAuth client登録、OpenAI submissionはManual steps。repo defaultではMCP endpointは無効。

## Tools
| Tool | Mode | Notes |
|---|---|---|
| `search` | read-only | Authenticated user's ACTIVE personal saved links only. `includeSharedTags=true` is rejected. |
| `fetch` | read-only | Fetch by opaque `publicSafeId`; raw body and live URL refetch are not returned. |
| `rinbam.list_tags` | read-only | Local personal tags attached to ACTIVE links only. |
| `rinbam.get_ai_receipt` | read-only | Metadata-only placeholder; raw prompt/body never returned. |
| `rinbam.list_recent_saved_links` | read-only | Recent ACTIVE personal saved links; shared tags excluded. |

## Constraints
- `readOnlyHint: true`, `openWorldHint: false`, `destructiveHint: false`, `idempotentHint: true` are required.
- Missing/null annotations are validation errors.
- No business-data write tools, no Supabase insert/update/delete/upsert. The only allowlisted RPCs are the user-bound ACTIVE read RPCs and the atomic distributed rate-limit RPC.
- Archived, disabled, deleted, and `PENDING_DELETE` records are always excluded.
- Search never loads a fixed latest-200/latest-500 application window; fetch resolves by `(user_id, public_safe_id)`.
- No live URL refetch and no external network call from tool execution.
- No raw `fetched_body`.
- Unknown input fields are rejected and returned payloads are recursively sanitized for raw bodies, tokens, secrets, JWTs, emails, and local paths.
- JSON-RPC notifications receive no response body.
- Prompt injection text is treated as text only; it cannot trigger writes or external calls.
- Rate limit is enforced per authenticated user.

## Validation
`python3 scripts/verify_mcp_contract.py` and `cd web/admin && npm run typecheck`.

## Failure handling
Any write path, noauth personal data, missing annotation, shared tag default inclusion, or raw body output is `NO_GO_INTERNAL`.
