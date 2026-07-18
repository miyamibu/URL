# REPO_GO Evidence

Final status: REPO_GO

## Current working-tree status: REPO_GO (external release gates remain separate)

The historical evidence below is retained, but the current 2026-07-18 evidence proves the manual ChatGPT handoff on a canonical Android physical device, a current-source Apple Development device install/launch, and a live Privacy Policy deployment/recheck. The current source baseline is Android `versionCode=18` / iOS `build=16`. Distribution signing, store upload/submission, OpenAI submission, production secret entry, and store-console review remain separate external/manual gates.

Current proof boundary:

- Android/iOS manual-handoff implementation and automated tests prove local-tag selection, eligible-only preview/ZIP, preview/archive parity, zero-result rejection, filename/manifest contracts, known-pattern redaction, unknown-secret warning/confirmation, and no question/API/OAuth path.
- Physical iPhone proof for the current source is limited to current-source Apple Development install/launch; Appium/WDA UI operation remains unverified because Appium responds but the RemoteXPC endpoint reports zero active iPhone tunnels. Historical build16 composer evidence is retained separately and is not promoted to current-source proof.
- Physical Android manual handoff is verified on Pixel 9a `55211JEBF16639`: local tag selection, preview, explicit confirmation, ZIP creation, direct `com.openai.chatgpt` share, ZIP attachment in the normal composer, empty question field, and intentionally unsent state.
- Android Release build succeeds but the generated AAB is unsigned. iOS current-source device build is Apple Development-signed; Distribution signing/upload remains blocked.
- Privacy Policy source was deployed to Vercel production deployment `dpl_5YKkCQxcAAmjQ4NxqFHaojuDw2Re`; `scripts/verify_public_web_release.sh` passed against `https://miyamibu.xyz` after deployment.

## Verified Areas

