# AI Feature Inventory

## Goal
`1prompt.docx` のAI透明化/個人リンク記憶要求を、現在の repo 実装状態に落として追跡する。

## Context
2026-07-10時点の作業では、DB破壊・store提出・deploy・production secret投入は行っていない。`REPO_GO` はrepo内の実装/検証が外部公開前に揃ったという意味であり、production deploy、OpenAI submission、store submission、production secret投入、store/live再確認はManual stepsとして分離する。

## Inventory
| Area | Status | Evidence | Remaining gate |
|---|---|---|---|
| AI-safe Export ZIP / JSON | IMPLEMENTED_LOCAL | Android `ExportRepository.kt`, iOS `ExportArchiveBuilder.swift` が `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, `aiEligible`, excerpt/redaction, `savedSnapshotNotice` を出力する。 | external share-sheet/store proof is Manual step |
| Raw fetched body exclusion | IMPLEMENTED_LOCAL | Export documentから `fetchedBody` を除外し、`bodyExcerpt` と `bodySummary` に限定。 | future opt-in body export requires new approval |
| Shared tag AI boundary | IMPLEMENTED_LOCAL | 共有タグ付きentryは `aiEligible=false` / `shared_tag_default_excluded`。Android ChatGPT sync は local-only tags を送る。 | iOS personal-link syncのlocal-only tag parity review |
| Preview / Receipt / Draft / Diff | IMPLEMENTED_LOCAL | Android RoomとiOS SQLiteにReceipt/Draft/Diffをローカル保存。feature flag default off。Mock providerでdeterministic draft生成。Diff applyは明示confirm時のみ許可フィールドを更新。 | production AI provider wiring is future opt-in |
| Read-only MCP descriptors | IMPLEMENTED_LOCAL | `web/admin/lib/rinbamMcp.ts` が `search`, `fetch`, `rinbam.list_tags`, `rinbam.get_ai_receipt`, `rinbam.list_recent_saved_links` を定義。annotationsはread-only固定。 | deployed URL / OpenAI review are Manual steps |
| MCP data access | IMPLEMENTED_LOCAL | `URLSAVER_MCP_ENABLED=true` が無い限りendpointはdefault disabled。Bearer tokenを `auth.getUser` し、全queryで `user_id` を固定。rate limitあり。raw `fetched_body` は返さない。 | production OAuth/client registration is Manual step |
| Link death insurance | IMPLEMENTED_LOCAL | 保存時点のtitle/author/body kind/summary/excerpt/thumbnail/metadata source/fetched timeをExport/MCPに出し、保存時点情報の注意文を出す。 | Wayback/headless/browser external resolver remains out of repo default |
| OpenAI submission | MANUAL_STEP | 公式提出・review・production installは未実施。 | owner approval, deployed production endpoint, privacy/security review |

## Constraints
- `normalizedUrl` と `openUrl = normalizedUrl` の保存契約を変えない。
- shared-tag collaborator data はAI export/MCPに参加者情報として出さない。
- raw prompt、secret、token、local path、full fetched body はデフォルトで出さない。
- Receiptはローカル保存のみ。request/response sizeは正確値ではなくbucket化する。
- Login with ChatGPTはDocs only / 採用審査であり、本体ログインではない。
- YouTube/TikTok/X非公式動画DLはAI/Link death insuranceの解決策にしない。

## Validation
- Android: `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ExportRepositoryTest --tests jp.mimac.urlsaver.AiTransparencyPolicyTest --tests jp.mimac.urlsaver.AiTransparencyRepositoryTest --tests jp.mimac.urlsaver.LinkDeathInsuranceContractTest`
- iOS: `xcodebuild ... build-for-testing` and `test-without-building` on a dedicated simulator.
- Web: `npm run typecheck` under `web/admin`
- MCP static: `python3 scripts/verify_mcp_contract.py`

## Failure handling
repo内の実装/contract/test失敗は `NO_GO_INTERNAL` とする。CoreSimulator Busy/preflightのみなら環境要因として `NOT_VERIFIED` に分ける。production deploy、OpenAI submission、store submission、production secret投入、store/live再確認はManual stepsであり、repo内部ブロッカーに混ぜない。
