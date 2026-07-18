# AI / MCP Release Runbook

## Goal
AI-safe Export / MCP / ChatGPT-facing codeを、repo内検証と外部公開ゲートに分けて扱う。

## Context
このrunbookは local source readiness 用。store submission、deploy、OpenAI submission はユーザー承認が必要。

`ChatGPTに聞く` の手動ファイル共有は、端末内の選択/preview/ZIP生成とOS共有までであり、read-only MCP、ChatGPT個人リンク同期、production AI provider/API/OAuthとは別経路。2026-07-18の追加差分は実装・自動テストGO。iPhoneはChatGPT用ZIPを通常トーク画面へ添付し質問欄空欄・未送信まで確認済み。Android実機、署名済み配布物、公開/store状態は別ゲート。

## Steps
1. Confirm dirty tree and unrelated changes:
   - `git status --short`
   - `git diff --stat`
2. Run local AI/export checks:
   - `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ExportRepositoryTest --tests jp.mimac.urlsaver.AiTransparencyPolicyTest --tests jp.mimac.urlsaver.AiTransparencyRepositoryTest --tests jp.mimac.urlsaver.LinkDeathInsuranceContractTest`
   - iOS `build-for-testing` and `test-without-building` on a dedicated simulator when possible.
   - Manual handoff tests must cover eligible-only preview/ZIP, all-field known-pattern redaction, preview/archive parity, unknown-secret warning/explicit confirmation, and no question/API/OAuth path.
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
- Do not use old `1.0.11` store status as proof for the current source baseline (`1.0.15`, Android `versionCode=18`, iOS `build=16`).
- Keep local build/test proof separate from physical-device, public URL, store, and OpenAI review proof.
- Keep production deploy, OAuth registration, OpenAI submission, store submission, production secret input, and store/live recheck as Manual steps.
- Do not require MCP/provider deployment for manual handoff. Do require public Privacy/App Review text and current store-form review before submitting a binary that exposes the handoff.
- Login with ChatGPT remains Docs only / adoption review.

## Done when
- Local checks pass or each failure has a concrete blocker.
- Final repo status uses only `REPO_GO`, `NO_GO_INTERNAL`, `BLOCKED_EXTERNAL`, or `NOT_VERIFIED`.

## Failure handling
If code/test/docs fail, use `NO_GO_INTERNAL`. If iOS build-for-testing passes but only CoreSimulator Busy/preflight blocks execution, use `NOT_VERIFIED`. If repo is ready and only external publication remains, use `REPO_GO` and list Manual steps.