| Area | Result | Evidence |
|---|---|---|
| Android | BUILD_TEST_PASS / UNSIGNED_AAB / DEVICE_CHATGPT_COMPOSER_VERIFIED | Canonical `jp.miyamibu.urlalbum`, `versionCode=18`, `versionName=1.0.15`. Unit tests, lint, Debug build, Release APK/AAB build pass. `jarsigner -verify -strict` reports the current `app-release.aab` is unsigned, so it is not upload proof. Pixel 9a direct-share proof is recorded under `artifacts/device-verification/2026-07-18-android-ch13/`. |
| iOS | CURRENT_SOURCE_DEVICE_INSTALL_PASS / APPIUM_UI_UNVERIFIED / DISTRIBUTION_BLOCKED | Current source `com.mibu.codebridge.ios`, `1.0.15` build `16` built with Apple Development signing and installed/launched on iPhone 12 UDID `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E`. Appium/WDA UI proof is not current-source verified because Appium 4723 and RemoteXPC 42314 are unavailable. No Apple Distribution identity is installed. |
| Supabase migration/replay | REMOTE_APPLIED_WITH_VALIDATION_PASS | Local and linked databases include 42 migrations through `20260716140000_restore_account_reassignment.sql`; the additive fixes `20260716130000_fix_promo_invite_updated_at.sql` and `20260716140000_restore_account_reassignment.sql` are applied remotely. Local and linked `supabase db lint --fail-on warning` report no schema errors, and a clean local `supabase test db --local supabase/tests` run passes all 4 files / 8 tests. Linked pgTAP remains `NOT VERIFIED` because the CLI cannot resolve `db.xocumgxbylmpoobfqows.supabase.co`; the fixture-writing suite was not forced against production. |
| Physical iPhone UI | VERIFIED_TO_CHATGPT_COMPOSER_FOR_BUILD16 | Canonical build16 was overwrite-installed on UDID `00008101-00066D96340A001E` with app data retained. Appium/WDA verified tag selection, preview, confirmation, ChatGPT-specific ZIP `rinbam-chatgpt-…zip`, iOS SharingUIService ChatGPT selection, normal ChatGPT composer attachment, empty question field, and unsent state. Final ChatGPT send was intentionally not performed. |
| Physical Android latest candidate | VERIFIED_FOR_DEBUG_VERSIONCODE18 / PRIOR_DATA_BACKUP_INVALID | Canonical Pixel 9a `55211JEBF16639` had an incompatible Play-signed install. The first backup attempt was invalid (`run-as: package ... not debuggable`); no recoverable backup exists. After explicit approval, the Play install was removed, Debug `versionCode=18` was installed/launched, and the ChatGPT composer handoff was verified. The install script now validates the tar archive before proceeding. |
| Release flag contract | PASS | Android release derives local media saving from a configured HTTPS resolver, keeps AI transparency off, keeps ChatGPT personal-link operation off, and keeps shared-tag cloud mode explicit. iOS shared-tag and AI flags remain separately controlled by xcconfig/Info.plist. |
| Media resolver health | PASS_WITH_EXTERNAL_BACKEND | `https://rinbam-media-resolver.onrender.com/health` returned HTTP 200 on 2026-07-16; the current release BuildConfig contains the same HTTPS host and `ALLOW_LOCAL_MEDIA_DOWNLOADS=true`. Resolver local contract tests passed 24/24. |
| MCP contract | PASS | `python3 scripts/verify_mcp_contract.py` passed. |
| Web/admin | PASS | `cd web/admin && npm run typecheck && npm run lint && npm run build` passed; protected support/moderation/audit endpoints return 401 without bearer auth. |
| Admin operations | PASS_WITH_LIVE_ADMIN_AUTH | `admin_audit_logs` wiring, support queue/status/assignment, and moderation review APIs/UI are implemented. Linked migration `20260716100000_admin_ops_workflows.sql` applied; linked lint passed. Live admin actions still require an authorized owner/moderator account. |
| Mobile UI contract | PASS | `python3 scripts/verify_mobile_ui_contract.py` passed. |
| AI-safe Export baseline | PASS_CURRENT_WORKING_TREE / DEVICE_PARTIAL | Android/iOS tests cover `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, `aiEligible`, excerpts, saved snapshot notice, the manual ChatGPT handoff, all-field known-pattern redaction, unknown-secret warning/confirmation, and preview/archive parity. Device boundary is recorded above. |
| AI Preview / Receipt / Draft / Diff | PASS | Android Room and iOS SQLite persist local-only receipts/drafts/diff proposals; feature flag default off; mock provider deterministic; apply requires explicit confirmation. |
| Link death insurance | PASS | Export/MCP include saved-time metadata, `metadataSource`, excerpt/summary, and `savedSnapshotNotice`; raw `fetchedBody` is not default output. |
| Release hygiene | PASS_CURRENT_RECHECK | `bash scripts/check_release_hygiene.sh` passed for the current code/docs; `git diff --check` also passed. |
| Clean review archive | PASS | `bash scripts/create_clean_review_archive.sh` creates the archive under the OS temp directory, not repo root. Forbidden-file grep returned OK. |
| Secret scan | PASS_WITH_EXPECTED_TEXT_HITS | Search hits were docs, example names, redaction patterns, Supabase role names in migrations, and local config references. No production secret values were found. |
| 2026-07-16 local release recheck | HISTORICAL_PASS_WITH_EXTERNAL_GATES | Android unit/lint/bundle, Web typecheck/lint/build, mobile UI contract, MCP contract, release hygiene, public Web checks, and the iOS shared-tag boundary regression test passed for the earlier baseline. It does not cover the 2026-07-17 manual handoff changes. |
| 2026-07-18 manual handoff recheck | IMPLEMENTATION_TEST_GO / ANDROID_COMPOSER_VERIFIED / IOS_CURRENT_SOURCE_INSTALL_ONLY | Android unit/lint/Debug/Release builds and iOS tests/current-source Apple Development device build pass. Pixel 9a reaches the normal ChatGPT composer with the ChatGPT-specific ZIP attached, empty question field, and no send. iOS current source is installed/launched, but Appium/WDA UI operation is not verified. Public Privacy deploy and live verifier pass. |

## Manual Steps Remaining After Internal Revalidation

| Manual step | Why it remains manual |
|---|---|
| Production deploy | Privacy static site deployment is complete and verified; any future production service deployment remains external. |
| Production MCP/OAuth registration | Requires owner-controlled provider console and secret entry. |
| OpenAI Apps Developer Mode connection and submission | Requires owner ChatGPT/OpenAI account and deployed HTTPS MCP endpoint. |
| App Store / Play Console upload or submission | App Store Connect iOS version `1.0.15` is created and left in `提出準備中`, but the distribution-signed build is unavailable. Android re-upload may remain time-gated until the Play reset activation time; iOS upload needs a distribution certificate/profile and upload authentication. |
| Production secrets | Must be entered outside repo and chat. |
| Store/live verification | External state changes over time and must be verified at release time. |
| Signed iOS archive/upload | A build16 development archive exists, but distribution archive/export and App Store Connect upload authentication remain to be completed for this release. |

## REPO_GO vs LAUNCH_READY_REPO

| Status | Meaning |
|---|---|
| `REPO_GO` | Repo implementation is internally consistent and validated. External publication work is still manual. |
| `LAUNCH_READY_REPO` | Repo also contains the launch-operation artifacts: staging/internal/TestFlight procedures, OpenAI Developer Mode test plan, privacy/store disclosure checklist, production secrets/flags checklist, rollback plan, manual QA matrix, and readiness scripts. |

## Stop Conditions

- Any test weakening, removed redaction, noauth MCP personal data, raw body/prompt/token output, AI feature flag default on, shared-tag default inclusion, or root review archive returns the repo to `NO_GO_INTERNAL`.
- CoreSimulator-only failures after build-for-testing passes are not automatically internal blockers; classify as `NOT_VERIFIED` unless a code/test failure is found.
