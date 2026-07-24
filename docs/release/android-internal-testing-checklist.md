# Android Internal Testing Checklist

## Scope
Prepare Android internal testing without Codex uploading to Play Console.

## Version and Identity

- [ ] Check `app/build.gradle.kts`.
  - Expected: `applicationId = "jp.miyamibu.urlalbum"`.
  - Expected current source: `versionName = "1.0.15"`, `versionCode = 17`.
  - Stop if: package name changes or version is not the intended release.

## Signing Config

- [ ] Confirm upload keystore exists outside the repo.
  - Expected: no keystore, password, or `.jks` file in git.
  - Stop if: signing material appears in repo or chat.

## Release AAB Build

```bash
./gradlew testDebugUnitTest lintDebug bundleRelease
```

- Expected: tests/lint pass and release AAB is produced.
- Evidence: Gradle summary, AAB path, versionCode/versionName.
- Expected: release `ALLOW_LOCAL_MEDIA_DOWNLOADS=false` even when a resolver URL is configured; the media-save action remains gated until authenticated, user-bound, signed-download, quota, and upstream network-boundary validation is complete.
- Stop if: tests are weakened, release config turns AI flag on, configured media resolver is not HTTPS, or build output contains secrets.

## Play Console Internal Testing Upload

Codex does not upload to Play Console. Manual owner steps:

1. Open Play Console for `jp.miyamibu.urlalbum`.
2. Select Internal testing.
3. Upload the signed AAB.
4. Confirm versionCode/versionName.
5. Add internal testers.
6. Save the tester opt-in link and build screenshot.

Stop if Play Console package, signing certificate, Data Safety, or tester access does not match the intended build.

## Pixel 9a QA

Run `docs/release/manual-qa-matrix.md` Android section on Pixel 9a.

Required highlights:

- fresh install
- upgrade from previous build
- share-sheet save
- Export and AI-safe Export content inspection
- shared tag default AI exclusion
- feature flag off
- local/account deletion clears AI receipt/draft
- crash-free smoke test

## Data Safety

- Confirm Privacy Policy URL is public and current.
- Confirm Data Safety includes account data, saved URLs, tags, shared-tag cloud sync if enabled, contact support, purchases/subscriptions where applicable.
- Confirm AI test functionality is disclosed as selected saved URLs only, with shared tags excluded by default.
- Stop if Data Safety says no collection while cloud sync/contact support/subscriptions are enabled.

## Ads and Data Collection

- Expected: release ads disabled.
- Evidence: release hygiene script output.
- Stop if ad SDK or ad declaration appears without policy update.

## Feature Flag Default Off

- Expected: `AI_TRANSPARENCY_ENABLED=false` for release.
- Expected: normal UI has no public AI entry.
- Stop if default release build exposes unapproved AI UI.

## AI-safe Export

- Inspect `manifest.json`, `entries.jsonl`, `schema.json`, `README_FOR_AI.md`, `redaction_report.json`.
- Stop if raw body, prompt, token, refresh token, attachment, or raw DB id appears.

## Rollback To Previous Internal Build

Manual Play Console action:

1. Halt rollout or remove the bad internal release from tester availability.
2. Promote or keep the previous known-good internal build.
3. Disable remote MCP/AI flags if the issue is backend/connector related.
4. Save incident note using `docs/release/rollback-plan.md`.

## Stop Conditions

- Crash on launch or share save.
- Data loss on upgrade.
- Wrong package/signing.
- raw body/prompt/token in Export/MCP/logs.
- Shared-tag AI eligibility by default.
- Normal UI exposes AI while flag is off.
