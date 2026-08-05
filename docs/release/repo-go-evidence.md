# REPO_GO Evidence

Final status: REPO_GO (Apple 1.0.17 public / Google Play 1.0.16 fully public / Android 1.0.17 full-production review submitted)

## Current source snapshot (manifest-backed)

- Android: `jp.miyamibu.urlalbum`, `versionName=1.0.17`, `versionCode=21`
- iOS: `com.mibu.codebridge.ios`, `shortVersion=1.0.17`, `build=19`; share extension `com.mibu.codebridge.ios.share`
- Supabase migration head: `20260804090000_default_all_users_to_pro.sql`
- Machine-readable source: `docs/release/release-manifest.json`

> Dated provider, store, and device captures below are historical evidence unless they are explicitly re-run for the current source. They must not be treated as current proof solely because they appear in this document.

## Current working-tree status: REPO_GO (external release gates remain separate)

The historical evidence below is retained. The current release candidate baseline is Android `versionName=1.0.17` / `versionCode=21` and iOS `CFBundleShortVersionString=1.0.17` / `CFBundleVersion=19`. Android v21 removes unused native code, embeds R8 mapping metadata, and is submitted to Google Play for a 100% production rollout. Android v20 and iOS 1.0.17 are publicly available. OpenAI submission and production secret entry remain separate gates.

## 2026-08-05 Android v21 release-metadata hardening

| Surface | Confirmed evidence |
|---|---|
| Android source | Bumped canonical `jp.miyamibu.urlalbum` to `versionName=1.0.17`, `versionCode=21`, retaining target SDK 36. Release R8 is enabled and DataStore is updated to `1.1.7`, which contains its corrected consumer ProGuard rules. |
| R8 mapping | `bundleRelease` embeds `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` in the AAB. The standalone local mapping is also generated under `app/build/outputs/mapping/release/mapping.txt`. |
| Native code | The app uses DataStore's single-process coordinator only. Its unused, stripped MultiProcess native counter is excluded from packaging; the v21 AAB contains no `.so` libraries, so native debug symbols are not applicable. |
| Automated guard | `scripts/verify_android_release_artifacts.sh` fails when mapping is absent, or when native libraries exist without embedded debug-symbol metadata. Cross-platform CI builds the release bundle and runs this guard. |
| Local validation | JDK 21 `testDebugUnitTest`, `lintDebug`, and `bundleRelease` passed. The release-artifact guard reports R8 mapping present and no native libraries. |
| Signed artifact | `/Users/mimac/.urlsaver-signing/app-release-1.0.17-21-play-upload-signed-20260805.aab`; SHA-256 `e88c8e978bea63b8d811c12569fb8e6b28c62111da4bca39c500705ccc6f9605`. `jarsigner -verify` passed, its upload-certificate SHA-256 matches the Play-accepted v20 artifact, and Bundletool 1.18.3 validated package `jp.miyamibu.urlalbum`, version `1.0.17 (21)`, and target SDK 36. |
| Google Play | Play accepted v21 for the production track. The artifact row shows the attached `ReTrace` mapping file and no native debug-symbol warning. The release is configured for 100% / all eligible countries, has zero supported-device loss, and the publishing overview shows `審査中の変更` / `完全公開を開始`. It is submitted, not yet publicly live. |

## 2026-08-05 Android v20 full-production submission and maintenance

| Surface | Confirmed evidence |
|---|---|
| Android source | Bumped canonical `jp.miyamibu.urlalbum` to `versionName=1.0.16`, `versionCode=20`, retaining target SDK 36. This release contains the streaming storage-capacity accounting fix merged as `8eba9294`. |
| Local validation | JDK 21 `testDebugUnitTest`, `lintDebug`, and `bundleRelease` passed. The signed AAB was validated with Bundletool and reports package `jp.miyamibu.urlalbum`, version `1.0.16 (20)`, and target SDK 36. |
| Signed artifact | `/Users/mimac/.urlsaver-signing/app-release-1.0.16-20-play-upload-signed-20260805.aab`; SHA-256 `e25b2c32b6514076130a151c0a558ea800693cba940ee82ceca77b5eeb205238`. Its upload-certificate SHA-256 exactly matches the accepted versionCode 19 artifact. |
| Google Play | Existing `1.0.15 (19)` rollout was expanded from 10% to 100%. `1.0.16 (20)` was uploaded to production, configured for 100% / all eligible countries, passed review, and is now the publicly active production release. |
| Web Admin | `postcss` was updated to `8.5.25`; lockfile resolution also updated vulnerable `brace-expansion` instances. `npm audit` reports 0 vulnerabilities, and typecheck, lint, 19 tests, and production build pass. |
| GitHub | Removed the obsolete required status context `verify`. Updated checkout, Java, Gradle, Node, Python, Deno, and Supabase setup actions to current Node 24-compatible major versions; `actionlint` passes. |

