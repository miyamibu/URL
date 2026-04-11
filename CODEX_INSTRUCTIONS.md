# CODEX_INSTRUCTIONS.md - URL Saver

This file is for `URL共有アプリ_UrlSaver` only.
Any instruction for other projects (for example LapTimer or Apple Watch migration) is out of scope.

## Role

You are Codex working inside the `URL共有アプリ_UrlSaver` repository.
Act as a repo-aware implementation agent.
Do not act as a generic code generator.

## Goal

Eliminate known structural failure modes in this repository
without introducing spec/code/test/checklist drift.

## Scope baseline

Follow `AGENTS.md` and Phase 1a/1b specs as the product contract.
When unsure, prefer removing out-of-scope behavior over widening scope.

## Initial issue list to verify

Treat these as hypotheses to audit against current files:

1. Success/release conditions are undefined.
2. Root is polluted by unrelated files/instructions.
3. Search exists in code although out of scope.
4. Share intake behavior silently degrades in multi-URL/text-rich inputs.
5. Metadata scheduling can fail silently and leave `PENDING` stuck.
6. Metadata extraction is brittle.
7. Privacy defaults are weak (`allowBackup`).
8. Delete grace timer uses ad hoc wall clock in UI layer.
9. Search/list path uses in-memory filtering and does not scale.
10. Release gate artifacts are weak or missing.

## Files to inspect first

- `AGENTS.md`
- `README.md`
- `CODEX_INSTRUCTIONS.md`
- `docs/phase1a-spec.md`
- `docs/phase1b-draft.md`
- `docs/phase1b-ux-checklist.md`
- `build.gradle.kts`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/jp/mimac/urlsaver/app/AppContainer.kt`
- `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`
- `app/src/main/java/jp/mimac/urlsaver/MainActivity.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/RoomModels.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/UrlEntryDao.kt`
- `app/src/main/java/jp/mimac/urlsaver/domain/UrlRules.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/MainActivityViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/ListSearchState.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/DetailViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/worker/MetadataFetcher.kt`
- unit tests and androidTests under `app/src/test` and `app/src/androidTest`

Also scan repo root for files unrelated to URL Saver.

## Operating rules

- Work from current filesystem state only. Do not rely on git history/diff.
- Do not fix docs only or code only. Keep both aligned.
- Do not silently remove diagnostics. Replace with equivalent troubleshooting value, or document why removed.
- Do not fake external integrations.
- Do not do broad refactors unless required by the 10 issues.
- Never mark checks as passed unless actually executed.
- Silent failure is forbidden:
  - no swallowed exception with no surfaced consequence
  - no `PENDING` state that can never complete
  - no UI implying success while degraded

## Required workflow

### Step 1 - Audit classification

Before editing, classify each issue as:

- `confirmed`
- `partially confirmed`
- `not present`
- `superseded`

Produce the short classification list first.

### Step 2 - Decision confirmation

Confirm these fixed decisions are safe from repo evidence:

- remove search rather than adopt it
- move unrelated root files into `archive/`
- disable backup entirely
- finalize crash reporting release decision in docs
- fill only actually verified UX checklist entries

If any decision causes data-loss risk or product contract breakage, stop and ask the user.
Otherwise continue without reopening the choice.

### Step 3 - Implementation order

1. Replace misleading root instructions and archive unrelated root files.
2. Add release criteria to docs.
3. Remove search UI/logic and align docs/tests/checklist.
4. Fix metadata fail-silent paths.
5. Fix share-intake degradation visibility.
6. Disable backup and document privacy stance.
7. Fix clock source via injectable/testable time source.
8. Strengthen release gate in docs and CI (without crash-reporting code).
9. Improve metadata robustness proportionately.

Do not jump ahead while higher-priority structural mismatch remains.

## Implementation requirements

### 1) Success criteria

Update `README.md` and `docs/phase1b-draft.md` with:

- target user (1 sentence)
- core user value (1 sentence)
- binary release gate criteria only (pass/fail)

Do not invent percentage targets without measurement plan.

### 2) Repo pollution

- `CODEX_INSTRUCTIONS.md` must stay URL Saver specific.
- Move unrelated root files to `archive/` in this pass.
- Do not delete unrelated files in this pass.

### 3) Scope drift

Search must be removed from active product surface.
Required:

- remove search UI from `UrlSaverRoot.kt`
- remove `ListSearchState.kt` logic if unused
- remove/adjust tests depending on search
- update `docs/phase1b-draft.md`
- update `docs/phase1b-ux-checklist.md`
- update `AGENTS.md` only when needed for consistency

At the end, docs/code/tests/checklist must all agree that search is out of scope.

### 4) Share intake

Verify current intent handling in `ShareReceiverActivity.kt`.
At minimum, explicitly surface degraded behavior when extra shared content is truncated or URL extraction fails.
No silent dropping.

If full `ACTION_SEND_MULTIPLE` support is too large:

- do not add hidden partial support
- provide explicit degradation behavior
- document as deferred/release blocker item

### 5) Metadata fail-silent

Remove paths where metadata can remain `PENDING` with no runnable work.

Requirements:

- no silent fallback that pretends scheduling succeeded
- no indefinite loading without recovery
- debug builds surface scheduler issues loudly
- release behavior reflects degraded state explicitly and offers retry-equivalent path

### 6) Metadata robustness

Replace regex-only HTML extraction with proper parser-based extraction for `<title>` and `<meta>`.
Add a User-Agent header.
Keep service-specific handling separate where needed.
Do not claim universal support.

### 7) Privacy/backup

Disable backup entirely in this pass.

- set `allowBackup` disabled
- add manifest-level disablement settings needed by supported Android versions
- document current local-storage/privacy stance in `README.md`

Do not add partial backup rules in this pass.

### 8) Clock source

Remove direct `System.currentTimeMillis()` from `MainActivityViewModel.kt`.
Inject `AppClock` or equivalent testable source.
Update unit tests to use fake clock when relevant.

### 9) Search scaling

Because search is removed in this pass:

- remove in-memory search from active surface
- do not build new DAO search architecture now
- ensure no spec claims search support

### 10) Release gate

Add or update:

- `.github/workflows/ci.yml` running:
  - `./gradlew assembleDebug`
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintDebug`
- `docs/phase1b-ux-checklist.md`: mark only actually verified entries
- docs explicitly stating crash reporting release decision for this phase

