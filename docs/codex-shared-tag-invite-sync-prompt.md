# Codex Prompt: Shared Tag Invite Sync MVP

Use this prompt when asking Codex to implement cross-device sharing for shared tags in `URL共有アプリ_UrlSaver`.

## Issue map

1. Share contract split
Current shared-tag "share" behavior is a same-device local deep link, not cross-device participation. The implementation must keep that local route intact while adding a separate cloud-sharing invite flow.

2. Product scope control
The requested feature is not "sync everything." Only explicitly shared tags should become cross-device shared, while ordinary saved URLs and local tags remain local-only.

3. Backend participation gap
The current sync contracts include tag/member/url syncing and an `invite_member` op, but they do not yet provide a public invite-token flow that lets a recipient join from a shared link.

4. Freshness expectation
Current sync is action-driven through WorkManager. To make "open the app and see recent shared-tag state" believable, foreground sync with light throttling is needed.

5. Permission drift risk
The domain model already distinguishes `OWNER`, `EDITOR`, and `VIEWER`, but the UI is not yet role-aware. Without owner/editor boundaries, destructive or admin actions can appear available even when the backend would reject them.

6. Auth UX gap
The main code has a stored auth session provider but no visible sign-in/join flow that updates it. Shared-tag invite acceptance is not complete unless the user can authenticate within this feature slice.

7. Spec/code drift risk
Because shared-tag behavior spans Android UI, local Room projection, backend RPCs, tests, and docs, partial implementation will quickly leave the repo inconsistent.

## Prompt

```md
Goal
Implement a production-safe MVP for cross-device shared tags in `URL共有アプリ_UrlSaver`.

This MVP must make it possible to share only shared tags across devices/users, while keeping ordinary saved URLs and local-only tags local. The goal is implementation completion in this repo, not a design-only answer.

Context
- Repository: `URL共有アプリ_UrlSaver`
- Source of truth is the current filesystem state.
- Existing local shared-tag deep links such as `urlsaver://tag/{tagId}` are same-device only and must remain that way.
- Existing shared-tag sync already has:
  - local Room projection with `LOCAL_ONLY` / `SYNCED`
  - outbox-based sync
  - `SharedTagSyncWorker`
  - Supabase RPC-based apply + snapshot pull
- The current codebase does not yet have a complete public invite-token join flow.
- The current codebase also appears to lack a visible user auth UX that updates `SharedTagAuthSessionProvider`, so auth must be handled as part of this feature slice.

Product contract for this MVP
- Only shared tags are cross-device shareable.
- Ordinary saved URLs outside a shared tag are never auto-shared.
- Local-only tags remain local unless explicitly migrated or explicitly created as cloud-shared.
- Cross-device participation uses a backend-issued invite token, not a local Room `tagId`.
- The old local deep link route remains separate and continues to mean same-device only.
- "Same content" for MVP means the shared tag's URL list only.
- `title`, `memo`, metadata body/thumbnail/title fetch state, and other per-entry presentation details are not sync guarantees for MVP.
- Different devices may render the same shared tag cards a little differently depending on local metadata fetch progress.

Preserve these invariants unless direct repo evidence forces otherwise
- `normalizedUrl` remains the duplicate key for saved URLs.
- `openUrl = normalizedUrl`.
- Existing ViewModel / Repository / Worker / Domain responsibility boundaries remain intact.
- Metadata-only updates must not change `updatedAt`.
- Local-only URL saving behavior must continue to work when signed out.
- Shared-tag UI remains Room-driven; UI should read local DB state, not assemble remote state directly.

Required implementation scope
- Add a real cross-device invite flow for shared tags using backend-issued invite tokens.
- Add the backend support required for that invite flow, including token creation and token acceptance.
- Add app-side handling for receiving an invite link and joining the shared tag.
- Add the smallest real auth UX needed so a recipient can sign in and accept an invite in-app.
- Add foreground sync enqueue behavior with light throttling so opening/returning to the app refreshes shared-tag state opportunistically.
- Make shared-tag permissions role-aware in the UI for MVP:
  - `OWNER`: can invite, rename if implemented, and delete the shared tag
  - `EDITOR`: can add/remove URLs within the shared tag
  - `VIEWER`: out of scope for full UX support in MVP unless repo work naturally requires partial support; do not expand the feature around viewer-only polish
- Change default tag creation so normal new tags are local by default.
- Keep cloud sharing explicit:
  - either create as shared intentionally
  - or migrate an existing local tag to cloud sharing intentionally
- Update docs and tests in the same pass.

Explicitly out of scope
- Sharing all saved URLs globally
- Syncing URL titles, memos, or metadata across devices
- Replacing the existing local deep link behavior with the invite flow
- General Phase 1c expansion
- Search, tags outside the existing shared-tag model, WebView, or unrelated architecture rewrites
- Realtime sockets/push as a requirement for MVP