## 2026-08-05 release-integration revalidation

| Surface | Confirmed evidence |
|---|---|
| Repository cleanup | Removed the unneeded destructive migration `20260730003000_delete_ios_owner_test_accounts.sql`. Updated the release-manifest head test and made Launch Standard limit tests explicit after the default entitlement changed to Pro. |
| Android local | Java 21 `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleRelease` passed. Canonical package remains `jp.miyamibu.urlalbum`, versionCode `19`, target SDK `36`. |
| Supabase local / production | Fresh local migration replay, public/private schema lint, and pgTAP passed: 18 files / 112 tests. The obsolete `20260730003000` production history entry was marked reverted without changing application data, then `20260731090000`, `20260731100000`, and `20260804090000` were applied to the linked production project. Local and remote migration histories match through `20260804090000`; linked public/private lint has no errors and only two pre-existing unused-parameter warnings in `consume_rinbam_mcp_rate_limit`. |
| Web / contracts | Web Admin 19 tests, typecheck, lint, and production build passed. Python resolver/release tests 31/31, mobile UI contract, MCP contract, release manifest, and release hygiene passed. |
| Pixel 9a | Serial `55211JEBF16639`. A validated pre-install backup was saved, canonical debug build 19 was overwrite-installed and launched without clearing app data, and the database retained `ACTIVE=121` before and after installation. The device returned to another foreground app, so this pass does not claim current-screen UI operation proof. |
| iPhone 12 | UDID `00008101-00066D96340A001E`. A fresh signed `Debug-iphoneos` build for `com.mibu.codebridge.ios` was built, overwrite-installed, and launched without resetting app data. Appium/WDA UI proof is `NOT_VERIFIED`: RemoteXPC tunnel startup requires administrator authorization and WDA timed out while enabling automation mode. |
| App Store Connect | Version `1.0.17` build `19` is approved as `配信準備完了`, with automatic release selected for 2026-08-05 19:00 JST or later. No additional release button is available. |
| Google Play | Production version `1.0.15` versionCode `19` is active and has been publicly available to 10% of users since 2026-07-30 00:48. The rollout percentage was not changed. |
| External state | The three Supabase production migrations listed above were applied and current Store states were inspected. No Vercel deploy, Store upload/submission, staged rollout change, OpenAI submission, or production-secret change was performed during this revalidation. |

## 2026-07-30 Android API 36 production decision

| Surface | Confirmed evidence |
|---|---|
| Android source | Canonical package `jp.miyamibu.urlalbum`; `versionName=1.0.15`, `versionCode=19`, `compileSdk=36`, `targetSdk=36`; AGP 8.10.1, Gradle 8.11.1, and JDK 21. |
| Local validation | `testDebugUnitTest`, `lintDebug`, `bundleRelease`, mobile UI contract, release hygiene, MCP contract, and `git diff --check` passed on 2026-07-30. API 36 emulator evidence is recorded under `artifacts/ui-review/2026-07-29/api36-targetsdk36/`. |
| Signed artifact | `/Users/mimac/.urlsaver-signing/app-release-1.0.15-19-target36-upload-signed-20260729.aab`; SHA-256 `f3f9c7b101f945e8013d00b9d534d5a85e4db93a08fae0ff45f5079d2cea1657`; `jarsigner -verify` and Bundletool validation passed. Signing material remains outside the repository. |
| Google Play internal track | Google Play accepted versionCode 19 / versionName 1.0.15 / target SDK 36. Internal release `1.0.15 (19) target API 36 内部確認` was published on 2026-07-29 at 23:52 JST to the selected two-person tester list. |
| Play validation | Two non-blocking diagnostics remain: no deobfuscation file and no native debug symbols. Supported-device loss is zero. |
| Physical Android | The release owner explicitly approved production publication without completing the physical-device upgrade/data-preservation test. This remains `NOT_VERIFIED`, not a pass. |
| Production submission | Release owner approved public publication on 2026-07-30. Google Play accepted the production change for versionCode 19 / target SDK 36 with a 10% staged rollout. The Public overview shows `審査中の変更`; the automated quick check was still running with an estimated 14 minutes remaining, after which Google review begins. This is submitted, not yet publicly live. |

