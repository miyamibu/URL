# Release / Ops Readiness Baseline 2026-07-09

## Goal
Track the current `1.0.15` repo readiness without mixing it with the historical `1.0.11` store submission log.

## Current Status
`NO_GO_INTERNAL` for the release as a whole. The 2026-07-18 manual ChatGPT handoff implementation, all-field known-pattern redaction, unknown-secret confirmation, Privacy/Store/App Review local text, automated tests, and iPhone-to-ChatGPT-composer E2E are GO on the current uncommitted working tree. Android physical E2E is not verified, Android AAB is unsigned, and iOS distribution signing/upload remains blocked. The earlier `REPO_GO` applies only to the 2026-07-10/16 baseline.

This is a repo-local readiness classification only. Production deploy, production MCP/OAuth registration, OpenAI submission, App Store / Play Console submission, production secret input, and store/live recheck are Manual steps and are not repo-internal blockers.

The older table below remains a broad ops baseline and historical risk ledger. Do not use its store/live/public-url rows as current proof for Android `1.0.15 (18)` / iOS `1.0.15 (16)`. The 2026-07-17 feature proof applies to the current uncommitted working tree and is not `LAUNCH_GO` proof.

## Source Snapshot
- Android: `jp.miyamibu.urlalbum`, `versionName = "1.0.15"`, `versionCode = 18`.
- iOS: `com.mibu.codebridge.ios`, `CFBundleShortVersionString = 1.0.15`, `CFBundleVersion = 16`.
- Current release evidence was rechecked on 2026-07-16 against Android versionCode 18 / iOS build 16. The manual-handoff implementation/build/test/device-partial evidence was rechecked separately on 2026-07-17. Store submission remains an external console step.
- Readiness must be run from the reviewed `main` branch after the release candidate is integrated; a feature branch is not a launch baseline.
- iOS default config: tracked `ios/Config/URLSaverSecrets.local-only.xcconfig`; cloud-sharing builds must pass ignored `ios/Config/URLSaverSecrets.xcconfig` explicitly.

## Current Gates / Proof Boundaries
| Area | Status | Required before GO |
|---|---|---|
| Release docs/store state | MANUAL_STEP | Reverify App Store Connect / Play Console / public URLs for the exact next submitted build. Do not use `1.0.11` rows as current proof. |
| Export contract | DONE_REPO / AUTOMATED_TEST_GO / IPHONE_COMPOSER_VERIFIED / ANDROID_DEVICE_PARTIAL | ZIP / JSON includes `manifest.json`, `entries.jsonl`, Markdown entry files, `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, redaction, AI eligibility, and saved snapshot notice. Manual handoff preview/archive parity, all-field known-pattern redaction, unknown-secret confirmation, and no-question/API/OAuth contracts pass. iPhone reaches the normal ChatGPT composer with the ChatGPT-specific ZIP attached and question field empty; Android physical E2E remains `NOT_VERIFIED`. |
| Admin audit | DONE_REPO | `web/admin/lib/audit.ts` records promo, support, and moderation actions in the existing immutable `admin_audit_logs` table; `/api/admin/audit` exposes the protected review view. |
| Support operations | DONE_REPO_WITH_LIVE_ADMIN_AUTH | Support queue/status fields were added by migration `20260716100000_admin_ops_workflows.sql`; `/api/admin/support` and the admin UI provide queue, assignment, status, and note workflow. Live use still requires an authorized admin account. |
| Moderation/report operations | DONE_REPO_WITH_LIVE_ADMIN_AUTH | `/api/admin/moderation` and the admin UI provide report review, reject/close actions, moderation history, and a user-suspension action. Live use still requires an authorized moderator/owner account. |
| ChatGPT personal link sync / MCP | DONE_REPO | Normal UI remains hidden. MCP is read-only, default disabled, auth/user-boundary enforced, shared tags excluded by default, and raw bodies are not returned. Production OAuth/deploy/OpenAI submission are Manual steps. |
| Media resolver / video wording | DONE_REPO_FOR_AI_SCOPE | AI/link-death remediation does not use YouTube/TikTok/X unofficial video download or headless/browser resolver. Store/live media claims remain Manual/external release review. |
| Artifact hygiene | DONE_REPO | Release hygiene and clean archive scripts are required before sharing. `URLSaver-clean-review.zip` defaults outside repo root. |

## Validation Method
- Android: `./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease assembleRelease`.
- iOS: `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`, plus simulator tests when iOS code changes.
- Web admin: `npm run typecheck`, `npm run lint`, `npm run build` under `web/admin`; protected admin APIs return 401 without a bearer token.
- Supabase functions: `deno check supabase/functions/*/index.ts` for changed functions.
- Mobile UI guard: `python3 scripts/verify_mobile_ui_contract.py`.
- AI / MCP local checks: `python3 scripts/verify_mcp_contract.py`, `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ExportRepositoryTest --tests jp.mimac.urlsaver.AiTransparencyPolicyTest`, and `npm run typecheck` under `web/admin`.
- External/live/store checks must be reported separately from local green checks.
- Linked migration `20260716100000_admin_ops_workflows.sql` was applied and `supabase db lint --linked --fail-on warning` passed.

## Failure Handling
- If any current binary, store console, public URL, or live backend state is not checked, report it as unverified.
- Use `REPO_GO`, `NO_GO_INTERNAL`, `BLOCKED_EXTERNAL`, or `NOT_VERIFIED` for current repo-gate reporting. Do not use legacy `BLOCKED_INTERNAL` as the final status enum.
- Do not print secret values from ignored local config files or production dashboards.