Constraints
- Inspect the repo first. Do not rely on memory or assumptions.
- Do not remove the existing local deep link contract. Keep it working as same-device only.
- Add a separate invite route for cloud participation. Choose a stable custom-scheme format if repo evidence does not already define one, then document it and test it.
- Do not use local `tagId` as the cross-device invite payload.
- Implement invite participation through backend-issued invite tokens plus acceptance that results in an active membership.
- Do not reuse `invite_member(user_id)` as the public link-sharing mechanism unless you also prove it satisfies the invite-token UX and active-membership requirements end-to-end.
- Keep the sync transport WorkManager-based. Do not move sync into ad hoc UI-thread networking.
- Foreground freshness should enqueue sync through existing scheduling patterns, not bypass them.
- Add light sync throttling based on local sync state such as `lastPulledAt`, so app foregrounding does not aggressively re-sync every time.
- Because auth UX is currently incomplete, implement the smallest real user-facing auth slice needed to sign in and join a shared tag. Do not leave shared-tag join dependent on hidden developer-only state manipulation.
- If you need a new dependency for auth, choose the smallest stable option and justify it briefly in the final report.
- Keep business logic out of composables beyond event wiring and state display.
- Respect existing destructive-operation safeguards and data-compatibility expectations.
- Update repo docs so they truthfully describe:
  - what is shared
  - what is not shared
  - how invite links work
  - what "latest" means in practice
  - any auth prerequisite for participation

Done when
- A normal new shared tag is local by default unless the user explicitly chooses cloud sharing.
- A local tag can be explicitly migrated to cloud sharing, preserving its existing local contents.
- A cloud-shared tag can produce a shareable invite link/token.
- Another user can open that invite link in the app, authenticate if needed, accept it, and become an active member.
- After acceptance and sync, the recipient sees that shared tag and its URL list locally.
- Shared-tag syncing remains limited to the tag's URL list for MVP.
- Opening or returning to the app opportunistically enqueues shared-tag sync with throttling.
- Owner-only actions are not falsely shown as available to non-owners.
- Editors can add/remove URLs in shared tags they belong to.
- Local-only URLs and local-only tags remain local and are not accidentally exposed to other users.
- Docs, tests, and implementation all agree on the behavior.
- Required validation commands are run and reported honestly.

Files to inspect first
- `AGENTS.md`
- `README.md`
- `docs/phase1a-spec.md`
- `docs/phase1b-draft.md`
- `docs/shared-tag-sync-architecture.md`
- `app/src/main/java/jp/mimac/urlsaver/domain/TagModels.kt`
- `app/src/main/java/jp/mimac/urlsaver/domain/SharedTagSyncContracts.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultTagRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/TagRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/TagDao.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncScheduler.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncAuth.kt`
- `app/src/main/java/jp/mimac/urlsaver/ShareReceiverEntrypointRouter.kt`
- `app/src/main/java/jp/mimac/urlsaver/MainActivity.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/TagDetailScreen.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/TagDetailViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/TagListViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- `app/src/test/java/jp/mimac/urlsaver/TagRepositoryTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/SharedTagSyncRepositoryTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/ShareReceiverActivityEntrypointTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/MainActivityViewModelSecondaryIntentTest.kt`
- `supabase/migrations/20260420120000_shared_tag_sync.sql`
- `supabase/tests/shared_tag_sync_validation.sql`

Implementation expectations
- Introduce explicit app concepts for:
  - shared-tag invite creation
  - shared-tag invite acceptance
  - authenticated participation
- Keep the invite flow separate from manual JSON import/export fallback behavior.
- Ensure invite acceptance results in membership that `pull_shared_tag_snapshot()` will actually return for the recipient, or evolve the backend contracts safely so that outcome becomes true.
- If backend schema changes are needed, include forward-safe migrations and update SQL validation coverage.
- Add Android tests for:
  - local tag creation defaulting to local scope
  - explicit migrate-to-cloud behavior
  - invite route parsing and app entry behavior
  - throttled foreground sync enqueue behavior
  - role-aware UI or ViewModel behavior where practical
  - invite acceptance / repository reconciliation at the appropriate layer
- Update any misleading copy that currently implies shared-tag "share" means same-device-only when the tag is truly cloud shared, while preserving truthful copy for local deep links.
- If a full rename flow for owners is not already present, do not invent a large rename feature just to satisfy the role matrix; at minimum, prevent delete/invite/admin affordances from appearing to editors.

Validation method
- Run:
  - `./gradlew assembleDebug`
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintDebug`
  - `./gradlew connectedDebugAndroidTest`
- Run or update relevant Supabase SQL validation if the backend contract changes.
- If any required validation cannot be executed in this environment, say exactly what was not run and why.

Failure-handling behavior
- If the repo lacks enough auth/backend capability to complete invite participation safely, do not fake completion. Report the exact blocker and stop rather than shipping a misleading partial flow.
- Do not leave a cloud-share button that still sends only a local `tagId` deep link while docs claim cross-device sharing works.
- Do not claim "latest" semantics unless foreground sync plus actual snapshot reconciliation behavior support that claim.
- Do not silently broaden sharing beyond shared tags.
- Do not leave role checks to backend rejection alone when the UI can reasonably avoid exposing invalid owner-only actions.

Output format
- `Audit`
  - short summary of the current shared-tag gap before edits
- `Plan`
  - concise implementation plan before major edits
- `Changes made`
  - short summary of app, backend, docs, and test changes
- `Validation`
  - exact pass/fail status for each required command and any SQL validation
- `Risks`
  - only remaining real limitations, follow-ups, or blockers
```