Current proof boundary:

- Android/iOS manual-handoff implementation and automated tests prove local-tag selection, eligible-only preview/ZIP, preview/archive parity, zero-result rejection, filename/manifest contracts, known-pattern redaction, unknown-secret warning/confirmation, and no question/API/OAuth path.
- The existing physical iPhone and Android handoff records below are historical evidence for earlier source snapshots; Appium/WDA and Android physical operation were not re-run for the current `main` snapshot in this pass.
- Android Release build succeeds; the generated repository AAB is unsigned by design, while the separately signed v21 upload AAB was accepted by Google Play with embedded R8 mapping and submitted for a 100% production rollout. Public users remain on v20 until Google approves v21. The iOS Distribution archive/export/upload succeeded for build 19, and iOS 1.0.17 is publicly available.
- `web/invite-link` was deployed to Vercel production deployment `dpl_GeSsSnoG2tUyNfkuAtgaHqtQrmBd` on 2026-07-26 and aliased to `https://miyamibu.xyz`; `scripts/verify_public_web_release.sh` passed after deployment.

## 2026-07-28 Store submission / device evidence

| Surface | Confirmed evidence |
|---|---|
| Android artifact | Canonical package `jp.miyamibu.urlalbum`; `versionName=1.0.15`, `versionCode=19`. `./gradlew --console=plain testDebugUnitTest lintDebug bundleRelease` passed. AAB `/Users/mimac/Desktop/りんばむ/app/build/outputs/bundle/release/app-release.aab` passed `bundletool validate`; SHA-256 `2915efe21d5659bfb3ddeb66c999fa900a9a9d2d39605a8f8d9c2fdb28a4c871`. |
| Google Play | Production remains `18 (1.0.15)` at 100% with no pending changes. Uploading the version 19 AAB was rejected with `アップロードしたすべてのバンドルに署名する必要があります。` No Android submission was made. |
| iOS artifact | Canonical bundle `com.mibu.codebridge.ios`; app and share extension `1.0.17 (19)`. Distribution archive/export succeeded. IPA `/Users/mimac/Library/Developer/Xcode/Archives/URLSaveriOS-20260728-1.0.17-app-store-export/りんばむ.ipa`; SHA-256 `cb2abc6b642bd13da87604e546a1c826cef024589b932390d52c32324f4baa68`. |
| App Store Connect | App `6771251450`; version `1.0.17`, build `19` uploaded, attached to the version, and submitted. After reload the console showed `審査待ち`. Apple review approval and App Store publication are not yet confirmed. |
| Physical iPhone | iPhone 12 UDID `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E`: version `1.0.17`, build `19` installed and launched with `devicectl`; app data was not reset. This is install/launch evidence, not Appium/WDA UI-operation proof. |
| Physical Android | Pixel 9a serial `55211JEBF16639` was not present in `adb devices` during this capture. The earlier version 18 install is not evidence for current build 19; version 19 physical installation remains unverified. |

## 2026-07-26 Current-main / Production Deployment Evidence

The following records are a dated provider-state capture made while the application implementation/recheck HEAD was `392e175347e5fbc97267a32944888e68eff71dd7`; later documentation-only commits may advance repository HEAD without changing the application source. They do not prove that every provider artifact was built from that implementation HEAD. The Render health response reported this implementation HEAD at capture time. Vercel (`web/admin` and `web/invite-link`), Railway, and Supabase provider status/deployment/function versions were confirmed, but source-revision correspondence was not independently verified from provider responses. These records do not close the remaining external release gates.

