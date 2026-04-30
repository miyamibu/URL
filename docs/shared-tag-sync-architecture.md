# Shared Tag Sync Architecture

Goal
- Add production-safe cross-device shared tag sync on top of the existing Android local tag feature without replacing unrelated local behavior.

Context
- Existing `tags` and `tag_url_cross_refs` stay in use for local-only tags.
- Synced tags extend the same local tables with scope, auth, and outbox metadata so current UI can remain Room-driven.
- Supabase/Postgres is the remote source of truth.

Constraints
- UI reads local DB only.
- Sync writes go through RPC, not direct multi-table client writes.
- Pull starts with full snapshot.
- JSON export/import remains the manual backup and external-sharing fallback.
- Local shared-folder links use `urlsaver://tag/{tagId}` and are same-device only; they are not backup or cross-device sync.
- Cross-device participation uses backend-issued invite tokens (`urlsaver://invite/{token}`), not local Room `tagId`.
- Shared-tag freshness stays WorkManager-based; foreground refresh should enqueue sync with light throttling instead of doing ad hoc UI-thread networking.

Done when
- Local-only tags still work when signed out.
- New tags default to local-only unless the user explicitly creates or migrates a tag into synced scope.
- Signed-in sync-capable clients can create invite tokens for synced tags they own.
- Invite recipients can authenticate in-app, accept the token, become `ACTIVE` members, and then receive the shared tag via snapshot reconciliation.
- Snapshot reconciliation rebuilds the local synced view from Supabase state.

Output format
- Backend assets live under `supabase/`.
- Cross-platform contracts and vectors live under `contracts/shared-tag-sync/`.

Validation method
- Android unit tests cover normalization vectors, outbox/reconcile behavior, migration flow, and user scoping.
- SQL validation scripts exercise schema, RLS-safe helper logic, RPC idempotency, and uniqueness.

Failure-handling behavior
- When auth or Supabase config is unavailable, the app keeps local-only tags working and does not enqueue remote sync.
- When an outbox sync attempt fails before a server operation result is confirmed, local optimistic state remains pending and retries later.
- When `apply_shared_tag_ops` returns an operation-level terminal status, that op is retired as local `FAILED` and is not resent forever.
- Unknown or missing operation statuses are treated as sync failure (never silent success).
- Invalid or expired invite tokens never create partial membership locally; the app surfaces the failure and leaves local-only data untouched.

## Android Model

- `tags`
  - local-only and synced tags share the same local table
  - synced rows add `scope=SYNCED`, `authUserId`, `remoteTagId`, and sync metadata
- `tag_url_cross_refs`
  - keeps local entry assignments
  - synced rows add remote URL ids, normalized/raw URL copies, auth scope, and sync metadata
- `shared_tag_members`
  - local cache of remote membership
- `shared_tag_invites`
  - remote-only invite tokens hashed server-side
  - invite acceptance converts a recipient into an `ACTIVE` member so `pull_shared_tag_snapshot()` can return the tag
- `shared_tag_sync_outbox`
  - durable queued operations
- `shared_tag_sync_state`
  - per-account client id and pull bookkeeping

## Product Contract

- Cross-device sharing applies only to tags in `scope=SYNCED`.
- Ordinary saved URLs outside a shared tag remain device-local.
- MVP sync parity means the shared tag's URL list only.
- `title`, `memo`, metadata text, thumbnails, and fetch state are local presentation details and are not sync guarantees.
- UI permissions should stay role-aware:
  - `OWNER`: invite members, delete the shared tag, and perform owner-only admin actions if implemented
  - `EDITOR`: add and remove URLs
  - `VIEWER`: out of scope for full MVP polish; avoid exposing edit/admin affordances

## Invite Flow

1. Owner migrates or creates a tag in synced scope.
2. Owner calls `create_shared_tag_invite(...)` and shares `urlsaver://invite/{token}`.
3. Recipient opens the invite route in the app.
4. If needed, recipient signs in or signs up through the in-app Supabase Auth flow.
5. App calls `accept_shared_tag_invite(...)`.
6. Backend records an `ACTIVE` membership for the recipient.
7. WorkManager sync pulls a fresh snapshot and the shared tag appears through local Room queries.

