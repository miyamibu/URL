# Staging Deploy Checklist

## Purpose
Staging exists to verify MCP/OpenAI Developer Mode behavior before production. It must use HTTPS, authentication, non-production or controlled credentials, and a kill switch.

## Required Environment Variables

Use external hosting secret storage only. Do not commit real values.

| Name | Required | Purpose | Example value |
|---|---:|---|---|
| `URLSAVER_MCP_ENABLED` | Yes | MCP kill switch. | `false` by default, `true` only during staging smoke. |
| `URLSAVER_MCP_ID_SECRET` | Yes when enabled | HMAC/publicSafeId derivation. | `staging-secret-from-secret-store` |
| `SUPABASE_URL` | Yes when enabled | Staging Supabase project URL. | `https://example.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | Yes when enabled | Server-side auth boundary for MCP route. | Set in host secret manager only. |
| `URLSAVER_MCP_RATE_LIMIT_WINDOW_MS` | Optional | Future externalized rate window. | `60000` |
| `URLSAVER_MCP_RATE_LIMIT_MAX_REQUESTS` | Optional | Future externalized rate max. | `60` |

## Required Feature Flags

- `URLSAVER_MCP_ENABLED=false` before and after smoke tests unless actively testing.
- Android/iOS AI transparency flags remain default off for normal users.
- Do not enable write tools; the MCP server is read-only only.

## Forbidden Production Values

- Do not use production Supabase service role key in staging.
- Do not use production HMAC/publicSafeId secret in staging.
- Do not paste Apple, Google, OpenAI, Supabase, Vercel, or store credentials into repo files or logs.
- Do not create `.env.production` in the repository.

## MCP Endpoint URL

- Staging URL placeholder: `https://staging.example.com/api/mcp`
- Requirement: HTTPS only.
- Production URL must not be used in smoke scripts unless the release owner explicitly changes this checklist and provides current approval.

## Auth Setup

- The endpoint must reject noauth requests.
- Bearer token must be from the intended staged user.
- OAuth/client setup, if used, must map the token to exactly one user boundary.
- Invalid token must return 401.

## HMAC / publicSafeId Secret Setup

- Set `URLSAVER_MCP_ID_SECRET` in the hosting secret manager.
- Use a unique staging value.
- Rotate if logs, screenshots, or console history suggest exposure.

## Rate Limit Config

- Current source uses a safe in-process default: 60 requests per 60 seconds per authenticated user.
- If the host runs multiple instances, add provider-level rate limiting before production.
- Staging smoke should verify 429 behavior where practical.

## Kill Switch Config

- Primary kill switch: `URLSAVER_MCP_ENABLED=false`.
- Secondary kill switch: remove route from deploy or block `/api/mcp` at edge/firewall.
- Emergency disable target: endpoint returns 404 `mcp_disabled` or 401 with no personal data.

## Smoke Test Commands

```bash
export MCP_STAGING_BASE_URL="https://staging.example.com"
export MCP_STAGING_BEARER_TOKEN="<staging-user-token>"
export MCP_STAGING_CONFIRM_URL="I_UNDERSTAND_THIS_IS_STAGING"
bash scripts/smoke_mcp_staging.sh
```

## Rollback Commands

Use the hosting provider console or CLI approved for the project:

```bash
# Example only: disable in provider secret/env settings, then redeploy/restart.
URLSAVER_MCP_ENABLED=false
```

Do not run production deploy commands from Codex unless the user explicitly asks for that action in the current conversation.

## Expected Logs

- Request path, HTTP status, tool name, coarse latency, user boundary hash or provider user id if already safe.
- Rate limit decisions.
- Auth failures by category only: `auth_required`, `invalid_token`.

## Logs That Must Not Appear

- Raw prompt.
- Raw `fetchedBody`.
- Raw token or refresh token.
- Supabase service role key.
- HMAC/publicSafeId secret.
- Attachment contents.
- Cross-user rows.

## How To Disable MCP Immediately

1. Set `URLSAVER_MCP_ENABLED=false` in the hosting environment.
2. Redeploy or restart the web/admin service.
3. Confirm `GET /api/mcp` returns 404 `mcp_disabled`.
4. Confirm no staged connector can fetch user data.
5. Save sanitized evidence in the incident note.
