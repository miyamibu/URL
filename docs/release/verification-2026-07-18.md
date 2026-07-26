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

## iOS current-source verification (2026-07-18 initial run)

- Device: iPhone 12, CoreDevice identifier `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E`.
- Canonical bundle: `com.mibu.codebridge.ios`.
- Physical-device backend: Appium + WebDriverAgent, Appium UDID `00008101-00066D96340A001E`, server `127.0.0.1:4723`.
- Current source built with `DEVELOPMENT_TEAM=8R3B5675ZJ -allowProvisioningUpdates`, Apple Development signing, and installed/launched successfully with `devicectl`.
- Verified on the current source: home → export → `ChatGPTに聞く` → local tag selection → preview with `対象 3件` / `除外 0件` → redaction/disclosure review → explicit confirmation → ZIP creation (`3件のChatGPT用ZIPを作成しました`) → iOS share-sheet display.
- Evidence directory: `artifacts/device-verification/2026-07-18-ios-ch13/` (`07-home*`, `08-preview*`, `09-after-zip*`, `10-share-sheet*`, and `11-after-share-close-source.xml`).
- `xcrun devicectl device info apps` showed only `com.mibu.codebridge.ios` among the relevant apps; `com.openai.chatgpt` is not installed on this iPhone. Therefore selecting ChatGPT in the share sheet, attaching the ZIP in ChatGPT, confirming an empty question field, and the intentionally unsent state remain `NOT VERIFIED on physical iPhone` due to the external app not being installed.

## iOS current-source retest (2026-07-19)

- The user-provided ChatGPT installation was present in the iOS share-target list as `ChatGPT`; the actual app bundle reported by Appium was `com.openai.chat`.
- After fixing the temporary-file cleanup race, the current source was rebuilt and signed with Apple Development, then overwrite-installed on the same physical iPhone without clearing app data.
- Appium/WDA verified: `ChatGPTに聞く` → local tag `統合確認-20260710` → `対象 3件` / `除外 0件` → explicit disclosure confirmation → ZIP creation → iOS share sheet → `ChatGPT` selection.
- ChatGPT opened its normal new-chat composer with a visible `ZIP` attachment card named `Rinbam Chatgpt 20260719 012406`; the question field remained empty and no send action was performed.
- Evidence directory: `artifacts/device-verification/2026-07-19-ios-ch13/` (`02-share-sheet*` and `03-chatgpt-composer*`).

## Public Privacy deployment

- Vercel production deployment: `dpl_5YKkCQxcAAmjQ4NxqFHaojuDw2Re`.
- Aliased domain: `https://miyamibu.xyz`.
- `./scripts/verify_public_web_release.sh` passed after deployment: Privacy, account deletion, Asset Links, AASA, paid-plan disclosure, StoreKit/Google Play Billing disclosure, and no stale no-real-billing wording.

## Release boundary

- Current Android AAB is unsigned; current iOS build is Apple Development-signed only.
- Apple Distribution/TestFlight, Google Play upload, store privacy/Data Safety/App Privacy entry, and OpenAI submission remain external account-controlled gates.
- Unknown secret formats cannot be detected with mathematical certainty; the preview warning and explicit confirmation remain required by design.