## Sync Loop

1. User action writes optimistic local state and queues an outbox op.
2. WorkManager runs `SharedTagSyncWorker` with the target `authUserId` in input data.
3. Worker submits queued ops to `apply_shared_tag_ops(...)`.
4. Worker pulls `pull_shared_tag_snapshot()`.
5. Snapshot replaces the synced projection for the active `authUserId`.

## Freshness Expectations

- Tag edits still enqueue sync immediately through the repository/scheduler path.
- App foregrounding should opportunistically enqueue sync for the active auth session when the last pull is stale enough.
- MVP aims for "latest when you open the app" in an opportunistic sense, not hard realtime delivery.

## Multi-Account Safety

- Synced local rows are scoped by `authUserId`.
- Worker execution is also scoped by `authUserId`; if active session and requested user mismatch, sync no-ops instead of syncing the wrong account.
- Queries merge `LOCAL_ONLY` rows with only the current user's `SYNCED` rows.
- Logging out or switching users hides previous synced rows without touching local-only tags.

## iOS Readiness

- An `ios/` workspace now exists in this repo, but shared tag sync v1 implementation remains Android-first.
- Future iOS work should reuse:
  - `contracts/shared-tag-sync/url-normalization-v1.json`
  - the serializable operation/snapshot contracts in Android source
  - the same Supabase RPC and snapshot endpoints

## iOS Stage 1 Corrections

- `url_entries` on iOS is the canonical URL cache and detail source, not the Main / Archive visibility source by itself.
- iOS `url_entries` now reserves two provenance counters:
  - `local_provenance_count`
    - `> 0` means the row is eligible for Main / Archive visibility
    - `0` means the row may exist only as a shared-tag cache row and must stay hidden from Main / Archive
  - `shared_reference_count`
    - counts shared-tag references materialized from snapshot reconcile
    - rows with `local_provenance_count = 0` and `shared_reference_count = 0` are cleanup candidates
- Shared-tag hub/detail on iOS must read `shared_tag_*` tables plus membership scoped by active `authUserId`; it must not infer shared-tag visibility from `url_entries` presence alone.
- When a shared-only row already exists in `url_entries` and the user saves the same URL locally on iOS, the row should be promoted by setting `local_provenance_count > 0` instead of being treated as a duplicate hidden cache row.
- When local delete grace expires for a row that still has shared references, iOS must drop local provenance and keep the cache row instead of hard-deleting it.

## iOS SQLite Concurrency Model

- iOS app and Share Extension must open the same SQLite file through one shared `SQLiteDatabase` layer, not through ad hoc per-feature SQLite code.
- The shared layer must configure:
  - `PRAGMA journal_mode=WAL`
  - `PRAGMA foreign_keys=ON`
  - `sqlite3_busy_timeout(...)`
  - bounded retry on `SQLITE_BUSY` / `SQLITE_LOCKED` for write statements and transaction boundaries
- Share saves stay inside short write transactions.
- Snapshot reconcile on iOS must avoid a single long write lock; when Phase A/B reconcile is implemented, it should be broken into bounded write windows rather than one oversized transaction.

## iOS Pending Invite Lifecycle

- A pending invite token on iOS is durable and secure state, not UI memory.
- The token must be stored in secure storage because it is a bearer token.
- The token must survive:
  - app restart
  - sign-up that requires email confirmation
  - later sign-in retry
  - account switch
- The token is cleared only when:
  - invite accept succeeds
  - invite accept fails with invalid-or-expired semantics
  - the user explicitly cancels invite participation
- Logging out must not automatically clear the pending invite token.

## iOS Sync Executor

- iOS shared-tag sync must run through a per-`authUserId` single-flight executor.
- For the same `authUserId`, only one `apply -> pull -> reconcile` run may execute at a time.
- If another trigger arrives while sync is running for that user, iOS must coalesce it into exactly one rerun request rather than dropping it or starting parallel sync.
- Sign-in, invite accept, foreground stale-check, and shared-tag URL add/remove should all enqueue through this executor once those flows are implemented.
