# iOS Shared Tag Stage 1 Foundation

Goal
- Fix the Stage 1 iOS shared-tag sync design gaps so Phase A/B can build on stable storage, routing, and execution guarantees.

Context
- Android already defines the source-of-truth contract for shared-tag sync v1.
- iOS is still the local Phase 1a/1b saver, so Stage 1 must add only the minimum foundation needed to avoid unsafe behavior later.

Constraints
- Do not widen scope into full account-wide URL sync.
- Do not change Android RPC shapes, auth flow shape, or URL normalization contract.
- Keep Main / Archive / Detail local-first and compatible with existing Phase 1a/1b behavior.
- Use `URLSession + Codable` and boring local storage primitives.

Done when
- Shared-only URL cache rows cannot leak into Main / Archive.
- App and Share Extension SQLite access uses one concurrency model.
- Pending invite tokens survive restart and auth interruption.
- Shared-tag sync execution can be serialized per `authUserId`.

## Issue Map

- Finding 1: split `url_entries` cache existence from Main / Archive visibility through provenance counters.
- Finding 2: move SQLite opening and retry policy into one shared owner used by app and Share Extension.
- Finding 3: store pending invite token durably in secure storage and restore it on bootstrap.
- Finding 4: add a per-user single-flight sync executor with trigger coalescing.

## Visibility / Provenance Model

- `url_entries` remains the canonical URL row store for:
  - local Main / Archive / Detail
  - shared-tag detail rendering
  - metadata fetch targets
- iOS adds:
  - `local_provenance_count INTEGER NOT NULL DEFAULT 1`
  - `shared_reference_count INTEGER NOT NULL DEFAULT 0`
- Main / Archive query rule:
  - show rows only when `local_provenance_count > 0`
  - then apply `record_state`
- Shared-only row rule:
  - snapshot reconcile may reuse/create a row with `local_provenance_count = 0`
  - that row stays hidden from Main / Archive even if `record_state = ACTIVE`
- Promotion rule:
  - if the user later saves a hidden shared-only row locally, reuse the row and set `local_provenance_count = 1`
- Orphan cleanup rule:
  - safe hard delete requires both `local_provenance_count = 0` and `shared_reference_count = 0`
  - if local delete grace finishes while `shared_reference_count > 0`, convert the row back to hidden shared-cache state instead of deleting it

## SQLite Concurrency Model

- Both the app and Share Extension instantiate repositories against the same DB file, so they must share:
  - identical open path
  - identical pragmas
  - identical busy timeout
  - identical bounded retry behavior
- Shared `SQLiteDatabase` decisions:
  - use `WAL`
  - enable `foreign_keys`
  - use `sqlite3_busy_timeout(3000)`
  - retry write statements and transaction boundaries up to a small bounded limit on `SQLITE_BUSY` / `SQLITE_LOCKED`
- Transaction grain:
  - share-save writes stay short
  - future snapshot reconcile must avoid holding a single lock across the whole snapshot application

## Pending Invite Persistence Lifecycle

- Storage:
  - Keychain generic-password item
  - one durable pending invite record at a time for Stage 1
- Saved when:
  - app receives `urlsaver://invite/{token}`
- Restored when:
  - app bootstrap runs after launch or restart
- Cleared when:
  - invite accept succeeds
  - invite accept fails as invalid/expired
  - user explicitly cancels
- Not cleared when:
  - logout
  - auth retry required
  - sign-up needs email confirmation

## Per-User Sync Executor Lifecycle

- Executor key: `authUserId`
- Enqueue behavior:
  - if idle for that user, start sync immediately
  - if already running for that user, mark `needsRerun = true`
- Completion behavior:
  - if `needsRerun = true`, clear the flag and run exactly once more
  - if no rerun is pending, drop the in-memory state for that user
- Different users may have independent executor state.
- Later trigger sites must all call this executor instead of calling sync directly:
  - sign-in
  - invite accept
  - foreground stale-check
  - shared-tag URL add/remove

## Source References

- `docs/shared-tag-sync-architecture.md`
- `app/src/main/java/jp/mimac/urlsaver/domain/SharedTagSyncContracts.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultTagRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncRemoteDataSource.kt`
- `ios/URLSaverShared/Data/URLRepository.swift`
- `ios/URLSaverShared/Support/SQLiteDatabase.swift`
- `ios/URLSaverShared/Data/PendingInviteStore.swift`
- `ios/URLSaveriOS/App/SharedTagSyncExecutor.swift`