Do not add Firebase/Sentry code in this pass.

## UI/UX rules

- one clear job per screen/section
- no unnecessary status clutter
- preserve troubleshooting visibility
- keep copy short and concrete
- when degraded, state degradation clearly

## Verification loop

After implementation:

1. Re-audit each issue as fixed/deferred/blocked.
2. Run:
  - `./gradlew assembleDebug`
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintDebug`
3. Run instrumentation when environment allows:
  - `./gradlew connectedDebugAndroidTest`
4. Update docs/checklist to match reality.
5. Re-read changed files for regressions introduced in this pass.

Report results honestly; do not mark unrun checks as passed.

## UX checklist recording rules

When editing `docs/phase1b-ux-checklist.md`:

- mark verified only when actually checked
- separate automated evidence vs human review
- leave human-unverified items clearly not executed

Final report must separate:

- `自動確認済み`
- `人手未実施`
- `環境不足で未実施`

## Done when

All must be true:

- 10 issues re-audited and each fixed/deferred/blocked(external-only)
- no doc/code/test/checklist drift in touched scope
- search no longer implemented while out of scope
- no silent failure pretending success in touched scope
- backup disabled and policy documented
- crash reporting release decision clearly documented (no false completion)
- verification reported as passed/failed/not run

## Final report format

Use this exact order:

1. Audit summary (10 items + status)
2. Decision confirmation
3. Files changed
4. What was implemented
5. Verification run
  - passed
  - failed
  - not run
6. UX verification status
  - 自動確認済み
  - 人手未実施
  - 環境不足で未実施
7. Remaining blockers/deferred items
8. Why this does not reintroduce drift
