# AI Release Gate Skill

Use this skill when changing AI-friendly export, ChatGPT personal-link sync, MCP, OpenAI Apps SDK integration, or AI transparency docs for りんばむ.

## Goal
Keep AI-facing changes read-only, redacted, and release-gated unless the owner explicitly approves a production action.

## Required context
- Read `docs/ai/feature-inventory.md`.
- Read `docs/ai/privacy-data-flow.md`.
- Read `docs/ai/ai-receipt.md` for Preview/Receipt/Draft/Diff changes.
- Read `docs/ai/redaction-policy.md` for raw-body/prompt/secret boundaries.
- For export changes, read `docs/ai/ai-safe-export-contract.md`.
- For MCP changes, read `docs/ai/mcp-readonly-contract.md`, `docs/ai/mcp-tools.md`, and `docs/ai/mcp-auth.md`.
- For launch-readiness work, read `docs/release/launch-go-checklist.md`, `docs/release/launch-status-model.md`, `docs/release/staging-deploy-checklist.md`, `docs/release/manual-qa-matrix.md`, `docs/release/rollback-plan.md`, `docs/ai/mcp-staging-smoke-test.md`, `docs/ai/openai-apps-developer-mode-test-plan.md`, and `docs/ai/openai-submission-readiness.md`.

## Constraints
- Do not expose raw `fetchedBody`, raw prompts, secrets, tokens, local paths, or raw DB UUIDs.
- Do not add MCP write tools without explicit approval and a new contract.
- Do not deploy, submit to OpenAI, commit, push, or change production secrets without explicit approval.
- Keep shared-tag entries AI-ineligible by default unless a future owner-approved opt-in flow exists.
- Keep Receipt/Draft/Diff local-only. Receipt stores metadata and bucketed request/response sizes only.
- Keep MCP default disabled unless `URLSAVER_MCP_ENABLED=true`; auth and user boundary are mandatory.
- Treat production deploy, OAuth registration, OpenAI submission, store submission, production secrets, and live/store recheck as Manual steps, not repo-internal blockers.
- Login with ChatGPT remains Docs only / adoption review; do not wire it as app login.
- Do not use YouTube/TikTok/X unofficial video download or headless/browser resolver as the default link-death solution.

## Validation
- Android export/transparency: `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ExportRepositoryTest --tests jp.mimac.urlsaver.AiTransparencyPolicyTest --tests jp.mimac.urlsaver.AiTransparencyRepositoryTest --tests jp.mimac.urlsaver.LinkDeathInsuranceContractTest`
- iOS export/transparency: `xcodebuild ... build-for-testing` and `test-without-building` on a dedicated simulator when possible.
- MCP: `python3 scripts/verify_mcp_contract.py`
- Web: `cd web/admin && npm run typecheck`
- Release hygiene: `bash scripts/check_release_hygiene.sh`
- Launch readiness: `bash scripts/check_launch_readiness.sh`

## Failure handling
- If any validation cannot run, state the exact reason.
- If a raw data leak, unconfirmed Diff apply, noauth MCP data, or write path is found, mark the repo `NO_GO_INTERNAL`.
- If only CoreSimulator Busy/preflight remains after build-for-testing passes, report `NOT_VERIFIED` instead of treating it as internal code failure.
