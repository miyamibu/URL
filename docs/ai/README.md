# りんばむ AI / MCP Docs

## Goal
AI-friendly export、AI透明化、ChatGPT向け個人リンク検索、read-only MCP の現行契約を、実装済み範囲と未完了ゲートに分けて管理する。

## Context
- Android/iOS の共有タグプロフィール画面には `ChatGPTに保存リンクを同期` カードを表示する。外部接続未設定時は「現在は利用できません」と表示し、操作不可とする。
- AI透明化は Android の `DEBUG && AI_TRANSPARENCY_ENABLED`、iOS の `DEBUG` と Info.plist flag に限定された Debug-only UI で、通常のRelease UIには出ない。
- AI向けの正式なユーザー導線は Android / iOS の Export 画面が中心。
- web/admin の MCP endpoint は read-only 試作であり、production公開やOpenAI提出は未実施。MCP/Store/Supabase live は未検証の外部ゲートであり、repo内のローカル実装/検証とは分離する。

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
