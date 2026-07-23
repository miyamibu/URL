# Final Store Submission Checklist

## Date
2026-06-28 historical submission log; current source re-baseline added 2026-07-09.

## Goal
この文書の下部に残る `1.0.11` の表は、2026-06-27/28 の Google Play / App Store 提出時点の履歴ログとして扱う。現在の repo source は Android `1.0.15 (versionCode=17)` / iOS `1.0.15 (build=17)` であり、この履歴ログだけでは次回提出可否を判断しない。

## Current Source Snapshot (2026-07-23 Store Candidate)
- Android source: `versionName = "1.0.15"`, `versionCode = 17`, package `jp.miyamibu.urlalbum`.
- iOS source: `CFBundleShortVersionString = 1.0.15`, `CFBundleVersion = 17`, bundle `com.mibu.codebridge.ios`.
- Current release/ops readiness tracker: `docs/release/release-ops-readiness-2026-07-09.md`.
- Current repo gate is `REPO_GO` when local code, docs, scripts, tests, build, release hygiene, and reviewed-main integration pass. This is a repo-only status, not store/public/OpenAI publication.
- AI-safe export/MCP source contracts are tracked under `docs/ai/`; these local docs do not mean production MCP deployment, production OAuth registration, OpenAI submission, store submission, production secret entry, or store/live recheck is complete.
- The `1.0.11` store submission, public URL, screenshot, signing, and console rows below are not current proof for Android `1.0.15 (17)` / iOS `1.0.15 (17)`.
- Rows below that say `DONE` are historical `1.0.11` evidence unless the row explicitly names the 2026-07-10 repo gate. Do not use them as `LAUNCH_GO` evidence for the current source snapshot without re-running the manual launch checklist.

## Manual Steps Not Done By Codex
| Step | Reason |
|---|---|
| production deploy | External publication action. |
| production MCP/OAuth registration | Requires owner-controlled provider console and secrets. |
| OpenAI submission | Requires deployed endpoint and owner submission. |
| App Store / Play Console submission | Store-console action. |
| production secret投入 | Secrets must stay outside repo/chat. |
| store/live再確認 | External state can change and must be checked at release time. |

## Official Requirements Checked
- Google Play User Data policy: privacy policy URL is required for all apps, must be public/non-geofenced/non-PDF, and Data safety must match the privacy policy.
- Google Play Data safety: every published app must complete the Data safety form; apps with no data collection still complete the form and provide a privacy policy.
- Google Play account deletion: apps that allow account creation must provide in-app and web account deletion.
- Google Play preview assets: feature graphic is 1024 x 500 px; screenshots are required for store listing quality.
- Google Play personal developer accounts created after 2023-11-13 may need 12 opted-in testers for 14 continuous days before production access.
- App Store Connect screenshots: one to ten `.jpeg`, `.jpg`, or `.png` screenshots; accepted sizes depend on device family.
- App Store privacy: App Privacy responses and privacy policy URL are required and must include third-party partner practices.
- Apple account deletion: apps with account creation must let users initiate deletion in app.

## Historical Release Mode Finding (2026-06-28)
Historical `1.0.11` repo snapshot was aligned to cloud-enabled submission at that time. This is not proof that current `1.0.14 (15)` production secrets or external consoles have been rechecked.

- Android release config had `release.shared.tag.cloud.enabled=true` and production Supabase values in local release configuration for that historical submission.
- iOS Release build settings read `ios/Config/URLSaverSecrets.xcconfig` and showed `URLSAVER_SHARED_TAG_CLOUD_ENABLED=true` plus production Supabase/contact-support values for that historical submission.
- Ads, external analytics, and third-party crash reporting remain disabled. Google Play Billing / StoreKit subscriptions are enabled for paid plans.
- Store forms must disclose account sign-in, shared-tag cloud sync/collaboration, invite sync, contact support processing, and account deletion.

The repo-side cloud-enabled alignment is fixed. Google Play and App Store Connect submissions for `1.0.11` are now in review/waiting-for-review. Remaining follow-up risks are Android screenshot refresh if the listing is later replaced, Play App Signing SHA-256 for final App Links, and any reviewer-requested test-account details.

