# Prelaunch Report

## Date
2026-05-11

## Goal
外部契約前に実行できる launch 準備の現状を記録し、契約後に残る外部値と承認ゲートを明確にする。

## Current Release Posture
- Android release dry run: local-only / shared-tag cloud disabled.
- iOS release-style build: generic iOS build with code signing disabled.
- Ads: disabled in Android release BuildConfig.
- Billing: not implemented for initial launch.
- External analytics and third-party crash reporting: not integrated for initial launch.

## Validation Results
| Check | Result | Notes |
|---|---|---|
| `git diff --check` | PASS | Whitespace/error check passed. |
| `./gradlew assembleRelease` | PASS | Contract-free build now succeeds with `SHARED_TAG_CLOUD_ENABLED=false`. |
| `./gradlew assembleDebug testDebugUnitTest lintDebug` | PASS | Android debug build, unit tests, and lint passed. |
| Android release artifact inspection | PASS | Release BuildConfig has `ADS_ENABLED=false`, empty AdMob IDs, `SHARED_TAG_CLOUD_ENABLED=false`, empty Supabase URL/key. |
| Android release merged manifest ad scan | PASS | No `com.google.android.gms.ads`, `AD_ID`, or `ACCESS_ADSERVICES_*` entries found in merged release manifest. |
| iOS simulator tests | PASS_WITH_SKIPS | 72 passed, 0 failed, 3 skipped. Skips are live Supabase shared-tag tests when live env is absent. |
| iOS release-style generic build | PASS | `xcodebuild ... -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build -quiet` exited 0. |
| Public web live verification | PASS | `https://miyamibu.xyz/privacy/` returned HTTP 200; both `.well-known` files fetched from `miyamibu.xyz` and parsed as JSON on 2026-05-11. |

## Work Completed
- Android release build no longer requires production Supabase config when shared-tag cloud is disabled.
- iOS live shared-tag XCTest now skips when live Supabase environment is missing instead of failing.
- Contract-free launch plan added.
- Store listing draft added.
- Privacy / Data safety draft added.
- App Links / Universal Links / signing placeholder document added.
- Account deletion docs updated to distinguish local-only release from shared-tag cloud release.

## Worktree Risk
The repo still contains unrelated or pre-existing dirty state outside this launch-prep change. This report does not approve deleting or discarding any of it.

Main categories still requiring owner review:
- Android shared-tag / UI / tests changes.
- iOS shared-tag / UI / project changes.
- App icon assets.
- Evidence artifacts under `artifacts/`.
- Deletion candidates such as `CODEX_INSTRUCTIONS.md`, `archive/root-unrelated/...`, and `tmp/laptimer-*.png`.

## Contract-dependent Blockers
- Apple Developer Team ID and provisioning.
- iOS distribution certificate and App / Share Extension profiles.
- App Store Connect app record, privacy answers, screenshots, review metadata, and upload.
- Google Play Console production access, Play App Signing SHA-256, store listing, Data safety, content rating, and submission.
- Production Supabase URL / publishable or anon key / migrations / Auth settings if shared-tag cloud is enabled.
- Public account deletion URL if account creation is enabled.
- Final replacement and real-device verification of App Links / Universal Links values after issued Apple/Google values are known.

## Failure Handling
- If shared-tag cloud is enabled for release, re-run Android release build with production Supabase values and re-run the live Supabase validation path.
- If ads or billing are enabled later, stop and rewrite store listing, privacy, and Data safety drafts before submission.
- If App Links or Universal Links fail after contract-issued values are available, update only the SHA-256 / Team ID placeholders and verify on real devices.

## Done Criteria Status
Contract-free launch prep is complete to the point that remaining blockers are external contracts, externally issued IDs/keys, physical-device verification, final screenshots, or explicit approval-gated cleanup/commit operations.
