# Final Store Submission Checklist

## Date
2026-05-13

## Goal
URL Saver を Google Play / App Store 提出へ近づけるため、repo内で確認・作成できる提出素材、設定、検証結果、外部待ちを一箇所にまとめる。

## Official Requirements Checked
- Google Play User Data policy: privacy policy URL is required for all apps, must be public/non-geofenced/non-PDF, and Data safety must match the privacy policy.
- Google Play Data safety: every published app must complete the Data safety form; apps with no data collection still complete the form and provide a privacy policy.
- Google Play account deletion: apps that allow account creation must provide in-app and web account deletion.
- Google Play preview assets: feature graphic is 1024 x 500 px; screenshots are required for store listing quality.
- Google Play personal developer accounts created after 2023-11-13 may need 12 opted-in testers for 14 continuous days before production access.
- App Store Connect screenshots: one to ten `.jpeg`, `.jpg`, or `.png` screenshots; accepted sizes depend on device family.
- App Store privacy: App Privacy responses and privacy policy URL are required and must include third-party partner practices.
- Apple account deletion: apps with account creation must let users initiate deletion in app.

## Current Release Mode Finding
Current repo is aligned to local-only v1.0:

- Android Release `BuildConfig` has `ADS_ENABLED=false`, `SHARED_TAG_CLOUD_ENABLED=false`, `SUPABASE_URL=""`, and `SUPABASE_ANON_KEY=""`.
- iOS Debug/Release build settings have `URLSAVER_SHARED_TAG_CLOUD_ENABLED=false`, `URLSAVER_SUPABASE_URL=""`, and `URLSAVER_SUPABASE_ANON_KEY=""`.
- Shared-tag cloud/profile/account UI is hidden when cloud is not configured.
- Android local-only export UI hides the shared-tag-only quick selection when `SHARED_TAG_CLOUD_ENABLED=false`.
- Cloud sharing, login/account creation, invite sync, and production Supabase are v1.1-or-later scope.

The repo-side local-only alignment is fixed. Store submission is still blocked by external account/signing/screenshots/public-console work.

## Google Play Submission Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Package name | DONE | `app/build.gradle.kts`: `applicationId = "jp.mimac.urlsaver"` | Keep stable. |
| Version | DONE | `versionCode = 1`, `versionName = "1.0"` | Bump only for later releases. |
| Target SDK | DONE | `targetSdk = 35` | Recheck at submission date. |
| App name/listing copy | DONE | `docs/release/store-listing-draft.md` | Paste into Play Console after account setup. |
| Privacy policy source | DONE | `web/invite-link/privacy/index.html` | Verify final public URL before entry. |
| Privacy policy public URL | DONE | `https://miyamibu.xyz/privacy/` returned HTTP 200 on 2026-05-11 and served the local-only privacy page. | Use this URL in Play Console. |
| Data safety draft | DONE | `docs/release/privacy-data-safety-draft.md` local-only rows match Release BuildConfig. | Copy local-only answers into Play Console. |
| Account deletion URL | NOT_APPLICABLE | Local-only v1.0 exposes no account creation/login. | Revisit for v1.1 cloud/account release. |
| Ads declaration | DONE | Release config sets `ADS_ENABLED=false`; release manifest removes ad components. | Keep ads disabled or revise privacy/listing. |
| Billing declaration | DONE | No billing dependency found in active Gradle dependencies. | Keep no IAP/subscriptions or add billing setup later. |
| App icon | DONE | Android mipmap densities exist under `app/src/main/res/mipmap-*`. | Final visual approval still owner decision. |
| Feature graphic | DONE | `artifacts/store-assets/google-play-feature-graphic-1024x500.png`, verified 1024 x 500. | Owner can approve or replace before upload. |
| Phone screenshots | DONE | Final Android local-only screenshot candidates are recorded in `artifacts/store-assets/screenshots/2026-05-13/android/`; manifest verdict is `READY_FOR_STORE_UPLOAD`. | Owner may still replace art before upload. |
| Release AAB | DONE | `./gradlew assembleDebug testDebugUnitTest lintDebug bundleRelease assembleRelease` passed; output `app/build/outputs/bundle/release/app-release.aab`. | Use only after final signing/account setup. |
| Release signing / upload key | NEEDS_USER_ACTION | No release keystore found in repo inventory. | Create/configure upload key and Play App Signing in Play Console. |
| Play App Signing SHA-256 | NEEDS_USER_ACTION | `web/invite-link/.well-known/assetlinks.json` has beta/debug fingerprint. | Replace with Play App Signing SHA-256 after enrollment. |
| Android App Links file | UNVERIFIED | `https://miyamibu.xyz/.well-known/assetlinks.json` returned JSON on 2026-05-11, but final Play SHA-256 is not known. | Replace/reverify after final Play SHA-256. |
| Content rating | NEEDS_USER_ACTION | Draft age notes in `docs/release/store-listing-draft.md`. | Complete Play Console questionnaire. |
| Target audience | NEEDS_USER_ACTION | App is productivity, not Made for Kids. | Confirm in Play Console. |
| Closed testing requirement | NEEDS_USER_ACTION | Official requirement may apply to new personal accounts. | If applicable, run 12 testers for 14 days before production. |
| App record / console listing | BLOCKED_EXTERNAL | Requires Play Console access. | User creates/opens Play app record. |

