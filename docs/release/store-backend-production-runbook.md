# Store Backend Production Runbook

## Goal

Operate purchase verification, App Store Server Notifications V2, Google Play
RTDN, and entitlement reconciliation without exposing provider credentials or
allowing an unverified notification to grant access.

Repository validation is not production proof. A release remains
`NOT_VERIFIED_EXTERNAL` until the Apple and Google sandbox checks in this
runbook have evidence.

## Scope and trust boundaries

| Component | Caller authentication | Responsibility |
|---|---|---|
| `verify-store-purchase` | Supabase user JWT | Verifies a StoreKit JWS or Google purchase token and atomically records the verification, grant, and audit event. |
| `store-notification-receiver` | Apple signed payload or Google Pub/Sub OIDC token | Verifies the provider event and applies it only to an already-bound purchase and grant. |
| `store-entitlement-reconciliation` | `x-store-reconciliation-secret` compared in constant time | Replays a durable event or checks current Apple/Google subscription state for an approved event binding. |

`store-notification-receiver` and `store-entitlement-reconciliation` have
`verify_jwt = false` in `supabase/config.toml`. This is intentional:
provider callbacks do not carry a Supabase JWT, and reconciliation uses a
dedicated secret. Both functions fail closed before their service-role RPC when
their own authentication or binding checks fail. Do not add an API gateway
bypass that removes these in-function checks.

`verify-store-purchase` keeps the default JWT verification and also resolves
the authenticated user before verification.

## Migration order

Keep these already-published migrations byte-for-byte immutable and in this
order:

1. `20260726220000_purchase_verification_atomicity.sql`
2. `20260727110000_store_notification_durable_contract.sql`
3. `20260727130000_store_active_reconciliation.sql`
4. `20260727160000_store_apple_active_reconciliation.sql`

Before any production command, compare the linked migration history with the
repository. Stop if the dry run includes unrelated migrations, tries to replay
one of the versions above, or reports a local/remote history mismatch. Never
edit an applied migration or use migration-repair commands as an automatic
fix. Correct an applied contract with a new forward migration.

## Runtime settings and secrets

Enter values only in the Supabase project secret UI or another approved secret
manager. Do not paste values into chat, shell history, screenshots, logs,
commits, issues, or pull requests.

Supabase supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` as reserved
function secrets. The service-role key must never be copied into either mobile
app.

### Shared store identity

| Name | Classification | Required rule |
|---|---|---|
| `APP_STORE_ENVIRONMENT` | Configuration | `Sandbox` or `Production`; must match the notification and Server API environment. |
| `APP_STORE_BUNDLE_ID` | Configuration | Exactly `com.mibu.codebridge.ios`. |
| `APP_STORE_APPLE_ID` | Configuration | Positive App Store application ID. Required for active reconciliation in both environments. |
| `GOOGLE_PLAY_PACKAGE_NAME` | Configuration | Exactly `jp.miyamibu.urlalbum`. |

### Purchase verification

| Name | Classification | Used by |
|---|---|---|
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Secret JSON credential | `verify-store-purchase` Google Developer API verification. |

Apple purchase verification uses the shared App Store identity settings and
the signed transaction supplied by the authenticated client.

### Notification receiver

| Name | Classification | Required rule |
|---|---|---|
| `GOOGLE_RTDN_PUSH_AUDIENCE` | Protected configuration | Exact OIDC audience configured on the Pub/Sub push subscription. |
| `GOOGLE_PUBSUB_EXPECTED_SERVICE_ACCOUNT_EMAIL` | Protected configuration | Exact service-account email allowed to push. |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_CLIENT_EMAIL` | Protected configuration | Developer API service-account client email. |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` | Secret | PKCS#8 private key; escaped or multiline PEM is accepted. |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY_ID` | Protected configuration, optional | JWT `kid` when the service account requires it. |
| `GOOGLE_PUBSUB_OIDC_JWKS_URL` | Protected configuration, optional | Leave unset to use Google's official JWKS URL. A custom URL requires a separate security review. |

Apple notification verification uses the shared App Store identity settings
and the Apple root-certificate manifest committed with
`verify-store-purchase`.

### Active reconciliation

| Name | Classification | Required rule |
|---|---|---|
| `STORE_RECONCILIATION_SECRET` | Secret | At least 32 characters of high entropy; unique per environment. |
| `APP_STORE_SERVER_API_ISSUER_ID` | Protected configuration | App Store Connect API key issuer ID. |
| `APP_STORE_SERVER_API_KEY_ID` | Protected configuration | App Store Connect API key ID. |
| `APP_STORE_SERVER_API_PRIVATE_KEY` | Secret | ES256 PKCS#8 App Store Connect private key. |

Reconciliation also uses the Google Developer API settings listed above. A
secret can be generated locally with `openssl rand -hex 32`, but its output
must be pasted directly into the official secret UI and never copied into this
repository or chat.

## Repository validation

Run from a clean checkout:

