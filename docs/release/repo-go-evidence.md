# REPO_GO Evidence

## Final status: REPO_GO

This file records repo-local evidence refreshed on 2026-07-13. Source verification used commit `bd0b8620`; this evidence update and the compatibility-test additions are carried by the current branch tip. `REPO_GO` means the repository implementation, docs, scripts, tests, build checks, and release hygiene are ready for pre-publication operations. It does not mean production deploy, store submission, OpenAI submission, production secret entry, or live/store verification is complete.

## Verified Areas

| Area | Result | Evidence |
|---|---|---|
| Android | PASS | `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug compileDebugAndroidTestKotlin --rerun-tasks` passed on 2026-07-13. Unit tests, lint, Debug/Release packaging, and AndroidTest Kotlin compilation completed successfully. |
| iOS | PASS | `xcodebuild -project ... -destination iPhone 17 Pro test` passed on 2026-07-13: 121 tests, 3 live Supabase tests skipped, 0 failures. Unsigned iPhoneOS Release build also passed. The alternate simulator was used because the store-shot simulator returned CoreSimulator Busy. |
| Supabase migration replay | PASS_WITH_ENVIRONMENT_WARNING | Local disposable replay reached `20260713223000_reconcile_promo_invite_code_events.sql` after compatibility repairs. The pgtap compatibility assertions are recorded in `supabase/tests/promo_invite_code_event_compatibility_validation.sql`. Final container startup failed on the Colima Docker socket mount; `supabase db lint --local` could not connect to `127.0.0.1:55422`. No remote database was touched. |
| MCP contract | PASS | `python3 scripts/verify_mcp_contract.py` passed. |
| Web/admin | PASS | `cd web/admin && npm run typecheck` passed. |
| Mobile UI contract | PASS | `python3 scripts/verify_mobile_ui_contract.py` passed. |
| AI-safe Export | PASS | Android/iOS export tests cover `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, `aiEligible`, excerpts, and saved snapshot notice. |
| AI Preview / Receipt / Draft / Diff | PASS | Android Room and iOS SQLite persist local-only receipts/drafts/diff proposals; feature flag default off; mock provider deterministic; apply requires explicit confirmation. |
| Link death insurance | PASS | Export/MCP include saved-time metadata, `metadataSource`, excerpt/summary, and `savedSnapshotNotice`; raw `fetchedBody` is not default output. |
| Release hygiene | PASS | `bash scripts/check_release_hygiene.sh` passed. |
| Clean review archive | PASS | `bash scripts/create_clean_review_archive.sh` creates the archive under the OS temp directory, not repo root. Forbidden-file grep returned OK. |
| Secret scan | PASS_WITH_EXPECTED_TEXT_HITS | Search hits were docs, example names, redaction patterns, Supabase role names in migrations, and local config references. No production secret values were found. |

## Manual Steps Remaining After REPO_GO

| Manual step | Why it remains manual |
|---|---|
| Production deploy | External publication action. |
| Production MCP/OAuth registration | Requires owner-controlled provider console and secret entry. |
| OpenAI Apps Developer Mode connection and submission | Requires owner ChatGPT/OpenAI account and deployed HTTPS MCP endpoint. |
| App Store / Play Console upload or submission | Store-console action. |
| Production secrets | Must be entered outside repo and chat. |
| Store/live verification | External state changes over time and must be verified at release time. |
| Signed iOS archive/upload | Requires Apple team, signing identity, provisioning, and App Store Connect access. |

## REPO_GO vs LAUNCH_READY_REPO

| Status | Meaning |
|---|---|
| `REPO_GO` | Repo implementation is internally consistent and validated. External publication work is still manual. |
| `LAUNCH_READY_REPO` | Repo also contains the launch-operation artifacts: staging/internal/TestFlight procedures, OpenAI Developer Mode test plan, privacy/store disclosure checklist, production secrets/flags checklist, rollback plan, manual QA matrix, and readiness scripts. |

## Stop Conditions

- Any test weakening, removed redaction, noauth MCP personal data, raw body/prompt/token output, AI feature flag default on, shared-tag default inclusion, or root review archive returns the repo to `NO_GO_INTERNAL`.
- CoreSimulator-only failures after build-for-testing passes are not automatically internal blockers; classify as `NOT_VERIFIED` unless a code/test failure is found.