## App Store Submission Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Bundle ID | DONE | `ios/URLSaveriOS.xcodeproj/project.pbxproj`: `jp.mimac.urlsaver.ios` | Keep stable. |
| Share extension Bundle ID | DONE | Project has `jp.mimac.urlsaver.ios.share`. | Needs distribution provisioning. |
| Display name | DONE | `ios/URLSaveriOS/Info.plist`: `CFBundleDisplayName = URL Saver` | Keep stable. |
| Version/build | DONE | `CFBundleShortVersionString = 1.0`, `CFBundleVersion = 1` | Bump for later submissions if needed. |
| App icon | DONE | `ios/URLSaveriOS/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` and required iPhone sizes exist. | Final visual approval still owner decision. |
| Privacy manifest | DONE | `ios/URLSaveriOS/PrivacyInfo.xcprivacy` and extension manifest exist. | Recheck if SDKs/data collection change. |
| App Privacy draft | DONE | `docs/release/privacy-data-safety-draft.md`; iOS privacy manifests parsed successfully. | Copy local-only answers into App Store Connect. |
| Privacy policy URL | DONE | `https://miyamibu.xyz/privacy/` returned HTTP 200 on 2026-05-11 and served the local-only privacy page. | Use this URL in App Store Connect. |
| Account deletion | NOT_APPLICABLE | Local-only v1.0 exposes no account creation/login; iOS Release cloud config is false/empty. | Revisit for v1.1 cloud/account release. |
| Test account | NOT_APPLICABLE | No login/account feature is exposed in local-only v1.0. | Revisit for v1.1 cloud/account release. |
| Associated Domains entitlement | DONE | `ios/URLSaveriOS/URLSaveriOS.entitlements` has `applinks:miyamibu.xyz`. | Reverify with final Team ID/deployment. |
| AASA file | UNVERIFIED | `https://miyamibu.xyz/.well-known/apple-app-site-association` returned JSON on 2026-05-11 and currently has `8R3B5675ZJ.jp.mimac.urlsaver.ios`. | Replace/verify after final paid Team ID is confirmed. |
| Current iOS release cloud config | DONE | `ios/URLSaveriOS.xcodeproj/project.pbxproj`: Release app target has `URLSAVER_SHARED_TAG_CLOUD_ENABLED=false`, empty Supabase URL/key. Normal `xcodebuild -showBuildSettings` stays local-only. | Keep unchanged for local-only v1.0. Do not pass dev Supabase `ios/Config/URLSaverSecrets.xcconfig` with `-xcconfig`; use no xcconfig or `ios/Config/URLSaverSecrets.local-only.xcconfig`. |
| Archive / distribution signing | NEEDS_USER_ACTION | Device debug signing works; App Store distribution signing not confirmed. | Apple Developer Program, distribution cert/profile, App Store Connect record required. |
| App Store screenshots | BLOCKED_EXTERNAL | iOS Simulator SDK is 26.5 but installed runtimes are 26.4 / 26.4.1; existing signed Debug-iphoneos app contains dev shared-tag/Supabase values and is rejected for store screenshots; physical iPhone app inspection was blocked while the device was locked. | Install matching iOS 26.5 simulator runtime or provide an unlocked device with a local-only signed build path. |
| Age rating | NEEDS_USER_ACTION | Draft age notes exist. | Complete App Store Connect age questionnaire. |
| App Store Connect app record | BLOCKED_EXTERNAL | Requires Apple account/App Store Connect. | User creates/opens app record and SKU. |

