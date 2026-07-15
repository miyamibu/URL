# REPO_GO Evidence

## Final status: REPO_GO

This file records repo-local evidence refreshed on 2026-07-15. Source implementation verification is carried by commit `a44b0d31`; this evidence update is committed separately so the implementation reference stays stable. `REPO_GO` means the repository implementation, docs, scripts, tests, build checks, and release hygiene are ready for pre-publication operations. It does not mean production deploy, store submission, OpenAI submission, production secret entry, or live/store verification is complete.

## Verified Areas

| Area | Result | Evidence |
|---|---|---|
| Android | PASS_WITH_EXTERNAL_SIGNING_WAIT | `testDebugUnitTest`, `lintDebug`, packaging, and AndroidTest Kotlin compilation passed; canonical `jp.miyamibu.urlalbum`, `versionCode=17`, `versionName=1.0.14` produced a new-key signed AAB. Play accepted the file transfer but rejected it because the upload-key reset is not active yet; the console states re-upload is available after `2026-07-17 07:29:38 UTC`. |
| iOS | PASS_WITH_UPLOAD_AUTH_GATE | `xcodebuild ... test` passed: 121 tests, 3 live Supabase tests skipped, 0 failures. The current source was rebuilt into a signed build15 archive and installed on the physical iPhone. App Store export succeeded with Apple Distribution signing, `get-task-allow=false`, and `manageAppVersionAndBuildNumber=false`; the final IPA is `/tmp/URLSaveriOS-export-20260715-final/りんばむ.ipa`. `altool` upload is blocked only because no App Store Connect JWT or app-specific password was supplied. |
| Supabase migration/replay | REMOTE_APPLIED_WITH_VALIDATION_PASS | Local and linked databases include all 39 migrations through `20260715120000_fix_promo_delivery_lint_warning.sql`; local `supabase db lint --local --fail-on warning` and the compatibility pgTAP test both pass (5/5). The linked project accepted the new migration, `supabase db lint --linked --fail-on warning` reports no schema errors, and the same five compatibility assertions are all `ok: true` through `supabase db query --linked` using the native IPv6 resolver. The stock `supabase test db --linked` wrapper still fails after its initial IPv6 connection because its containerized psql re-resolves the hostname and receives no usable address; this is a CLI/DNS transport limitation, not a failing database assertion. |
| Physical iPhone UI | PASS_WITH_CURRENT_APPIUM_E2E | Appium/XCUITest + RemoteXPC verified current build15 (`1.0.14`) of bundle `com.mibu.codebridge.ios` on physical UDID `00008101-00066D96340A001E`. The session opened an existing card's detail, opened and closed the 7-page media sheet, returned home, opened the manual URL form, saved a URL, and observed the normalized `example.com` card on home. Evidence: `artifacts/ui-review/2026-07-15/iphone-appium-e2e/`; prior navigation evidence remains under `artifacts/ui-review/2026-07-13/ios-appium/`. |
| Physical Android latest candidate | BLOCKED | Canonical Pixel 9a is connected, but the latest release candidate is not Play-signature compatible for a data-preserving direct install. Play Internal update and post-update UI/data proof remain pending. |
| MCP contract | PASS | `python3 scripts/verify_mcp_contract.py` passed. |
| Web/admin | PASS | `cd web/admin && npm run typecheck` passed. |
| Mobile UI contract | PASS | `python3 scripts/verify_mobile_ui_contract.py` passed. |
| AI-safe Export | PASS | Android/iOS export tests cover `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, `aiEligible`, excerpts, and saved snapshot notice. |
| AI Preview / Receipt / Draft / Diff | PASS | Android Room and iOS SQLite persist local-only receipts/drafts/diff proposals; feature flag default off; mock provider deterministic; apply requires explicit confirmation. |
| Link death insurance | PASS | Export/MCP include saved-time metadata, `metadataSource`, excerpt/summary, and `savedSnapshotNotice`; raw `fetchedBody` is not default output. |
| Release hygiene | PASS | `bash scripts/check_release_hygiene.sh` passed. |
| Clean review archive | PASS | `bash scripts/create_clean_review_archive.sh` creates the archive under the OS temp directory, not repo root. Forbidden-file grep returned OK. |
| Secret scan | PASS_WITH_EXPECTED_TEXT_HITS | Search hits were docs, example names, redaction patterns, Supabase role names in migrations, and local config references. No production secret values were found. |
| 2026-07-15 local release recheck | PASS_WITH_EXTERNAL_GATES | Repository checks passed and `LAUNCH_READY_REPO` is green. Supabase lint is warning-free and linked compatibility assertions are 5/5. Android v17 AAB upload was attempted and rejected only by the Play reset activation window; the canonical Pixel remains Play-managed versionCode 16. The current iOS source (including shared-tag detail parity) was rebuilt, installed as build15, and exported to `/tmp/URLSaveriOS-export-20260715-final/りんばむ.ipa`; the current-source physical iPhone Appium E2E for save/detail/media is recorded under `artifacts/ui-review/2026-07-15/iphone-appium-e2e/`. App Store Connect upload remains stopped by missing JWT/app-specific-password authentication. |

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
