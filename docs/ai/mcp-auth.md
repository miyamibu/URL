# MCP Auth Contract

## Goal
MCP endpoints must never expose personal saved links without explicit enablement and authenticated user boundary.

## Context
The repo includes a read-only MCP resource server foundation under `web/admin`. It does not implement an OAuth Authorization Server, token exchange, dynamic client registration, or provider registration. Until those external pieces issue and verify a dedicated MCP token, production enablement is `NO_GO_EXTERNAL` and the endpoint must remain disabled.

## Constraints
- Endpoint is enabled only when all of `URLSAVER_MCP_ENABLED=true`, `URLSAVER_MCP_AUTHORIZATION_SERVER_READY=true`, and a non-generic `URLSAVER_MCP_TOKEN_AUDIENCE` are present. Missing/incomplete auth configuration fails closed as `mcp_disabled`.
- When enabled, POST requires Bearer auth.
- The token must be a Supabase-verifiable user token dedicated to the configured MCP audience. Generic Supabase audiences such as `authenticated`, `anon`, and `service_role` are rejected.
- The fixed required scope is `links:read`; neither a client argument nor an environment setting can weaken it.
- After issuer, audience, scope, subject, and expiry checks, the Bearer token is used only for Supabase `auth.getUser` verification. It is never forwarded to PostgREST or data RPCs. The verified user ID must match the token `sub`.
- Data access uses the server-side service-role client, with the verified user ID explicitly fixed in every query and in service-role-only read/rate-limit RPC arguments. The incoming request cannot supply or override that user ID.
- Every personal saved-link/tag query/RPC filters by `user_id = ctx.userId`; data tools are ACTIVE-only and always exclude deleted, disabled, and `PENDING_DELETE` records.
- Invalid/missing token returns 401 with Bearer challenge.
- `includeSharedTags=true` is rejected unless a future explicit scope/feature flag is approved.
- `URLSAVER_MCP_ID_SECRET` is required to derive opaque publicSafeId.
- The search/list RPCs use opaque `public_safe_id` and the fetch RPC path performs an equality lookup; application-side latest-200/latest-500 scans are not part of the contract.
- Per-user rate limiting is an atomic distributed, service-role-only Supabase RPC with a server-owned fixed boundary of 60 requests per 60 seconds. Historical authenticated RPC arguments remain for deployed-client compatibility but are ignored and cannot relax the same bucket. If the RPC is unavailable, the endpoint fails closed with 503; exhausted buckets return 429 with `Retry-After`.
- Unknown input fields are rejected, output is recursively sanitized, and JSON-RPC notifications receive no response body.
- JSON-RPC `id=null` is rejected as Invalid Request. Unknown `tools/call` tool names return JSON-RPC `-32602`.

## Done when
- Disabled endpoint does not return personal data.
- Invalid token is rejected.
- A generic Supabase token without the dedicated MCP audience and `links:read` scope is rejected.
- User boundary is enforced in all queries.
- Contract script passes.

## Validation
`python3 scripts/verify_mcp_contract.py`.

## Failure handling
If noauth access or cross-user data leakage is possible, status is `NO_GO_INTERNAL`. If the production Authorization Server, dedicated audience/scope issuance, provider registration, or end-to-end token verification is missing, status is `NO_GO_EXTERNAL`; keep both readiness and enable flags false.