## Common Assets Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Store listing draft | DONE | `docs/release/store-listing-draft.md` updated with input sheet. | Review wording before paste. |
| Privacy/Data safety draft | DONE | `docs/release/privacy-data-safety-draft.md` updated with evidence matrix. | Select release mode and copy matching answers. |
| Screenshot plan | PARTIAL | Android final screenshot candidates are complete under `artifacts/store-assets/screenshots/2026-05-13/android/`; iOS remains blocked in the same manifest. | Resolve iOS runtime/signing/device blocker before App Store upload. |
| Feature graphic | DONE | `artifacts/store-assets/google-play-feature-graphic-1024x500.png` | Owner approve/replace. |
| App Links / Universal Links placeholders | DONE | `docs/release/link-and-signing-placeholders.md` | Replace only issued external IDs after account setup. |
| Public web privacy page | DONE | Source is updated at `web/invite-link/privacy/index.html`; `https://miyamibu.xyz/privacy/` returned HTTP 200 on 2026-05-11. | Recheck if privacy copy changes before submission. |
| Public account deletion page | NOT_APPLICABLE | Source exists for future cloud release, but local-only v1.0 has no account creation. | Deploy only if account creation ships. |
| Android local-only screenshot evidence | DONE | `artifacts/store-assets/screenshots/android/android-emulator-debug-localonly-home.png`, 1080 x 2400, forced local-only debug BuildConfig. | Use only as evidence, not final upload. |
| iOS local-only screenshot evidence | DONE | `artifacts/store-assets/screenshots/ios/ios-iphone17-home-local-only.png`, 1206 x 2622, Release simulator. | Use only as evidence until final screenshot set is approved. |

## External / User Action Checklist

| Item | Status | Why | Next action |
|---|---|---|---|
| Google Play developer account/app record | BLOCKED_EXTERNAL | Requires account and console access. | Create app record for `jp.mimac.urlsaver`. |
| Play App Signing | NEEDS_USER_ACTION | App Links need final SHA-256. | Enroll and provide Play App Signing SHA-256. |
| Release keystore/upload key | NEEDS_USER_ACTION | No release keystore found in repo. | Create upload key and store securely outside repo. |
| Apple Developer Program / Team | NEEDS_USER_ACTION | Debug team exists, distribution team not confirmed. | Confirm paid team and Team ID. |
| App Store Connect record/SKU | BLOCKED_EXTERNAL | Requires App Store Connect. | Create record for `jp.mimac.urlsaver.ios`. |
| Production Supabase | NOT_APPLICABLE | local-only v1.0 has Android/iOS cloud disabled and empty Supabase values. | Provide production Supabase only for v1.1 cloud release. |
| Public HTTPS deployment | DONE | `miyamibu.xyz` is assigned to the Vercel `invite-link` deployment and live checks passed on 2026-05-11 for `/privacy/`, both `.well-known` JSON files, and `/invite/placeholder`. | Recheck after any web copy or signing placeholder change. |
| Final release screenshots | PARTIAL | Android is `READY_FOR_STORE_UPLOAD`; iOS is `BLOCKED` in `artifacts/store-assets/screenshots/2026-05-13/manifest.json`. | Resolve iOS capture blocker. |