| Surface | Confirmed evidence |
|---|---|
| Repository | Application implementation/recheck HEAD `392e175347e5fbc97267a32944888e68eff71dd7`; later documentation-only commits may advance repository HEAD. |
| Vercel `web/admin` | Project `rinbamu-admin`; deployment `dpl_7acr2EjhiMLphJU4SKuPWh8toSsP` is `READY`; alias `https://rinbamu-admin.vercel.app`. Source-revision correspondence to the repository HEAD was not independently verified from the provider response. |
| Railway | Project/service `rinbam-youtube-resolver`; deployment `27192cdc-dd42-42ac-a44a-c937e115cc81` is `SUCCESS`; `https://rinbam-youtube-resolver-production.up.railway.app/health` returned HTTP 200. Source-revision correspondence to the repository HEAD was not independently verified from the provider response. |
| Render | `https://rinbam-media-resolver.onrender.com/health` returned HTTP 200; reported version is `392e175347e5fbc97267a32944888e68eff71dd7`. |
| Supabase | Project `xocumgxbylmpoobfqows`; functions `contact-support` v17, `contact-support-resend-webhook` v3, and `verify-store-purchase` v15; migration list is local=remote through `20260716140000`; `supabase db lint --linked` PASS. Source-revision correspondence to the repository HEAD was not independently verified from the provider response. |
| Vercel `web/invite-link` | Existing deployment `dpl_GeSsSnoG2tUyNfkuAtgaHqtQrmBd` remains prior provider-state evidence; source-revision correspondence to the repository HEAD was not independently verified from the provider response. |

## Verified Areas

