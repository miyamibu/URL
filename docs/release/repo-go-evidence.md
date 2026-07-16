# REPO_GO Evidence

## Final status: REPO_GO

This file records repo-local evidence refreshed on 2026-07-16. The reviewed release baseline is Android `versionCode=17` / iOS `build=15`, with code/config/test changes carried by commit `e923ea0d`. `REPO_GO` means the repository implementation, docs, scripts, tests, build checks, and release hygiene are ready for pre-publication operations. It does not mean production deploy, store submission, OpenAI submission, production secret entry, or live/store verification is complete.

## Verified Areas

| Area | Result | Evidence |
|---|---|---|
| Android | PASS_WITH_EXTERNAL_SIGNING_WAIT | `testDebugUnitTest`, `lintDebug`, and `bundleRelease` passed; canonical `jp.miyamibu.urlalbum`, `versionCode=17`, `versionName=1.0.14` produced a signed AAB. The configured HTTPS media resolver makes release `ALLOW_LOCAL_MEDIA_DOWNLOADS=true`; AI transparency and ChatGPT sync remain false. Play re-upload is still time-gated until `2026-07-17 07:29:38 UTC`. |
| iOS | PASS_WITH_UPLOAD_AUTH_GATE | `xcodebuild ... test` passed: 122 tests, 3 live Supabase tests skipped, 0 failures. The local/shared ChatGPT boundary regression suite is included (`SharedTagStoreTests` 4/4). The current source was rebuilt into a signed build15 archive and installed on the physical iPhone. App Store export succeeded with Apple Distribution signing, `get-task-allow=false`, and `manageAppVersionAndBuildNumber=false`; the final IPA is `/tmp/URLSaveriOS-export-20260715-final/りんばむ.ipa`. `altool` upload remains outside this task because App Store Connect credentials were not supplied. |
| Supabase migration/replay | REMOTE_APPLIED_WITH_VALIDATION_PASS | Local and linked databases include 40 migrations through `20260716100000_admin_ops_workflows.sql`; local and linked `supabase db lint --fail-on warning` both report no schema errors. The new support queue columns are present remotely. The stock `supabase test db` wrapper still has pre-existing fixture/permission failures in older auth/promo validation files; those failures are separate from this migration and are not schema lint failures. |
| Physical iPhone UI | PASS_WITH_CURRENT_APPIUM_E2E | Appium/XCUITest + RemoteXPC verified current build15 (`1.0.14`) of bundle `com.mibu.codebridge.ios` on physical UDID `00008101-00066D96340A001E`. The session opened an existing card's detail, opened and closed the 7-page media sheet, returned home, opened the manual URL form, saved a URL, and observed the normalized `example.com` card on home. Evidence: `artifacts/ui-review/2026-07-15/iphone-appium-e2e/`; prior navigation evidence remains under `artifacts/ui-review/2026-07-13/ios-appium/`. |
| Physical Android latest candidate | PARTIAL_WITH_PLAY_SIGNING_GATE | The canonical Pixel 9a is ADB-connected and Play-managed `versionCode=16`; current foreground, home screenshot, detail screen, local/shared tag labels, and saved URL card were verified without data reset. The v17 candidate is not Play-signature compatible for a data-preserving direct install. Play Internal update plus post-update UI/data proof remain pending until the upload-key reset activates at `2026-07-17 07:29:38 UTC`. |
| Release flag contract | PASS | Android release derives local media saving from a configured HTTPS resolver, keeps AI transparency off, keeps ChatGPT personal-link operation off, and keeps shared-tag cloud mode explicit. iOS shared-tag and AI flags remain separately controlled by xcconfig/Info.plist. |
| Media resolver health | PASS_WITH_EXTERNAL_BACKEND | `https://rinbam-media-resolver.onrender.com/health` returned HTTP 200 on 2026-07-16; the current release BuildConfig contains the same HTTPS host and `ALLOW_LOCAL_MEDIA_DOWNLOADS=true`. Resolver local contract tests passed 24/24. |
| MCP contract | PASS | `python3 scripts/verify_mcp_contract.py` passed. |
| Web/admin | PASS | `cd web/admin && npm run typecheck && npm run lint && npm run build` passed; protected support/moderation/audit endpoints return 401 without bearer auth. |
| Admin operations | PASS_WITH_LIVE_ADMIN_AUTH | `admin_audit_logs` wiring, support queue/status/assignment, and moderation review APIs/UI are implemented. Linked migration `20260716100000_admin_ops_workflows.sql` applied; linked lint passed. Live admin actions still require an authorized owner/moderator account. |
| Mobile UI contract | PASS | `python3 scripts/verify_mobile_ui_contract.py` passed. |
| AI-safe Export | PASS | Android/iOS export tests cover `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, `aiEligible`, excerpts, and saved snapshot notice. |
| AI Preview / Receipt / Draft / Diff | PASS | Android Room and iOS SQLite persist local-only receipts/drafts/diff proposals; feature flag default off; mock provider deterministic; apply requires explicit confirmation. |
| Link death insurance | PASS | Export/MCP include saved-time metadata, `metadataSource`, excerpt/summary, and `savedSnapshotNotice`; raw `fetchedBody` is not default output. |
| Release hygiene | PASS | `bash scripts/check_release_hygiene.sh` passed. |
| Clean review archive | PASS | `bash scripts/create_clean_review_archive.sh` creates the archive under the OS temp directory, not repo root. Forbidden-file grep returned OK. |
| Secret scan | PASS_WITH_EXPECTED_TEXT_HITS | Search hits were docs, example names, redaction patterns, Supabase role names in migrations, and local config references. No production secret values were found. |
| 2026-07-16 local release recheck | PASS_WITH_EXTERNAL_GATES | Android unit/lint/bundle, Web typecheck/lint/build, mobile UI contract, MCP contract, release hygiene, public Web checks, and the iOS shared-tag boundary regression test passed. `check_launch_readiness.sh` intentionally requires the reviewed `main` branch and current evidence date; branch integration remains a Git operation, not a store submission. Android v17 physical update and iOS TestFlight/full live E2E remain external/manual gates. |

## Manual Steps Remaining After REPO_GO

| Manual step | Why it remains manual |
|---|---|
| Production deploy | External publication action. |
| Production MCP/OAuth registration | Requires owner-controlled provider console and secret entry. |
| OpenAI Apps Developer Mode connection and submission | Requires owner ChatGPT/OpenAI account and deployed HTTPS MCP endpoint. |
| App Store / Play Console upload or submission | Android re-upload is time-gated until the Play reset activation time; iOS upload needs App Store Connect JWT or app-specific password. |
| Production secrets | Must be entered outside repo and chat. |
| Store/live verification | External state changes over time and must be verified at release time. |
| Signed iOS archive/upload | Distribution-signed IPA is ready; only App Store Connect upload authentication remains. |

## REPO_GO vs LAUNCH_READY_REPO

| Status | Meaning |
|---|---|
| `REPO_GO` | Repo implementation is internally consistent and validated. External publication work is still manual. |
| `LAUNCH_READY_REPO` | Repo also contains the launch-operation artifacts: staging/internal/TestFlight procedures, OpenAI Developer Mode test plan, privacy/store disclosure checklist, production secrets/flags checklist, rollback plan, manual QA matrix, and readiness scripts. |

## Stop Conditions

- Any test weakening, removed redaction, noauth MCP personal data, raw body/prompt/token output, AI feature flag default on, shared-tag default inclusion, or root review archive returns the repo to `NO_GO_INTERNAL`.
- CoreSimulator-only failures after build-for-testing passes are not automatically internal blockers; classify as `NOT_VERIFIED` unless a code/test failure is found.
