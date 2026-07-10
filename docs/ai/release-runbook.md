# AI / MCP Release Runbook

## Goal
AI-safe Export / MCP / ChatGPT-facing codeを、repo内検証と外部公開ゲートに分けて扱う。

## Context
このrunbookは local source readiness 用。store submission、deploy、OpenAI submission はユーザー承認が必要。

## Steps
1. Confirm dirty tree and unrelated changes:
   - `git status --short`
   - `git diff --stat`
2. Run local AI/export checks:
   - `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ExportRepositoryTest --tests jp.mimac.urlsaver.AiTransparencyPolicyTest --tests jp.mimac.urlsaver.AiTransparencyRepositoryTest --tests jp.mimac.urlsaver.LinkDeathInsuranceContractTest`
   - iOS `build-for-testing` and `test-without-building` on a dedicated simulator when possible.
3. Run web/MCP checks:
   - `cd web/admin && npm run typecheck`
   - `python3 scripts/verify_mcp_contract.py`
4. Run release hygiene:
   - `bash scripts/check_release_hygiene.sh`
   - `bash scripts/create_clean_review_archive.sh`
5. Re-run mobile UI contract when UI surfaces changed:
   - `python3 scripts/verify_mobile_ui_contract.py`

## Constraints
- Do not commit, push, deploy, submit to stores, submit to OpenAI, or enter production secrets without explicit owner approval.
- Do not use old `1.0.11` store status as proof for current `1.0.14 (15)`.
- Keep local build/test proof separate from physical-device, public URL, store, and OpenAI review proof.
- Keep production deploy, OAuth registration, OpenAI submission, store submission, production secret input, and store/live recheck as Manual steps.
- Login with ChatGPT remains Docs only / adoption review.

## Done when
- Local checks pass or each failure has a concrete blocker.
- Final repo status uses only `REPO_GO`, `NO_GO_INTERNAL`, `BLOCKED_EXTERNAL`, or `NOT_VERIFIED`.

## Failure handling
If code/test/docs fail, use `NO_GO_INTERNAL`. If iOS build-for-testing passes but only CoreSimulator Busy/preflight blocks execution, use `NOT_VERIFIED`. If repo is ready and only external publication remains, use `REPO_GO` and list Manual steps.
