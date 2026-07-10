# りんばむ AI / MCP Docs

## Goal
AI-friendly export、AI透明化、ChatGPT向け個人リンク検索、read-only MCP の現行契約を、実装済み範囲と未完了ゲートに分けて管理する。

## Context
- 通常UIの `ChatGPT連携` 設定は非表示のまま。
- AI向けの正式なユーザー導線は Android / iOS の Export 画面が中心。
- web/admin の MCP endpoint は read-only 試作であり、production公開やOpenAI提出は未実施。

## Index
- `feature-inventory.md`: 実装済み/未実装の棚卸し。
- `privacy-data-flow.md`: AI/Export/MCP で外へ出るデータ境界。
- `ai-receipt.md`: AI Preview / Receipt / Draft / Diff のローカル保存契約。
- `redaction-policy.md`: Export/MCP/Receipt のredaction方針。
- `ai-safe-export-contract.md`: ZIP / JSON のAI-safe出力契約。
- `mcp-readonly-contract.md`: MCP tool、auth、read-only制約。
- `mcp-tools.md`: MCP tool descriptorとread-only注釈の契約。
- `mcp-auth.md`: MCP default disabled、Bearer auth、user boundaryの契約。
- `mcp-staging-smoke-test.md`: staging MCP smoke testの前提、curl例、失敗時対応。
- `openai-apps-developer-mode-test-plan.md`: ChatGPT Developer Modeでの手動接続テスト計画。
- `openai-submission-readiness.md`: OpenAI submission前の手動確認チェックリスト。
- `production-runbook.md`: `REPO_GO` とManual stepsの分離。
- `release-runbook.md`: REPO_GO 前に確認する手順。

## Done when
- AIに渡るデータ、渡らないデータ、未検証ゲートが文書上で分離されている。
- 実装済みでない機能をstore文言、README、提出メモに書かない。
