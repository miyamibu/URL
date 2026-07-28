# MCP Auth Contract

## Goal
MCP endpoints must never expose personal saved links without explicit enablement and authenticated user boundary.

## Context
The repo includes a read-only MCP foundation under `web/admin`. Production hosting, OAuth registration, and OpenAI submission are Manual steps.

## Constraints
- Endpoint default is disabled unless `URLSAVER_MCP_ENABLED=true`.
- When enabled, POST requires Bearer auth.
- Token is verified through Supabase `auth.getUser`.
- Every personal saved-link/tag query/RPC filters by `user_id = ctx.userId`; data tools are ACTIVE-only and always exclude deleted, disabled, and `PENDING_DELETE` records.
- Invalid/missing token returns 401 with Bearer challenge.
- `includeSharedTags=true` is rejected unless a future explicit scope/feature flag is approved.
- `URLSAVER_MCP_ID_SECRET` is required to derive opaque publicSafeId.
- The search/list RPCs use opaque `public_safe_id` and the fetch RPC path performs an equality lookup; application-side latest-200/latest-500 scans are not part of the contract.
- Per-user rate limiting is an atomic distributed Supabase RPC. If the RPC is unavailable, the endpoint fails closed with 503; exhausted buckets return 429 with `Retry-After`.
- Unknown input fields are rejected, output is recursively sanitized, and JSON-RPC notifications receive no response body.

## Done when
- Disabled endpoint does not return personal data.
- Invalid token is rejected.
- User boundary is enforced in all queries.
- Contract script passes.

## Validation
`python3 scripts/verify_mcp_contract.py`.

## Failure handling
If noauth access or cross-user data leakage is possible, status is `NO_GO_INTERNAL`. If production OAuth registration is missing but repo contract is correct, classify that remaining work as Manual step / external.