## Google Play Submission Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Package name | DONE | `app/build.gradle.kts`: `applicationId = "jp.miyamibu.urlalbum"` | Keep stable. |
| Version | DONE | `versionCode = 11`, `versionName = "1.0.11"` | Bump only for later releases. |
| Target SDK | DONE | `targetSdk = 35` | Recheck at submission date. |
| App name/listing copy | DONE | `docs/release/store-listing-draft.md` | Paste into Play Console after account setup. |
| Privacy policy source | DONE | `web/invite-link/privacy/index.html` updated for cloud-enabled `1.0.11`. | Deploy and verify final public URL before entry. |
| Privacy policy public URL | DONE_PUBLIC_VERIFIED | `./scripts/verify_public_web_release.sh` passed on 2026-06-29: `https://miyamibu.xyz/privacy/` returned HTTP 200, discloses Standard / Pro subscriptions, Google Play Billing, and StoreKit, and no longer contains stale no-real-billing wording. | Re-run verifier before changing store metadata or release mode. |
| Data safety draft | DONE | `docs/release/privacy-data-safety-draft.md` cloud-sharing rows match the current release mode. | Copy cloud-sharing answers into Play Console. |
| Account deletion URL | DONE | `https://miyamibu.xyz/account-deletion/` returned HTTP 200 on 2026-06-27. | Enter this URL in Play Console. |
| Ads declaration | DONE | Release config sets `ADS_ENABLED=false`; release manifest removes ad components. | Keep ads disabled or revise privacy/listing. |
| Billing declaration | DONE | `app/build.gradle.kts` includes `com.android.billingclient:billing:8.3.0`; iOS has `StoreKitPurchaseService.swift`. | Keep Play Console / App Store Connect IAP metadata aligned with Standard / Pro subscriptions. |
| App icon | DONE | Android mipmap densities exist under `app/src/main/res/mipmap-*`. | Final visual approval still owner decision. |
| Feature graphic | DONE | `artifacts/store-assets/google-play-feature-graphic-1024x500.png`, verified 1024 x 500. | Owner can approve or replace before upload. |
| Phone screenshots | MANUAL_STEP | Existing Android screenshots predate cloud-enabled `1.0.11`; local AVD `urlsaverApi35` exited immediately with no adb device or emulator log on 2026-06-27. | Capture/approve final screenshots from a release-equivalent cloud-enabled build before final store listing update if screenshots are being replaced. |
| Release AAB | DONE | `./gradlew testDebugUnitTest lintDebug bundleRelease` passed on 2026-06-27; signed AAB created at `/Users/mimac/.urlsaver-signing/app-release-1.0.11-11-upload-signed.aab`; uploaded to Play Console production release 9 as `11 (1.0.11)` and submitted for review on 2026-06-27. | Monitor review result. |
| Release signing / upload key | DONE | Signed with `urlsaver-upload-reset-20260526-122511.jks`, alias `urlsaver-upload`; `jarsigner` verified signer `CN=URLSaver Upload, OU=URLSaver, O=Miyamibu`. | Keep keystore and passwords outside repo/chat. |
| Play App Signing SHA-256 | NEEDS_USER_ACTION | `web/invite-link/.well-known/assetlinks.json` has beta/debug fingerprint. | Replace with Play App Signing SHA-256 after enrollment. |
| Android App Links file | PARTIAL | `https://miyamibu.xyz/.well-known/assetlinks.json` returned JSON on 2026-06-27 and includes `jp.miyamibu.urlalbum`; final Play SHA-256 is not known. | Replace/reverify after final Play SHA-256. |
| Content rating | DONE | Play Console production release was accepted into review on 2026-06-27. | Monitor review result. |
| Target audience | DONE | Play Console production release was accepted into review on 2026-06-27. | Monitor review result. |
| Closed testing requirement | NEEDS_USER_ACTION | Official requirement may apply to new personal accounts. | If applicable, run 12 testers for 14 days before production. |
| App record / console listing | DONE | Play Console app record `りんばむ / jp.miyamibu.urlalbum` was accessible on 2026-06-27; production release `11 (1.0.11)` shows `審査中の変更`. | Monitor review result. |

