# Shared Tag Sync Contracts

URL normalization contract: v1

- `contracts/shared-tag-sync/url-normalization-v1.json` is the single source of truth for shared URL normalization.
- v1 is intentionally service-agnostic: it does not rewrite YouTube, X, Instagram, or TikTok URLs into another host/path.
- Query strings are preserved after trimming the URL shape, including YouTube `t` and X `s` parameters.
- The sync payload and snapshot `normalization_version` is `1`; a client must not silently consume another version.
- `openUrl` must equal `normalizedUrl` for every URL entry.

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
- Android and iOS unit tests must execute the normalization vectors directly.
- `scripts/verify_shared_url_normalization_contract.py` passes the same JSON fixture to the SQL test through `psql`.
- Server SQL validation must assert the same expected normalization outcomes and the stored-row version/invariant.

Failure-handling behavior
- If a client cannot normalize an input URL, it must not enqueue a sync write for that URL.
- If a sync attempt fails before operation-level statuses are confirmed, the outbox item stays pending and retries later.
- If an RPC response returns a terminal operation-level status, the client retires that outbox row as local `FAILED` and does not resend it forever.
- If operation status is unknown/missing, the client treats the sync as failed instead of silently succeeding.
