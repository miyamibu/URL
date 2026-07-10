# Production Secrets And Feature Flags

## Goal
Define production secret names and flag handling without committing real values.

## Required Secrets

| Secret name | Where to set | Purpose | Notes |
|---|---|---|---|
| `SUPABASE_URL` | Hosting provider secret manager | Server-side Supabase endpoint. | Use production project only at launch time. |
| `SUPABASE_SERVICE_ROLE_KEY` | Hosting provider secret manager | Server-side auth/user boundary for MCP. | Never expose to mobile app, logs, repo, or chat. |
| `URLSAVER_MCP_ID_SECRET` | Hosting provider secret manager | HMAC/publicSafeId derivation. | Rotate if exposed. |
| `URLSAVER_MCP_ENABLED` | Hosting env/flag system | MCP kill switch. | Default false. |
| OAuth client secret, if used | OAuth provider / hosting secret manager | MCP/OpenAI connector auth. | Never commit real value. |
| Provider API key, if future AI provider is enabled | Provider secret manager | AI provider calls. | Not needed for current mock/default-off release. |

## Never Commit Real Values

- Do not create `.env.production` in this repo.
- `.env.production.example` is allowed only if it contains placeholders.
- Do not commit service role keys, API keys, refresh tokens, private keys, mobileprovision files, keystores, or signing passwords.

## HMAC / publicSafeId Secret

- Use a high-entropy value per environment.
- Staging and production must use different values.
- Rotating changes future publicSafeId derivation; plan for temporary lookup compatibility if existing connector IDs are active.

## MCP Auth Secret / OAuth Client

- MCP must never be public noauth.
- Bearer/OAuth token must map to one user boundary.
- Invalid token returns 401.
- Token values must not be printed by smoke scripts or logs.

## Rate Limit Config

- Current code uses safe in-process defaults.
- Production should add hosting/provider edge rate limit if available.
- Use per-user and per-IP safeguards where possible.

## Kill Switch

- Primary: `URLSAVER_MCP_ENABLED=false`.
- Secondary: block `/api/mcp` at hosting/firewall.
- Tertiary: remove connector from OpenAI/ChatGPT settings.

## AI Feature Flags

- Android release: `AI_TRANSPARENCY_ENABLED=false`.
- iOS normal UI: no public AI entry unless future explicit approval.
- Mock provider remains deterministic for tests; production provider wiring is not part of this launch.

## MCP Feature Flags

- `URLSAVER_MCP_ENABLED=false` default.
- Enable only in staging smoke or production launch window with owner approval.
- Shared tags remain excluded by default.

## Android / iOS Build Flags

- Android release keeps `ALLOW_LOCAL_MEDIA_DOWNLOADS=false`.
- Android release keeps AI transparency off.
- iOS local-only config keeps shared tag cloud disabled unless an approved cloud-sharing build explicitly passes ignored secret config.

## Staging vs Production Differences

| Area | Staging | Production |
|---|---|---|
| MCP URL | staging HTTPS endpoint | production HTTPS endpoint |
| Secrets | staging-only | production-only |
| HMAC | staging unique | production unique |
| Logs | verbose but sanitized | minimal and sanitized |
| Connector | Developer Mode only | submission-approved connector only |

## Rotation Procedure

1. Disable MCP with `URLSAVER_MCP_ENABLED=false`.
2. Rotate the exposed secret in the provider console.
3. Redeploy/restart the service.
4. Re-run staging smoke tests.
5. Re-enable only after the release owner approves.

## Emergency Disable Procedure

1. Set `URLSAVER_MCP_ENABLED=false`.
2. Revoke OAuth/client credentials if needed.
3. Rotate `URLSAVER_MCP_ID_SECRET` if publicSafeId secret exposure is suspected.
4. Remove or disable OpenAI connector.
5. Save sanitized incident note using `docs/release/rollback-plan.md`.
