# Codex Prompt: List Swipe Archive/Delete

Use this prompt when asking Codex to implement real swipe actions in the list UI for `URL共有アプリ_UrlSaver`.

## Issue map

1. Product contract drift
この変更以前は `スワイプ archive/delete` が out of scope だったため、実装時は docs とコードを同時更新して契約のズレを防ぐ必要がある。

2. Gesture implementation gap
The current list card is tap-only, so swiping does nothing even though it can feel like a swipeable card.

3. Action semantics
Archive and delete must reuse existing repository/ViewModel behavior, especially the DB-backed pending delete + Undo flow.

4. Navigation safety
Card tap must still open Detail, and swipe must not accidentally trigger detail navigation or double-fire actions.

5. Verification drift risk
Code, docs, tests, and checklist entries must all be updated in one pass so the repo does not claim one behavior while shipping another.

## Prompt

```md
Goal
Implement a deliberate spec change so that the Main list supports real swipe actions for active entries:
- swipe start-to-end: archive
- swipe end-to-start: delete to pending delete state using the existing Undo flow

Context
- Repository: `URL共有アプリ_UrlSaver`
- This is an intentional scope expansion. The previous Phase 1a / 1b docs marked `スワイプ archive/delete` as out of scope, but this task overrides that and requires the contract to be updated.
- Current behavior: list cards are tap-only and open Detail. Archive/delete currently exist from Detail actions and repository methods.
- Preserve these existing invariants unless direct repo evidence requires otherwise:
  - `normalizedUrl` remains the duplicate key
  - `openUrl = normalizedUrl`
  - card tap still opens Detail
  - delete remains DB-backed pending delete + Undo, not immediate hard delete
  - existing ViewModel/Repository responsibility split remains intact

Constraints
- Inspect the current filesystem state first. Do not rely on git history as the source of truth.
- Update implementation, tests, and docs in the same pass. Do not leave spec/code drift behind.
- Implement swipe on the Main list for active entries only unless current repo evidence shows Archive list must also change for consistency.
- Reuse existing archive/delete flows instead of inventing a second path in Compose.
- Do not move business logic into composables beyond gesture event wiring.
- Do not introduce Phase 1c-style scope creep such as search, tags, sorting changes, or restore-on-swipe.
- Do not weaken existing undo, snackbar, metadata, or detail navigation behavior.
- If Compose swipe APIs require a non-trivial UI pattern choice, prefer the smallest stable solution that fits the existing app style.

Done when
- Main list entries can actually be swiped.
- Swiping right archives the active item through the existing application flow.
- Swiping left sends the item into the existing pending delete flow and keeps Undo behavior working.
- Tapping a card still opens Detail.
- Swipe actions cannot silently fail; if the action fails, the user gets truthful feedback through the existing event patterns.
- Repo docs no longer claim swipe archive/delete is out of scope.
- Tests cover the new behavior at the appropriate layers.
- `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, and `./gradlew lintDebug` pass.
- `./gradlew connectedDebugAndroidTest` is executed and its result is recorded honestly, even if it still fails for an existing blocker.

Files to inspect first
- `AGENTS.md`
- `README.md`
- `docs/phase1a-spec.md`
- `docs/phase1b-draft.md`
- `docs/phase1b-ux-checklist.md`
- `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/MainActivityViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/MainListViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/DetailViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/UrlRepository.kt`
- relevant tests under `app/src/test` and `app/src/androidTest`

Implementation expectations
- Add a real swipe container to Main list items.
- Keep swipe thresholds, animation, and affordance understandable and hard to trigger accidentally.
- Surface archive/delete background affordances clearly enough that the gesture does not feel invisible.
- Route archive/delete through existing ViewModel or repository-backed flows rather than duplicating logic inside `EntryCard`.
- Keep Detail actions as they are unless a small consistency fix is required.
- Update docs to reflect that swipe archive/delete is now supported, including any new guardrails or limitations.
- Update UX checklist entries to cover the new behavior and mark only what is actually verified.

Validation method
- Run:
  - `./gradlew assembleDebug`
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintDebug`
  - `./gradlew connectedDebugAndroidTest`
- If instrumentation still fails due to the existing `InputManager.getInstance` issue or another external blocker, record the exact failure and do not mark it as passed.

Failure-handling behavior
- If you find a conflict that would break repo invariants, data compatibility, or the pending delete contract, stop and report the blocker instead of shipping a partial workaround.
- Do not leave a half-implemented swipe UI that animates but does not execute repository actions.
- Do not silently downgrade to a different interaction model without saying so.

Output format
- `Audit`
  - short note on current cause of the non-working swipe expectation
- `Plan`
  - concise implementation plan before edits if the changes are substantial
- `Changes made`
  - short summary of implementation and doc/test updates
- `Validation`
  - exact pass/fail status for each required command
- `Risks`
  - only remaining real risks or blockers
```