## App Store Submission Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Bundle ID | DONE | `ios/URLSaveriOS.xcodeproj/project.pbxproj`: `com.mibu.codebridge.ios` | Keep stable. |
| Share extension Bundle ID | DONE | Project has `com.mibu.codebridge.ios.share`. | Needs distribution provisioning. |
| Display name | DONE | `ios/URLSaveriOS/Info.plist`: `CFBundleDisplayName = りんばむ` | Keep stable. |
| Version/build | DONE | `CFBundleShortVersionString = 1.0.11`, `CFBundleVersion = 11` in app and share extension Info.plist. | Bump only for later submissions. |
| App icon | DONE | `ios/URLSaveriOS/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` and required iPhone sizes exist. | Final visual approval still owner decision. |
| Privacy manifest | DONE | `ios/URLSaveriOS/PrivacyInfo.xcprivacy` and extension manifest exist. | Recheck if SDKs/data collection change. |
| App Privacy draft | DONE | `docs/release/privacy-data-safety-draft.md`; iOS privacy manifests parsed successfully. | Copy cloud-sharing answers into App Store Connect. |
| Privacy policy URL | DONE_PUBLIC_VERIFIED | `./scripts/verify_public_web_release.sh` passed on 2026-06-29: `https://miyamibu.xyz/privacy/` returned HTTP 200 and matches billing-enabled `1.0.11` privacy wording. | Re-run verifier before changing App Store Connect metadata or release mode. |
| Account deletion | DONE | `https://miyamibu.xyz/account-deletion/` returned HTTP 200 on 2026-06-27. | Enter this URL in App Store Connect review/privacy metadata. |
| Test account | NEEDS_USER_ACTION | Store review may need cloud-sharing sign-in access. | Provide a review test account or review notes that allow account flow verification. |
| Associated Domains entitlement | DONE | `ios/URLSaveriOS/URLSaveriOS.entitlements` has `applinks:miyamibu.xyz`. | Reverify with final Team ID/deployment. |
| AASA file | DONE | `https://miyamibu.xyz/.well-known/apple-app-site-association` returned JSON on 2026-06-27 and includes `8R3B5675ZJ.com.mibu.codebridge.ios`. | Recheck only if Apple Team ID or bundle ID changes. |
| Current iOS release cloud config | DONE | `xcodebuild ... -configuration Release -showBuildSettings` on 2026-06-27 shows `URLSAVER_SHARED_TAG_CLOUD_ENABLED=true` and production Supabase/contact-support values via `ios/Config/URLSaverSecrets.xcconfig`. | Keep store privacy answers aligned with cloud-enabled binary. |
| Archive / distribution signing | DONE | Archive `build/archives/URLSaveriOS-1.0.11-11-20260628-0644.xcarchive` uploaded successfully through Xcode `-exportArchive` destination `upload` on 2026-06-28 after adding the app icon asset catalog to the app target. | Monitor App Review result. |
| App Store screenshots | PARTIAL | A cloud-enabled `1.0.11` iOS simulator home screenshot was captured during review, but the UI review artifact was intentionally not retained in git. Full final store set still needs owner approval if replacing screenshots. | Capture/approve remaining screenshots if App Store listing is being changed. |
| Age rating | DONE | App Store Connect accepted the `1.0.11 (11)` submission on 2026-06-28. | Monitor App Review result. |
| App Store Connect app record | DONE | App record `りんばむ`, Apple ID `6771251450`; iOS `1.0.11 (11)` submission ID `428bdf26-5233-47e2-89a2-c3925f32994b` is `審査待ち` as of 2026-06-28 06:53 JST. | Monitor App Review result. |

