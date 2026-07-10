# Store Screenshot Capture Plan

## Goal
Google Play / App Store に提出するスクリーンショットを、最終提出バイナリと一致した状態で撮影できるようにする。

## Current Inventory

| Path | Platform | Size | Store-ready judgment |
|---|---|---:|---|
| `artifacts/store-assets/screenshots/android/android-emulator-debug-localonly-home.png` | Android | 1080 x 2400 | PARTIAL_RC_EVIDENCE: local-only debug build forced with `SHARED_TAG_CLOUD_ENABLED=false`, empty Supabase values, ads false. Confirms home screen has no profile/shared-tag cloud row, but not a final Play upload because it is one screen and not generated from the signed release AAB. |
| `artifacts/store-assets/screenshots/ios/ios-iphone17-home-local-only.png` | iOS | 1206 x 2622 | PARTIAL_RC_EVIDENCE: Release simulator app on iPhone 17. Accepted 6.3-inch screenshot size family and no cloud/profile UI visible, but only one screen and no demo URL data. |
| `artifacts/ui-review/2026-05-01/cross-device-full-test/android-home-initial.png` | Android | 1080 x 2424 | UNVERIFIED: current final release build after later UI/tag changes is not proven identical. |
| `artifacts/ui-review/2026-05-01/cross-device-full-test/android-manual-add-open.png` | Android | 1080 x 2424 | UNVERIFIED: useful reference, not final store capture. |
| `artifacts/ui-review/2026-05-01/cross-device-full-test/android-detail-test-url.png` | Android | 1080 x 2424 | UNVERIFIED: useful reference, not final store capture. |
| `artifacts/ui-review/2026-05-01/cross-device-full-test/android-archive-screen.png` | Android | 1080 x 2424 | UNVERIFIED: useful reference, not final store capture. |
| `artifacts/ui-review/2026-05-01/cross-device-full-test/android-export-sheet.png` | Android | 1080 x 2424 | UNVERIFIED: useful reference, not final store capture. |
| `artifacts/ui-review/2026-05-01/cross-device-full-test/ios-simulator-home.jpg` | iOS | 368 x 800 | NOT_APPLICABLE for App Store upload: below accepted App Store screenshot sizes. |
| `artifacts/ui-review/2026-05-01/topbar-icons/ios-topbar-icons.png` | iOS | 1206 x 2622 | UNVERIFIED: accepted 6.3-inch size family, but not final store capture. |

## Required Capture Set

### Google Play
- Use at least 2 phone screenshots; recommended set below:
  1. Home list with saved URLs and filters.
  2. Manual URL add sheet.
  3. Detail screen with title, memo, tags, and actions.
  4. Archive screen.
  5. Export screen.
  6. Do not include shared-tag cloud/profile screenshots for local-only v1.0.
- Suggested output directory: `artifacts/store-assets/google-play-screenshots/YYYY-MM-DD/`.
- Current local evidence directory: `artifacts/store-assets/screenshots/android/`.
- Final upload status: `NEEDS_USER_ACTION`. Capture a complete set from the final signed release or a release-equivalent install with clean demo data.

### App Store
- Upload 1 to 10 screenshots per required display family.
- Minimum practical set:
  1. Home list.
  2. Manual add.
  3. Detail.
  4. Archive.
  5. Export.
  6. Do not include shared-tag cloud/profile screenshots for local-only v1.0.
- Capture with an App Store accepted simulator/device size. Current useful accepted iPhone sizes include 6.9-inch, 6.5-inch, 6.3-inch, 6.1-inch, and 5.5-inch families.
- Suggested output directory: `artifacts/store-assets/app-store-screenshots/YYYY-MM-DD/`.
- Current local evidence directory: `artifacts/store-assets/screenshots/ios/`.
- Final upload status: `NEEDS_USER_ACTION`. Capture a complete set from the final App Store candidate after distribution signing or final simulator RC approval.

## Capture Rules
- Capture only after the exact release candidate build is selected.
- Screenshots must not include private personal data, real invite tokens, private emails, or credentials.
- For local-only v1.0, do not include cloud/profile screenshots that imply account features are public.
- If shared-tag cloud is enabled, include account deletion and shared-tag behavior in review notes, not necessarily in screenshots.

## Current Status
- Existing screenshots and the two newly captured local-only screenshots are useful UI evidence but are not a complete store upload set.
- Final store screenshots are `NEEDS_USER_ACTION` until a complete set is captured from the final RC with demo data and owner approval.
