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

Done when
- Local-only tags still work when signed out.
- Signed-in sync-capable clients can create or migrate tags into synced scope.
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

## Android Model

- `tags`
  - local-only and synced tags share the same local table
  - synced rows add `scope=SYNCED`, `authUserId`, `remoteTagId`, and sync metadata
- `tag_url_cross_refs`
  - keeps local entry assignments
  - synced rows add remote URL ids, normalized/raw URL copies, auth scope, and sync metadata
- `shared_tag_members`
  - local cache of remote membership
- `shared_tag_sync_outbox`
  - durable queued operations
- `shared_tag_sync_state`
  - per-account client id and pull bookkeeping

## Sync Loop

1. User action writes optimistic local state and queues an outbox op.
2. WorkManager runs `SharedTagSyncWorker` with the target `authUserId` in input data.
3. Worker submits queued ops to `apply_shared_tag_ops(...)`.
4. Worker pulls `pull_shared_tag_snapshot()`.
5. Snapshot replaces the synced projection for the active `authUserId`.

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
