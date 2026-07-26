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

`SUPABASE_URL` is an endpoint identifier rather than a credential. It remains
in the required runtime-values table for operational completeness and is
classified as non-secret below.

## Supabase Edge Functions: Non-secret Verification Settings

The following values are runtime configuration used to verify that the deployed
purchase-verification function is pointed at the intended app and store
environment. They are not credentials and must be checked without recording
secret values or printing the full runtime environment.

| Setting name | Classification | Required value or validation rule | Function contract |
|---|---|---|---|
| `SUPABASE_URL` | Non-secret endpoint setting | Production Supabase project URL only. | `verify-store-purchase` and `contact-support-resend-webhook` read it as the REST/Auth endpoint. |
| `APP_STORE_ENVIRONMENT` | Non-secret verification setting | Exactly `Sandbox` or `Production`. | `verify-store-purchase`; the value selects the Apple verification environment. |
| `APP_STORE_APPLE_ID` | Non-secret verification setting | Required only when `APP_STORE_ENVIRONMENT=Production`; must be a positive integer App Store ID. It is not required for `Sandbox`. | `verify-store-purchase`; production-only Apple verifier input. |
| `APP_STORE_BUNDLE_ID` | Non-secret verification setting | Exactly `com.mibu.codebridge.ios`. | `verify-store-purchase`; any other bundle ID fails closed. |
| `GOOGLE_PLAY_PACKAGE_NAME` | Non-secret verification setting | Exactly `jp.miyamibu.urlalbum`. | `verify-store-purchase`; used as the Google Play application package name. |
| `CONTACT_TO_EMAIL` | Non-secret operational setting | Approved support recipient address. | `contact-support`; routing value for Resend delivery. |
| `CONTACT_FROM_EMAIL` | Non-secret operational setting | Approved sender address for the configured Resend domain. | `contact-support`; sender value for Resend delivery. |

Validation checklist for these settings:

- Confirm the names and exact non-secret values in the provider configuration,
  without copying secrets into this repository, logs, screenshots, or chat.
- For Apple Sandbox, keep `APP_STORE_ENVIRONMENT=Sandbox` and do not require an
  `APP_STORE_APPLE_ID`; for Production, set `APP_STORE_ENVIRONMENT=Production`
  and verify that `APP_STORE_APPLE_ID` is a positive integer.
- Confirm `APP_STORE_BUNDLE_ID` and `GOOGLE_PLAY_PACKAGE_NAME` exactly match
  the canonical IDs above before enabling live purchase verification.
- Treat a successful function deploy or a local `deno check` as configuration
  and code checks only; they do not prove a real Apple Sandbox, App Store, or
  Google Play purchase flow.

## Supabase Edge Functions: Secret Settings

The current Edge Functions read the following secret values. Store them only in
the external Supabase/hosting secret store or the provider's protected
environment configuration; never commit, display, or echo the values.

| Secret name | Used by | Handling |
|---|---|---|
| `SUPABASE_SERVICE_ROLE_KEY` | `verify-store-purchase`, `contact-support-resend-webhook` | Secret store only; never put in Android/iOS builds, client responses, logs, repo files, or chat. |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | `verify-store-purchase` | Secret store only; contains the Google service account private key and must not be pasted into docs or shell history. |
| `SUPABASE_DB_URL` | `contact-support` | Secret store only; database URLs commonly contain credentials. |
| `RESEND_API_KEY` | `contact-support` and web/admin delivery lookup | Secret store only; never expose to clients or logs. |
| `RESEND_WEBHOOK_SECRET` | `contact-support-resend-webhook` and web/admin webhook route | Secret store only; use the matching Resend signing secret and rotate if exposed. |
| `CONTACT_RATE_LIMIT_SALT` | `contact-support` (optional code path) | Treat as a secret salt and keep it in the external secret store; do not log or commit it. |

`SUPABASE_SERVICE_ROLE_KEY` and `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` are
particularly sensitive: their names may appear in deployment documentation, but
their values must exist only in an external secret store. A name-only presence
check is acceptable; a value dump, masked or otherwise, is not.

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

- Android release enables `ALLOW_LOCAL_MEDIA_DOWNLOADS` only when the release media resolver URL is configured; otherwise it remains false and the media-save action is not exposed. The current configured release candidate has the resolver URL and therefore enables media saving.
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