## Validation Summary

| Check | Status | Result |
|---|---|---|
| Android unit tests | DONE | `./gradlew testDebugUnitTest` passed on 2026-05-13. |
| Android lint | DONE | `./gradlew lintDebug` passed on 2026-05-13. |
| Android debug build | DONE | `./gradlew assembleDebug` passed on 2026-05-11. |
| Android release AAB | DONE | `./gradlew bundleRelease` passed on 2026-05-13. |
| Android final build command | DONE | `./gradlew assembleDebug testDebugUnitTest lintDebug bundleRelease assembleRelease` passed on 2026-05-11. |
| Android release artifact scan | DONE | Release BuildConfig/manifest inspected on 2026-05-11. |
| Android local-only screenshot set | DONE | Release-equivalent Android screenshots with demo data are saved under `artifacts/store-assets/screenshots/2026-05-13/android/`; export screen no longer shows the shared-tag-only quick selection. |
| iOS simulator build | BLOCKED_EXTERNAL | On 2026-05-13, `xcodebuild ... -configuration Release -destination 'platform=iOS Simulator,name=iPhone 17' -showBuildSettings` failed because Xcode requires iOS 26.5 while installed simulator runtimes are iOS 26.4 / 26.4.1. |
| iOS tests | DONE | `xcodebuild ... test` passed on 2026-05-11. |
| iOS release settings | DONE | `xcodebuild ... -configuration Release -showBuildSettings` reported `URLSAVER_SHARED_TAG_CLOUD_ENABLED=false`; project file has empty Supabase URL/key. Explicitly passing dev `ios/Config/URLSaverSecrets.xcconfig` overrides this and is not allowed for local-only v1.0. |
| iOS release-style build | DONE | `xcodebuild ... -configuration Release -destination generic/platform=iOS CODE_SIGNING_ALLOWED=NO build` passed on 2026-05-11. |
| iOS local-only screenshot | DONE | Release simulator screenshot saved at `artifacts/store-assets/screenshots/ios/ios-iphone17-home-local-only.png`, 1206 x 2622. |
| Web/static/store files | DONE | Privacy HTML local-only text asserted; assetlinks/AASA JSON parsed; privacy manifests parsed; feature graphic verified 1024 x 500. |
| Final release-mode scan | DONE | Release config scan found no enabled cloud flag, local HTTP Supabase URL, or dev anon key in submitted app config paths. |
| Public URL live verification | DONE | `curl -I https://miyamibu.xyz/privacy/` returned HTTP 200; both `.well-known` files fetched from `miyamibu.xyz` and parsed as JSON on 2026-05-11. |

## Repo-Internal Unresolved Checklist

| Item | Status | Evidence | Next action |
|---|---|---|---|
| Release config contradiction | DONE | Android Release BuildConfig false/empty; iOS Release showBuildSettings false; final risk scan clean for submitted config paths. | None. |
| Store text contradiction | DONE | `docs/release/store-listing-draft.md`, `privacy-data-safety-draft.md`, `docs/account-deletion.md`, and privacy HTML describe local-only v1.0. | None. |
| Complete final screenshot set | PARTIAL | Android complete set is ready; iOS complete set is blocked by simulator runtime mismatch and no acceptable physical local-only signed build capture. | Resolve iOS runtime/signing/device blocker, then capture six iOS screenshots. |
| Android signed installable release APK | NEEDS_USER_ACTION | `app/build/outputs/apk/release/app-release-unsigned.apk` is unsigned; release deliverable is AAB. | Configure upload key / Play signing, then produce installable release if needed. |
| Physical device final verification | NEEDS_USER_ACTION | Not run because physical-device install requires explicit approval. | Approve device install if physical validation is required before submission. |

## Submission Readiness Verdict
Not ready to submit yet.

Android local-only screenshots and the export UI alignment are ready. Remaining blockers are iOS final screenshots, external store account setup, release signing / Apple distribution provisioning, and final App Links / Universal Links values from Play/App Store account setup.
