# Android 16 / targetSdk 36 verification summary

- Verification date: 2026-07-29 JST
- Store submission update: 2026-07-30 JST
- AVD: `rinbam_api36_pixel9a`
- ADB serial: `emulator-5554`
- Device profile: Pixel 9a, 1080 x 2424
- OS: Android 16 / API 36
- Package operated: `jp.miyamibu.urlalbum`
- Build operated: debug APK, versionCode 19, versionName 1.0.15, targetSdk 36
- Physical Pixel 9a: connected read-only; no install, clear, uninstall, or instrumentation was run

## Verified on the API 36 emulator

- App cold launch and home rendering: PASS
- Edge-to-edge status/navigation areas and five bottom actions: PASS by screenshot inspection
- Manual URL save (`https://example.com/api36-manual`): PASS
- Single `ACTION_SEND` URL intake (`https://example.org/api36-share`): PASS
- Home list to detail and Android back navigation: PASS
- Personal tag creation (`API36`) and selected-filter state: PASS
- Archive action, archived-state feedback, archive list, and back navigation: PASS
- Scrolling on the populated home list: PASS

## Multiple-share boundary

- The production contract uses `ArrayList<String>` for `ACTION_SEND_MULTIPLE`; the corresponding Robolectric coverage passed in the 159-test `testDebugUnitTest` run.
- ADB `am --esa` produces `String[]`, not `ArrayList<String>`, so that invocation was rejected as a test-harness type mismatch and is not counted as device proof.
- A text payload containing multiple URLs reached the tag-selection/save flow, but full `ArrayList<String>` device proof remains unverified.

## Local build validation

- `testDebugUnitTest`: PASS, 159 tests, JDK 21
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `bundleRelease`: PASS
- `scripts/verify_mobile_ui_contract.py`: PASS before and after changes
- `scripts/check_release_hygiene.sh`: PASS
- `scripts/verify_mcp_contract.py`: PASS
- `scripts/check_launch_readiness.sh`: repository-wide clean-tree gate remains `NO_GO_INTERNAL` because the API 36 release changes and this evidence are intentionally uncommitted; the stale daily-evidence issue was resolved on 2026-07-30

## Store boundary

- Play Console previously published production release: versionCode 18, versionName 1.0.15, targetSdk 35, published 2026-07-23 at 100%.
- Signed upload AAB: `/Users/mimac/.urlsaver-signing/app-release-1.0.15-19-target36-upload-signed-20260729.aab`, SHA-256 `f3f9c7b101f945e8013d00b9d534d5a85e4db93a08fae0ff45f5079d2cea1657`.
- `jarsigner -verify` and Bundletool 1.18.3 validation: PASS. The upload certificate owner matches the existing production upload certificate.
- Google Play accepted the AAB as versionCode 19, versionName 1.0.15, minimum API 26, target SDK 36.
- Internal release `1.0.15 (19) target API 36 内部確認` was published to the active internal track on 2026-07-29 at 23:52 JST.
- Internal tester list `Rinbam Internal Testers` is selected with 2 users, and the web opt-in link is active.
- Play validation reported two non-blocking warnings: no deobfuscation file and no native debug symbols. No supported-device loss was reported.
- Generated release AAB: `app/build/outputs/bundle/release/app-release.aab`, SHA-256 `b53eb3be8c930d9c9dbc0fb5cf8225a7b57a674093b20da8f314ba54f6e0b64f`.
- The generated release AAB remains unsigned and must not be uploaded as-is; only the signed upload AAB above was uploaded.
- Physical Pixel 9a remained on versionCode 18 / target SDK 35 at the last check. Play-delivered versionCode 19 overwrite-update and saved-data preservation are `NOT_VERIFIED`; the release owner explicitly authorized production submission without this physical-device test.
- On 2026-07-30, versionCode 19 / target SDK 36 was submitted for a 10% production rollout. Play Console shows `審査中の変更`; an automated quick check was still running with an estimated 14 minutes remaining before Google review. This proves submission only, not public/live availability.