| Area | Result | Evidence |
|---|---|---|
| Android | LOCAL_TEST_PASS / PLAY_V20_PUBLIC / PLAY_V21_REVIEW_SUBMITTED_100_PERCENT / PHYSICAL_V21_NOT_REVERIFIED | Canonical `jp.miyamibu.urlalbum`, `versionCode=21`, `versionName=1.0.17`, target SDK 36. Unit tests, lint, Release bundle, artifact guard, signature, and Bundletool validation pass. Google Play confirms the embedded ReTrace mapping and accepted v21 for a 100% / all-eligible-country production rollout; v20 remains public while v21 is under review. Physical v21 install and UI operation remain unverified. |
| iOS | LOCAL_TEST_PASS / APP_STORE_PUBLIC / PHYSICAL_INSTALL_VERIFIED | Current source `com.mibu.codebridge.ios`, `1.0.17` build `19`; distribution archive/export, App Store Connect upload/review, and public release succeeded. iPhone 12 install/launch is verified, while Appium/WDA UI-operation proof for this build was not re-run. |
| Supabase migration/replay | REMOTE_APPLIED_WITH_VALIDATION_PASS | Current-main evidence: project `xocumgxbylmpoobfqows`, functions `contact-support` v17, `contact-support-resend-webhook` v3, and `verify-store-purchase` v15; migration list is local=remote through `20260716140000`; `supabase db lint --linked` PASS. Local and linked databases include 42 migrations through `20260716140000_restore_account_reassignment.sql`; the additive fixes `20260716130000_fix_promo_invite_updated_at.sql` and `20260716140000_restore_account_reassignment.sql` are applied remotely. Linked pgTAP remains `NOT VERIFIED` because the CLI cannot resolve `db.xocumgxbylmpoobfqows.supabase.co`; the fixture-writing suite was not forced against production. |
| Physical iPhone UI | VERIFIED_TO_CHATGPT_COMPOSER_FOR_BUILD16 | Canonical build16 was overwrite-installed on UDID `00008101-00066D96340A001E` with app data retained. Appium/WDA verified tag selection, preview, confirmation, ChatGPT-specific ZIP `rinbam-chatgpt-…zip`, iOS SharingUIService ChatGPT selection, normal ChatGPT composer attachment, empty question field, and unsent state. Final ChatGPT send was intentionally not performed. |
| Physical Android latest candidate | VERIFIED_FOR_DEBUG_VERSIONCODE18 / PRIOR_DATA_BACKUP_INVALID | Canonical Pixel 9a `55211JEBF16639` had an incompatible Play-signed install. The first backup attempt was invalid (`run-as: package ... not debuggable`); no recoverable backup exists. After explicit approval, the Play install was removed, Debug `versionCode=18` was installed/launched, and the ChatGPT composer handoff was verified. The install script now validates the tar archive before proceeding. |
| Release flag contract | PASS | Android release derives local media saving from a configured HTTPS resolver, keeps AI transparency off, keeps ChatGPT personal-link operation off, and keeps shared-tag cloud mode explicit. iOS shared-tag and AI flags remain separately controlled by xcconfig/Info.plist. |
| Media resolver health | PASS_WITH_EXTERNAL_BACKEND | Current-main evidence: Render `https://rinbam-media-resolver.onrender.com/health` returned HTTP 200 with version `392e175347e5fbc97267a32944888e68eff71dd7`; Railway project/service `rinbam-youtube-resolver` deployment `27192cdc-dd42-42ac-a44a-c937e115cc81` is `SUCCESS`, and `https://rinbam-youtube-resolver-production.up.railway.app/health` returned HTTP 200. The current release BuildConfig contains the Render HTTPS host and `ALLOW_LOCAL_MEDIA_DOWNLOADS=true`. Resolver local contract tests passed 24/24. |
| MCP contract | PASS | `python3 scripts/verify_mcp_contract.py` passed. |
| Web/admin | PASS / VERCEL_PRODUCTION_VERIFIED | `cd web/admin && npm run typecheck && npm run lint && npm run build` passed; protected support/moderation/audit endpoints return 401 without bearer auth. Vercel project `rinbamu-admin` deployment `dpl_7acr2EjhiMLphJU4SKuPWh8toSsP` is `READY` with alias `https://rinbamu-admin.vercel.app`. |
| Admin operations | PASS_WITH_LIVE_ADMIN_AUTH | `admin_audit_logs` wiring, support queue/status/assignment, and moderation review APIs/UI are implemented. Linked migration `20260716100000_admin_ops_workflows.sql` applied; linked lint passed. Live admin actions still require an authorized owner/moderator account. |
| Mobile UI contract | PASS | `python3 scripts/verify_mobile_ui_contract.py` passed. |
| AI-safe Export baseline | PASS_CURRENT_WORKING_TREE / DEVICE_PARTIAL | Android/iOS tests cover `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, `aiEligible`, excerpts, saved snapshot notice, the manual ChatGPT handoff, all-field known-pattern redaction, unknown-secret warning/confirmation, and preview/archive parity. Device boundary is recorded above. |
| AI Preview / Receipt / Draft / Diff | PASS | Android Room and iOS SQLite persist local-only receipts/drafts/diff proposals; feature flag default off; mock provider deterministic; apply requires explicit confirmation. |
| Link death insurance | PASS | Export/MCP include saved-time metadata, `metadataSource`, excerpt/summary, and `savedSnapshotNotice`; raw `fetchedBody` is not default output. |
| Release hygiene | PASS_CURRENT_RECHECK | `bash scripts/check_release_hygiene.sh` and `git diff --check` passed on 2026-07-26. The version check now compares the app and Share Extension plist values instead of pinning an old version. |
| Clean review archive | PASS | `bash scripts/create_clean_review_archive.sh` creates the archive under the OS temp directory, not repo root. Forbidden-file grep returned OK. |
| Secret scan | PASS_WITH_EXPECTED_TEXT_HITS | Search hits were docs, example names, redaction patterns, Supabase role names in migrations, and local config references. No production secret values were found. |
| 2026-07-16 local release recheck | HISTORICAL_PASS_WITH_EXTERNAL_GATES | Android unit/lint/bundle, Web typecheck/lint/build, mobile UI contract, MCP contract, release hygiene, public Web checks, and the iOS shared-tag boundary regression test passed for the earlier baseline. It does not cover the 2026-07-17 manual handoff changes. |
| 2026-07-19 manual handoff recheck | IMPLEMENTATION_TEST_GO / ANDROID_COMPOSER_VERIFIED / IOS_COMPOSER_VERIFIED | iOS ExportArchiveBuilderTests (26) pass; current-source Apple Development device build/install pass. Pixel 9a reaches the normal ChatGPT composer with the ZIP attached, empty question field, and no send. iPhone 12 reaches the same state via Appium/WDA after selecting `ChatGPT` from the iOS share sheet. The iOS temporary ZIP is retained for 60 seconds after share-sheet dismissal to avoid an asynchronous recipient-import race. |
| 2026-07-23 main integration recheck | PASS_WITH_EXTERNAL_GATES | The hamburger-menu source is merged into `main`. Mobile UI contract, release hygiene, Android `testDebugUnitTest lintDebug bundleRelease` for canonical `versionCode=18` / `versionName=1.0.15`, iOS generic Release build, and iPhone 17 Simulator tests exited 0. Android AAB remains unsigned; iOS build17 distribution archive/upload and both store submissions remain unverified. |
| 2026-07-26 current-main recheck | LOCAL_VALIDATION_PASS / DEPLOYMENT_EVIDENCE_RECORDED / EXTERNAL_GATES_OPEN | Functional validation (Android `assembleDebug testDebugUnitTest lintDebug assembleRelease`, iOS Simulator `xcodebuild test` with 143 executed, 3 skipped, 0 failed, Web admin typecheck/lint/build, public Web verification, mobile UI contract, MCP contract, canonical tracker, and diff check) was performed at implementation HEAD `db4f7e79a765fd644156812e5a917d6649a99d17`. The subsequent documentation-only evidence sync produced current HEAD `392e175347e5fbc97267a32944888e68eff71dd7`; the repository readiness check was rerun against that clean HEAD and passed. This entry does not claim that the functional suite was rerun from `392e175347e5fbc97267a32944888e68eff71dd7`. Vercel `web/admin`, Railway, Render health, and Supabase provider evidence are recorded above; source-revision correspondence remains unverified except for the Render health SHA. Current-source physical devices, signed artifacts, store consoles, sandbox purchase, OpenAI submission, production secrets, MCP/OAuth registration, live store recheck, and full launch GO remain external/unverified. |

## Manual Steps Remaining After Internal Revalidation

| Manual step | Why it remains manual |
|---|---|
| Provider deployment/source revision correspondence | External / unverified: provider deployment/function state is recorded above, but independent confirmation that Vercel, Railway, Supabase, and `web/invite-link` artifacts correspond to the repository HEAD is still required. |
| Production MCP/OAuth registration | Requires owner-controlled provider console and secret entry. |
| OpenAI Apps Developer Mode connection and submission | Requires owner ChatGPT/OpenAI account and deployed HTTPS MCP endpoint. |
| Google Play review and release | Version `1.0.17 (21)` / target SDK 36 is submitted for a 100% production rollout to all eligible countries. Google review and public availability remain external; v20 stays publicly active until approval. |
| App Store Connect review and release | Version `1.0.17 (19)` is publicly available. No review or release action remains for this version. |
| Production secrets | Must be entered outside repo and chat. |
| Store/live verification | External state changes over time and must be verified at release time. |
| Physical-device final verification | iPhone 12 build 19 install/launch is verified. Android v21 installation and UI operation are unverified because no Android device or emulator was attached during the v21 release pass. |
| Sandbox purchase | External / unverified. |
| Full launch GO | External / unverified; the deployment evidence above does not constitute launch approval. |
| Signed iOS archive/upload | Distribution archive/export, App Store Connect upload/review, and public release for build 19 are complete. |

## REPO_GO vs LAUNCH_READY_REPO

| Status | Meaning |
|---|---|
| `REPO_GO` | Repo implementation is internally consistent and validated. External publication work is still manual. |
| `LAUNCH_READY_REPO` | Repo also contains the launch-operation artifacts: staging/internal/TestFlight procedures, OpenAI Developer Mode test plan, privacy/store disclosure checklist, production secrets/flags checklist, rollback plan, manual QA matrix, and readiness scripts. |

## Stop Conditions

- Any test weakening, removed redaction, noauth MCP personal data, raw body/prompt/token output, AI feature flag default on, shared-tag default inclusion, or root review archive returns the repo to `NO_GO_INTERNAL`.
- CoreSimulator-only failures after build-for-testing passes are not automatically internal blockers; classify as `NOT_VERIFIED` unless a code/test failure is found.