## Common Assets Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Store listing draft | DONE | `docs/release/store-listing-draft.md` updated with input sheet. | Review wording before paste. |
| Privacy/Data safety draft | DONE | `docs/release/privacy-data-safety-draft.md` updated with evidence matrix. | Select release mode and copy matching answers. |
| Screenshot plan | PARTIAL | Existing Android screenshot candidates are under `artifacts/store-assets/screenshots/2026-05-13/android/`; the temporary iOS cloud-enabled `1.0.11` home screenshot was not retained in git. | Capture/approve final Android/iOS store-size screenshots if replacing screenshots. |
| Feature graphic | DONE | `artifacts/store-assets/google-play-feature-graphic-1024x500.png` | Owner approve/replace. |
| App Links / Universal Links placeholders | DONE | `docs/release/link-and-signing-placeholders.md` | Replace only issued external IDs after account setup. |
| Public web privacy page | DONE_PUBLIC_VERIFIED | `./scripts/verify_public_web_release.sh` passed on 2026-06-29; live `https://miyamibu.xyz/privacy/` matches billing-enabled `1.0.11` wording. | Re-run verifier after any privacy or billing copy change. |
| Public account deletion page | DONE | Source added and `https://miyamibu.xyz/account-deletion/` returned HTTP 200 on 2026-06-27. | Use this URL in store consoles. |
| Android screenshot evidence | MANUAL_STEP | Older local-only screenshot evidence exists but does not prove cloud-enabled `1.0.11`; local AVD startup failed before adb registration. | Capture later on a working emulator/device if replacing Play screenshots. |
| iOS screenshot evidence | PARTIAL | Cloud-enabled `1.0.11` simulator screenshot was captured during review, but the temporary artifact was intentionally left out of git. | Capture remaining store-size screenshots if replacing App Store screenshots. |

## External / User Action Checklist

| Item | Status | Why | Next action |
|---|---|---|---|
| Google Play developer account/app record | DONE | Play Console developer account `miyamibu` and app record `jp.miyamibu.urlalbum` were accessible on 2026-06-27; production release `11 (1.0.11)` is in review. | Monitor review result. |
| Play App Signing | NEEDS_USER_ACTION | App Links need final SHA-256. | Enroll and provide Play App Signing SHA-256. |
| Release keystore/upload key | DONE | Existing upload keystore under `/Users/mimac/.urlsaver-signing/` produced verified signed AAB. | Keep secret material outside repo/chat. |
| Apple Developer Program / Team | DONE | Updated Apple Developer agreement was accepted by the account holder; App Store Connect accepted the `1.0.11 (11)` submission under Team ID `8R3B5675ZJ`. | Monitor App Review result. |
| App Store Connect record/SKU | DONE | App Store Connect shows `りんばむ` app record, Apple ID `6771251450`; submission ID `428bdf26-5233-47e2-89a2-c3925f32994b` is `審査待ち`. | Monitor App Review result. |
| Production Supabase | HISTORICAL_DONE_FOR_1.0.11 | Historical Android/iOS local release settings contained production Supabase values; values are intentionally not printed in this doc. Current `1.0.14 (15)` launch still requires owner-controlled secret entry and recheck. | Follow `docs/release/production-secrets-and-flags.md` before marking `LAUNCH_GO`. |
| Public HTTPS deployment | DONE_PUBLIC_VERIFIED | Vercel serves `https://miyamibu.xyz`; account-deletion, privacy, and `.well-known` checks pass in `./scripts/verify_public_web_release.sh` on 2026-06-29. | Re-run public URL verification after deployment or release-copy changes. |
| Final release screenshots | PARTIAL | iOS cloud-enabled screenshot captured; Android AVD capture blocked internally. | Decide whether existing Play screenshots can remain or capture later on a working emulator/device. |
| Chrome console automation | DONE | Chrome launched and Play Console/App Store Connect were reachable. Play production release `11 (1.0.11)` was submitted for review on 2026-06-27. App Store Connect `1.0.11 (11)` was submitted on 2026-06-28. | Monitor both store review results. |

## Validation Summary

