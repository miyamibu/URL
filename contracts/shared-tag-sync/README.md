# Shared Tag Sync Contracts

Goal
- Define the cross-platform contracts for Android, future iOS, and Supabase-backed shared tag sync.

Context
- UI stays local-first and reads only local persistence.
- Sync is `outbox -> RPC -> pull snapshot`.
- Realtime is optional and may only trigger pull; it is never authoritative.

Constraints
- `normalizedUrl` remains the app/domain-level dedupe key for local `url_entries`.
- Remote shared tag URLs use a surrogate `id` plus `unique(tag_id, normalized_url)`.
- Sync v1 covers shared tags, members, and shared tag URLs only.
- Collections, `userTitle`, `memo`, and metadata are not synced in v1.

Done when
- Android and server share the same operation and snapshot shapes.
- Future iOS implementation can consume the same contracts without reverse-engineering Android code.

Output format
- Kotlin serializable contracts live in the Android app code.
- URL normalization vectors live in [`url-normalization-v1.json`](./url-normalization-v1.json).

Validation method
- Android unit tests must execute the normalization vectors.
- Server SQL validation should assert the same expected normalization outcomes where practical.

Failure-handling behavior
- If a client cannot normalize an input URL, it must not enqueue a sync write for that URL.
- If a sync attempt fails before operation-level statuses are confirmed, the outbox item stays pending and retries later.
- If an RPC response returns a terminal operation-level status, the client retires that outbox row as local `FAILED` and does not resend it forever.
- If operation status is unknown/missing, the client treats the sync as failed instead of silently succeeding.
