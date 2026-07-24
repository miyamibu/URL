# MCP Auth Contract

## Goal
MCP endpoints must never expose personal saved links without explicit enablement and authenticated user boundary.

## Context
The repo includes a read-only MCP foundation under `web/admin`. Production hosting, OAuth registration, and OpenAI submission are Manual steps.

## Constraints
- Endpoint default is disabled unless both `URLSAVER_MCP_ENABLED=true` and `URLSAVER_PERSONAL_LINK_SNAPSHOT_PROTOCOL_ENABLED=true`.
- When enabled, POST requires Bearer auth.
- Token is verified through Supabase `auth.getUser`.
- Every personal saved-link/tag query filters by `user_id = ctx.userId`.
- Invalid/missing token returns 401 with Bearer challenge.
- `includeSharedTags=true` is rejected unless a future explicit scope/feature flag is approved.
- `URLSAVER_MCP_ID_SECRET` is required to derive opaque publicSafeId.

## Done when
- Disabled endpoint does not return personal data.
- Invalid token is rejected.
- User boundary is enforced in all queries.
- Contract script passes.

## Validation
`python3 scripts/verify_mcp_contract.py`.

## Failure handling
If noauth access or cross-user data leakage is possible, status is `NO_GO_INTERNAL`. If production OAuth registration is missing but repo contract is correct, classify that remaining work as Manual step / external.
