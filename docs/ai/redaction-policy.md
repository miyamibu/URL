# AI Redaction Policy

## Goal
Export、MCP、AI Receipt/Draftで外部またはAI向けに出せる情報を安全な要約に限定する。

## Context
りんばむの主データはURLとユーザーが付けたタイトル/メモ/タグ。metadata fetch結果は保存時点の補助情報であり、現在のURL内容と一致する保証はない。

## Constraints
- `fetchedBody`全文はExport/MCP/AIへdefaultで出さない。
- `bodySummary`、`bodyExcerpt`、`description`、`memoExcerpt`は長さ制限とredactionを通す。
- email、phone、token、Supabase URL/JWT風文字列、local pathはredaction対象。
- publicSafeIdはopaque。raw DB idを外部境界に出さない。
- shared tag由来URLは表示できてもAI対象はdefault `aiEligible=false`。
- archived/pending deleteもdefault AI対象外。
- Receiptにはraw prompt/body/token/attachmentを保存しない。

## Done when
- Exportに `schema.json`、`README_FOR_AI.md`、`redaction_report.json` が含まれる。
- `savedSnapshotNotice` が保存時点metadataに付く。
- MCPはraw bodyを返さず、write/external callをしない。

## Validation
- Android/iOS Export tests.
- `python3 scripts/verify_mcp_contract.py`.
- secret scan fallback using `rg`.

## Failure handling
redaction漏れやraw body混入を見つけた場合、そのarchive/API responseを共有せず `NO_GO_INTERNAL` とする。