| Check | Status | Result |
|---|---|---|
| Android unit tests | DONE | `./gradlew testDebugUnitTest` passed on 2026-05-13. |
| Android lint | DONE | `./gradlew lintDebug` passed on 2026-05-13. |
| Android debug build | DONE | `./gradlew assembleDebug` passed on 2026-05-11. |
| Android release AAB | DONE | `./gradlew testDebugUnitTest lintDebug bundleRelease` passed on 2026-06-27; `/Users/mimac/.urlsaver-signing/app-release-1.0.11-11-upload-signed.aab` verified with `jarsigner`; Play Console production release 9 contains `11 (1.0.11)` and is in review. |
| Android final build command | DONE | `./gradlew assembleDebug testDebugUnitTest lintDebug bundleRelease assembleRelease` passed on 2026-05-11. |
| Android release artifact scan | DONE | Release BuildConfig/manifest inspected on 2026-05-11; signed AAB certificate and version were verified on 2026-06-27. |
| Android screenshot set | MANUAL_STEP | Older release-equivalent screenshots with demo data are saved under `artifacts/store-assets/screenshots/2026-05-13/android/`, but they do not prove cloud-enabled `1.0.11`; AVD launch failed on 2026-06-27. |
| iOS simulator build | DONE | Debug simulator build installed and launched on `URLSaverStoreShot-iOS26-5` on 2026-06-27 for cloud-enabled `1.0.11` screenshot capture. |
| iOS tests | DONE | `xcodebuild ... test` passed on 2026-05-11. |
| iOS release settings | HISTORICAL_DONE_FOR_1.0.11 | `xcodebuild ... -configuration Release -showBuildSettings` on 2026-06-27 reported `URLSAVER_SHARED_TAG_CLOUD_ENABLED=true` and production Supabase/contact-support values for the historical `1.0.11` submission. Current `1.0.14 (15)` launch requires a fresh owner recheck. |
| iOS release-style build | DONE | `xcodebuild ... -configuration Release -destination generic/platform=iOS CODE_SIGNING_ALLOWED=NO build` passed on 2026-06-27. |
| iOS archive/export/upload | DONE | Archive `build/archives/URLSaveriOS-1.0.11-11-20260628-0644.xcarchive` uploaded successfully on 2026-06-28; App Store Connect submission `428bdf26-5233-47e2-89a2-c3925f32994b` is `審査待ち`. |
| iOS screenshot | PARTIAL | A simulator screenshot was captured from a build with `SharedTagCloudEnabled=true`; the temporary artifact was intentionally left out of git. Full store screenshot set still needs approval if replacing screenshots. |
| Web/static/store files | DONE | Privacy manifests parsed, feature graphic verified 1024 x 500, `.well-known` JSON parsed, and live privacy/account-deletion pages verified on 2026-06-27. |
| Final release-mode scan | DONE | Release config scan is aligned to cloud-enabled `1.0.11`; privacy/store docs disclose cloud sync, contact support, account deletion, and paid subscriptions. |
| Public URL live verification | DONE_PUBLIC_VERIFIED | 2026-06-29: `./scripts/verify_public_web_release.sh` passed; `privacy/` and `account-deletion/` return HTTP 200, `assetlinks.json` and AASA parse as JSON and include the expected package/bundle, and privacy no longer contains stale no-real-billing wording. |

## Repo-Internal Unresolved Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Release config contradiction | DONE | Android and iOS release settings are now treated as cloud-enabled; store drafts were updated for cloud-enabled `1.0.11`. | Recheck generated artifacts before final upload. |
| Store text contradiction | DONE | `docs/release/store-listing-draft.md`, `privacy-data-safety-draft.md`, web source, and live public pages describe cloud-enabled `1.0.11`. | None. |
| Complete final screenshot set | PARTIAL | Existing screenshot inventory may not match cloud-enabled `1.0.11`. | Capture/approve final Android/iOS screenshots from release-equivalent builds. |
| Android signed installable release APK | NEEDS_USER_ACTION | `app/build/outputs/apk/release/app-release-unsigned.apk` is unsigned; release deliverable is AAB. | Sign AAB with upload key; produce signed APK only if needed for side validation. |
| Physical device final verification | NEEDS_USER_ACTION | Not run because physical-device install requires explicit approval. | Approve device install if physical validation is required before submission. |

## Submission Readiness Verdict
Submitted to both stores.

Google Play production release `11 (1.0.11)` is in review. App Store Connect iOS `1.0.11 (11)` is `審査待ち` under submission ID `428bdf26-5233-47e2-89a2-c3925f32994b`. Public account-deletion, privacy, and `.well-known` verification pass as of 2026-06-29. Remaining risks are reviewer feedback, Play 16 KB memory page size compliance warning for future enforcement, Play App Signing SHA-256 replacement for final App Links, and any reviewer-requested test-account details.
