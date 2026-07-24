# Release / Ops Readiness Baseline 2026-07-09

## Superseded current status (2026-07-24)

`NO_GO_INTERNAL / NOT_VERIFIED_FOR_RELEASE`。この 2026-07-09 baseline は
履歴・運用論点の参照用であり、下記の `REPO_GO` や 1.0.14/1.0.14 の
source snapshot を現在の証拠として再利用してはならない。現行判定は
`docs/release/current-readiness-2026-07-24.yaml` を参照する。

## Goal
Track the current `1.0.14` repo readiness without mixing it with the historical `1.0.11` store submission log.

## Historical Status (superseded)
`REPO_GO` for the 2026-07-10 repo-local AI-safe Export / AI Receipt-Draft-Diff / read-only MCP / release hygiene remediation scope.

This is a repo-local readiness classification only. Production deploy, production MCP/OAuth registration, OpenAI submission, App Store / Play Console submission, production secret input, and store/live recheck are Manual steps and are not repo-internal blockers.

The older table below remains a broad ops baseline and historical risk ledger. Do not use its store/live/public-url rows as current proof for Android `1.0.14 (17)` / iOS `1.0.14 (15)`, and do not mix those external checks into the repo-local `REPO_GO` classification.

## Source Snapshot
- Android: `jp.miyamibu.urlalbum`, `versionName = "1.0.14"`, `versionCode = 17`.
- iOS: `com.mibu.codebridge.ios`, `CFBundleShortVersionString = 1.0.14`, `CFBundleVersion = 15`.
- Current release evidence was rechecked on 2026-07-16 against Android versionCode 17 / iOS build 15. The signed candidates are not store-submitted by this repo task.
- Readiness must be run from the reviewed `main` branch after the release candidate is integrated; a feature branch is not a launch baseline.
- iOS default config: tracked `ios/Config/URLSaverSecrets.local-only.xcconfig`; cloud-sharing builds must pass ignored `ios/Config/URLSaverSecrets.xcconfig` explicitly.

## P1 Blockers
| Area | Status | Required before GO |
|---|---|---|
| Release docs/store state | MANUAL_STEP | Reverify App Store Connect / Play Console / public URLs for the exact next submitted build. Do not use `1.0.11` rows as current proof. |
| Export contract | DONE_REPO | Current local implementation is ZIP / JSON with `manifest.json`, `entries.jsonl`, Markdown entry files, `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, redaction, AI eligibility, and saved snapshot notice. |
| Admin audit | BROAD_OPS_OPEN | Wire admin actions to `admin_audit_logs`, or explicitly defer central audit logging with owner approval before release. Not part of the 2026-07-10 AI/MCP/export repo gate. |
| Support operations | BROAD_OPS_OPEN | Contact support email delivery exists, but there is no admin queue/status workflow for support requests. Not part of the 2026-07-10 AI/MCP/export repo gate. |
| Moderation/report operations | BROAD_OPS_OPEN | Shared content report/moderation tables and RPCs exist, but no admin review workflow is present. Not part of the 2026-07-10 AI/MCP/export repo gate. |
| ChatGPT personal link sync / MCP | DONE_REPO | Normal UI remains hidden. MCP is read-only, default disabled, auth/user-boundary enforced, shared tags excluded by default, and raw bodies are not returned. Production OAuth/deploy/OpenAI submission are Manual steps. |
| Media resolver / video wording | DONE_REPO_FOR_AI_SCOPE | AI/link-death remediation does not use YouTube/TikTok/X unofficial video download or headless/browser resolver. Store/live media claims remain Manual/external release review. |
| Artifact hygiene | DONE_REPO | Release hygiene and clean archive scripts are required before sharing. `URLSaver-clean-review.zip` defaults outside repo root. |

## Validation Method
- Android: `./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease assembleRelease`.
- iOS: `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`, plus simulator tests when iOS code changes.
- Web admin: `npm run typecheck`, `npm run lint`, `npm run build` under `web/admin`.
- Supabase functions: `deno check supabase/functions/*/index.ts` for changed functions.
- Mobile UI guard: `python3 scripts/verify_mobile_ui_contract.py`.
- AI / MCP local checks: `python3 scripts/verify_mcp_contract.py`, `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ExportRepositoryTest --tests jp.mimac.urlsaver.AiTransparencyPolicyTest`, and `npm run typecheck` under `web/admin`.
- External/live/store checks must be reported separately from local green checks.

## Failure Handling
- If any current binary, store console, public URL, or live backend state is not checked, report it as unverified.
- Use `REPO_GO`, `NO_GO_INTERNAL`, `BLOCKED_EXTERNAL`, or `NOT_VERIFIED` for current repo-gate reporting. Do not use legacy `BLOCKED_INTERNAL` as the final status enum.
- Do not print secret values from ignored local config files or production dashboards.