```sh
deno fmt --check \
  supabase/functions/_shared/store-notification-contract.ts \
  supabase/functions/verify-store-purchase/index.ts \
  supabase/functions/verify-store-purchase/index.test.ts \
  supabase/functions/store-notification-receiver/index.ts \
  supabase/functions/store-notification-receiver/index.test.ts \
  supabase/functions/store-entitlement-reconciliation/index.ts \
  supabase/functions/store-entitlement-reconciliation/index.test.ts

deno lint \
  supabase/functions/_shared/store-notification-contract.ts \
  supabase/functions/verify-store-purchase/index.ts \
  supabase/functions/verify-store-purchase/index.test.ts \
  supabase/functions/store-notification-receiver/index.ts \
  supabase/functions/store-notification-receiver/index.test.ts \
  supabase/functions/store-entitlement-reconciliation/index.ts \
  supabase/functions/store-entitlement-reconciliation/index.test.ts

deno test --allow-env \
  supabase/functions/verify-store-purchase/index.test.ts \
  supabase/functions/store-notification-receiver/index.test.ts \
  supabase/functions/store-entitlement-reconciliation/index.test.ts
```

Run the four Store pgTAP files only against an isolated local Supabase test
database. The fixtures roll back their inserted users, purchases, events, and
grants. Never point a fixture runner at production:

- `supabase/tests/purchase_atomicity_validation.sql`
- `supabase/tests/store_notification_durable_validation.sql`
- `supabase/tests/store_active_reconciliation_validation.sql`
- `supabase/tests/store_apple_active_reconciliation_validation.sql`

Validation must prove that:

- purchase verification performs one atomic RPC write boundary;
- a provider transaction cannot be claimed by a different user;
- duplicate notifications and reconciliation snapshots are idempotent;
- unsigned, wrong-app, wrong-environment, wrong-audience, and wrong-account
  events fail before the grant RPC;
- stale notifications cannot reactivate a newer revoked state;
- no raw purchase token, JWS, provider payload, service-role key, or
  reconciliation secret is durably stored.

## Cutover procedure

These are production operations and require explicit owner approval.

1. Record the current deployed Function versions and linked migration list.
2. Confirm all setting names above are present without revealing their values.
3. Run `supabase db push --linked --dry-run`. Continue only when the result
   contains exactly the approved pending migrations and preserves the four
   published Store versions.
4. Apply approved forward migrations, if any.
5. Deploy `verify-store-purchase`, then `store-notification-receiver`, then
   `store-entitlement-reconciliation`. Confirm the deployed Function config
   keeps JWT verification enabled only for `verify-store-purchase`.
6. Configure both providers to call
   `https://<project-ref>.supabase.co/functions/v1/store-notification-receiver`.
   Google Pub/Sub must mint an OIDC token whose audience and service-account
   email exactly match the configured values.
7. Send an Apple provider test notification and a Google test notification.
   Test events must return an ignored/accepted response and must not create or
   modify a grant.
8. In Sandbox/TestFlight and Play internal testing, prove purchase,
   duplicate-delivery, renewal, billing-retry/grace, cancel-at-period-end,
   expiration, refund/revoke, and cross-account rejection.
9. Invoke reconciliation only through an approved backend or operator path.
   Supply event IDs and temporary provider binding material over TLS; never log
   the header secret, raw Google token, or raw Apple JWS.
10. Save sanitized evidence: deployment IDs, Function versions, migration
    versions, HTTP status/error codes, test account identifiers reduced to
    non-secret aliases, and resulting grant state.

Do not declare production GO from a successful deploy or a `200` response
alone. The durable event, subscription state, and expected grant transition
must agree.

## Monitoring

Monitor sanitized counts and error codes for:

- rejected signature/OIDC/authentication attempts;
- `binding_not_found`, `binding_mismatch`, and `grant_not_found`;
- provider API failures and reconciliation failures;
- duplicate-event volume;
- transitions to revoked, refunded, expired, on-hold, or paused.

Logs may contain stable error codes and event row IDs. They must not contain raw
purchase tokens, raw signed payloads, private keys, bearer tokens, or service
role credentials.

## Fail-closed rollback

Rollback is operational and forward-only:

1. Disable the Apple notification URL and Google Pub/Sub push subscription to
   stop new callbacks.
2. Stop every reconciliation scheduler/operator and rotate
   `STORE_RECONCILIATION_SECRET`.
3. Roll back Function code by redeploying the last known-good version recorded
   before cutover.
4. Do not delete Store tables, audit events, grants, or migration records. Do
   not reverse an applied migration in place.
5. If the database contract needs correction, restore service by deploying a
   reviewed forward migration. Use backups/PITR before any corrective data
   write.
6. Re-enable one provider at a time and repeat the sandbox evidence gates.

A rollback is complete only when new unapproved grant mutations have stopped,
existing audit data remains reconstructable, and the owner has recorded the
last known-good Function and migration versions.

## Production GO gates

Production operation is `GO` only when all of the following are true:

- repository tests, type checks, formatting, lint, and isolated pgTAP pass;
- linked migration history is aligned;
- runtime setting names and canonical app IDs are verified;
- provider console endpoints and OIDC identities are verified;
- Apple and Google external sandbox flows pass;
- monitoring and rollback owners are assigned;
- no secret value appears in repository, logs, screenshots, or chat.

Until the provider-console and real sandbox checks pass, report
`REPO_READY / NOT_VERIFIED_EXTERNAL`.
