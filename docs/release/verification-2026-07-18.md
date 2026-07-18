# 2026-07-18 Verification Record

This record freezes the latest Chapter 13 manual ChatGPT handoff evidence without claiming store or distribution readiness.

## Android physical verification

- Device: Pixel 9a, ADB serial `55211JEBF16639`.
- Canonical package: `jp.miyamibu.urlalbum`.
- Debug build: `versionName=1.0.15`, `versionCode=18`.
- The existing Play-signed install was incompatible with the Debug APK. The attempted `run-as` backup was invalid because the Play build was not debuggable; the 53-byte artifact contains only the error text and is not recoverable data. After explicit approval, the Play install was removed and Debug `versionCode=18` was installed/launched. The installer now validates the tar archive before any future destructive install.
- Verified sequence: select a local tag, review one eligible URL, review the fixed included/excluded/redaction disclosure, confirm unknown-secret warning, create the ChatGPT ZIP, and tap `ChatGPTに送る`.
- The OS delivered `application/zip` directly to `com.openai.chatgpt/.MainActivity`. The normal composer showed `rinbam-chatgpt-20260718-203850.zip`, an empty question field, and an enabled send button. No question was entered and no send action was performed.
- Evidence directory: `artifacts/device-verification/2026-07-18-android-ch13/`.

## iOS current-source verification

- Device: iPhone 12, CoreDevice identifier `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E`.
- Canonical bundle: `com.mibu.codebridge.ios`.
- Current source built with `DEVELOPMENT_TEAM=8R3B5675ZJ -allowProvisioningUpdates`, Apple Development signing, and installed/launched successfully with `devicectl`.
- Appium server `127.0.0.1:4723` responds, but `http://127.0.0.1:42314/remotexpc/tunnels` reports `activeTunnels=0`. Therefore current-source iOS share-sheet, ChatGPT selection, attachment, empty composer, and unsent-state operation remain `NOT VERIFIED`.

## Public Privacy deployment

- Vercel production deployment: `dpl_5YKkCQxcAAmjQ4NxqFHaojuDw2Re`.
- Aliased domain: `https://miyamibu.xyz`.
- `./scripts/verify_public_web_release.sh` passed after deployment: Privacy, account deletion, Asset Links, AASA, paid-plan disclosure, StoreKit/Google Play Billing disclosure, and no stale no-real-billing wording.

## Release boundary

- Current Android AAB is unsigned; current iOS build is Apple Development-signed only.
- Apple Distribution/TestFlight, Google Play upload, store privacy/Data Safety/App Privacy entry, and OpenAI submission remain external account-controlled gates.
- Unknown secret formats cannot be detected with mathematical certainty; the preview warning and explicit confirmation remain required by design.
