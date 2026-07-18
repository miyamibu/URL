# AI Redaction Policy

## Goal
Export、MCP、AI Receipt/Draftで外部またはAI向けに出せる情報を安全な要約に限定する。

## Context
りんばむの主データはURLとユーザーが付けたタイトル/メモ/タグ。metadata fetch結果は保存時点の補助情報であり、現在のURL内容と一致する保証はない。

redactionは既知パターンに対するbest-effortの安全策であり、未知の秘密や新しい形式をすべて検出する保証ではない。手動で外部共有する場合は、redactionに加えてユーザー自身の共有前確認を必須境界とする。

## Constraints
- `fetchedBody`全文はExport/MCP/AIへdefaultで出さない。
- app-owned production secret、credential、access/refresh tokenはExport/preview/README/Receipt/logへ出さない。
- ChatGPT手動共有ではURL、title、author、tag、collection、`bodySummary`、`bodyExcerpt`、`description`、`memoExcerpt`を含む全出力文字列を既知パターン検出に通す。excerpt系は長さ制限も適用する。
- email、phone、token-like value、URLエンコードされたtoken区切り、一般的なprovider接頭辞型token（`sk-`、`ghp_`、`xoxb-`、`AIza`、`AKIA`等）、Supabase URL/JWT風文字列、local pathは既知のredaction対象として伏せ字にする。
- 既知パターン検出を通過しても、未知の秘密、任意形式の認証情報、URL query/fragmentやtitle/tag等に埋め込まれた新形式の秘密が残る可能性を共有前画面に明示する。
- 手動共有は、対象URLと出力内容をユーザーが確認し、未知の秘密が含まれていないことを明示確認してからZIP生成/OS共有へ進む。
- publicSafeIdはopaque。raw DB idを外部境界に出さない。
- shared tag由来URLは表示できてもAI対象はdefault `aiEligible=false`。
- archived/pending deleteもdefault AI対象外。
- Receiptにはraw prompt/body/token/attachmentを保存しない。

## Done when
- Exportに `schema.json`、`README_FOR_AI.md`、`redaction_report.json` が含まれる。
- `savedSnapshotNotice` が保存時点metadataに付く。
- ChatGPT手動共有のpreviewとZIPで同じredaction結果を使い、既知パターンが伏せ字になり、未知の秘密に関する警告と共有前確認が表示される。
- MCPはraw bodyを返さず、write/external callをしない。

## Validation
- Android/iOS Export tests.
- Android/iOS ChatGPT handoff tests: URL/title/author/tag/collection/summary/excerpt/memoの既知パターン、preview/archive一致、未知secret警告、明示確認なしの共有拒否。
- `python3 scripts/verify_mcp_contract.py`.
- secret scan fallback using `rg`.

## Failure handling
既知パターンのredaction漏れ、raw body/app-owned secret混入、preview/archive不一致、未知secret警告または明示確認の欠落を見つけた場合、そのarchive/API responseを共有せず `NO_GO_INTERNAL` とする。未知の秘密をユーザーがpreviewで見つけた場合は共有を中止し、元データを修正または対象から除外して再生成する。すでに外部共有した場合は共有先での削除可否を確認し、incidentとして扱う。
