# Production sharing and auth verification — 2026-10-14

Status: PARTIAL. The explicitly authorized shared-tag auto-group migration is applied and verified on the linked production project `xocumgxbylmpoobfqows`. Live Google/Apple sign-in, same-user identity linking, and simultaneous Android/iOS sessions remain unverified.

This is the October 14 snapshot. Its auth and device handoffs were subsequently completed or superseded as recorded in [the October 15 follow-up](production-sharing-auth-2026-10-15.md); the migration was not replayed.

## Applied SQL

- Source revision: `e8e34c494a39aa136211bf25009b1977e055c005`.
- Migration: `20261013090000_auto_group_for_shared_tag_invite_accept.sql`.
- SHA-256: `c39c2964af8ff48b9dc08c0d5e4c28ff0eabe801a4497f173524b54d109cf158`.
- Before application, all 68 previous local/remote migration versions matched; the dry run proposed only this migration, with no seeds or roles.
- `supabase db push --linked --yes` completed successfully. All 69 migration versions now match.
- Read-only catalog checks confirmed the nullable UUID column, validated `ON DELETE SET NULL` foreign key, and valid unique partial index.
- Both replaced function bodies have matching local/remote MD5 digests: `accept_shared_invite` = `5d201094ca3789d6e8a21d483e134891`; `add_shared_tag_to_group` = `53e940762bb737c3866ff83f538d74ba`.
- The invitation and group-add functions allow authenticated execution, reject anonymous execution, and retain their security-definer search path `public, private, pg_temp`.
- No production fixtures, fake users, acceptance calls, or existing-row backfill were executed. Functional scenario coverage comes from the previously passing local SQL suite; this production check proves deployment and catalog state.

## Current auth configuration

Read from the authenticated production dashboard; secret values were not printed or saved.

- Google and Apple are enabled. Google client ID and secret are present.
- Apple only has native audience `com.mibu.codebridge.ios`; there is no Web Services ID or OAuth secret configured.
- Apple Developer Services ID list is empty. A registration proposal for `com.mibu.codebridge.web` is prepared on the confirmation screen, but not registered.
- Manual identity linking is disabled. Action-time confirmation for enabling it has been requested.
- Single-session-per-user enforcement is disabled. App callback `urlsaver://auth/callback` is covered by the existing `urlsaver://**` allow-list entry.
- Email sign-in and email confirmation are enabled; anonymous sign-in is disabled.

## Remaining evidence and handoffs

1. Enable manual linking after the requested action-time confirmation, save, and verify persistence.
2. Register/configure the Apple Web Services ID after confirmation, with canonical primary App ID and production Supabase callback. Creation/input of new authentication credentials requires an owner handoff.
3. Verify Google sign-in, adding Apple to that same signed-in account, and subsequent Apple sign-in returning the same user and data. Preserve existing separate identities if a conflict is reported.
4. Verify the same account stays signed in on both canonical mobile apps. At inspection, no Android device was connected; the paired physical iPhone had no available tunnel or detected USB connection, and querying installed apps failed with a device usage assertion error. No app install, reset, or data deletion was performed.

Sanitized evidence: `artifacts/production-auth/2026-10-14/production-verification.json`. Prepared Apple registration and Supabase session screenshots are retained in the same directory. The prior Store submission receipt remains a historical record and is not rewritten.
