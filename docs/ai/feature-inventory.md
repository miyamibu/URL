# AI Feature Inventory

## Goal
`1prompt.docx` のAI透明化/個人リンク記憶要求を、現在の repo 実装状態に落として追跡する。

## Context
2026-07-18の現在のworking treeでは、ChatGPT手動ファイル共有のAndroid/iOS実装、共有前確認、既知パターン伏せ字、Privacy/Store/App Review文言、自動テストを確認済み。変更は未commit・未push。物理iPhoneは自作タグ選択、全出力preview、明示確認、ChatGPT用ZIP作成、iOS共有シートでChatGPT選択、通常トーク画面へのZIP添付、空の質問欄、未送信状態までUSB Appium/WDAで確認した。Android実機は未接続。DB破壊・store提出・deploy・production secret投入は行わない。production MCP/OAuth、OpenAI submission、store submission、production secret投入、store/live再確認はManual stepsとしてローカル実装/検証から分離する。

## Inventory
| Area | Status | Evidence | Remaining gate |
|---|---|---|---|
| AI-safe Export ZIP / JSON（通常エクスポートbaseline） | IMPLEMENTED_LOCAL_BASELINE | Android `ExportRepository.kt`, iOS `ExportArchiveBuilder.swift` が `schema.json`, `README_FOR_AI.md`, `redaction_report.json`, `publicSafeId`, `aiEligible`, excerpt/redaction, `savedSnapshotNotice` を出力する。 | 手動共有の自動契約検証は次行でGO。実機E2Eと公開証跡は別管理 |
| ChatGPT手動ファイル共有 | IMPLEMENTED_LOCAL / AUTOMATED_TEST_GO / IPHONE_DEVICE_VERIFIED_TO_COMPOSER | エクスポート画面で自作タグを選び、全対象/出力内容を確認し、専用ZIPを生成してOS共有へ渡す。アプリは質問入力・自動入力/送信・OpenAI API/OAuth・MCP/provider接続を行わない。Android/iOS自動テストはPASS。物理iPhoneで専用ZIP作成、共有先ChatGPT選択、通常トーク画面への添付、空の質問欄、未送信状態まで確認。 | Android実機は `NOT_VERIFIED`。commit/push、public deploy、store提出も未実施 |
| Raw fetched body exclusion | IMPLEMENTED_LOCAL | Export documentから `fetchedBody` を除外し、`bodyExcerpt` と `bodySummary` に限定。 | future opt-in body export requires new approval |
| Shared tag AI boundary | IMPLEMENTED_LOCAL | 共有タグ付きentryは `aiEligible=false` / `shared_tag_default_excluded`。Android/iOSのChatGPT sync operationは、eligibleなローカルURLについてもローカルタグ名だけを送る。 | MCP/Store/Supabase live は未検証の外部ゲート |
| ChatGPT personal-link sync card | IMPLEMENTED_LOCAL | Android/iOS の共有タグプロフィール画面にカードを表示。外部接続未設定時は「現在は利用できません」と表示し、操作不可とする。 | MCP/Store/Supabase live は未検証の外部ゲート |
| Preview / Receipt / Draft / Diff | IMPLEMENTED_LOCAL | Android RoomとiOS SQLiteにReceipt/Draft/Diffをローカル保存。Androidの `DEBUG && AI_TRANSPARENCY_ENABLED`、iOSの `DEBUG`/Info.plist flag に限定された Debug-only UI で、通常のRelease UIには出ない。Mock providerでdeterministic draft生成。Diff applyは明示confirm時のみ許可フィールドを更新。 | production AI provider wiring is future opt-in |
| Read-only MCP descriptors | IMPLEMENTED_LOCAL | `web/admin/lib/rinbamMcp.ts` が `search`, `fetch`, `rinbam.list_tags`, `rinbam.get_ai_receipt`, `rinbam.list_recent_saved_links` を定義。annotationsはread-only固定。 | deployed URL / OpenAI review are Manual steps |
| MCP data access | IMPLEMENTED_LOCAL | `URLSAVER_MCP_ENABLED=true` が無い限りendpointはdefault disabled。Bearer tokenを `auth.getUser` し、全queryで `user_id` を固定。rate limitあり。raw `fetched_body` は返さない。 | production OAuth/client registration is Manual step |
| Link death insurance | IMPLEMENTED_LOCAL | 保存時点のtitle/author/body kind/summary/excerpt/thumbnail/metadata source/fetched timeをExport/MCPに出し、保存時点情報の注意文を出す。 | Wayback/headless/browser external resolver remains out of repo default |
| OpenAI submission | MANUAL_STEP | 公式提出・review・production installは未実施。 | owner approval, deployed production endpoint, privacy/security review |

## Constraints
- `normalizedUrl` と `openUrl = normalizedUrl` の保存契約を変えない。
- shared-tag collaborator data はAI export/MCPに参加者情報として出さない。
- app-owned production secret/credential、raw prompt、full fetched bodyは出力しない。ユーザー由来の全出力項目では既知のemail/phone/token-like/Supabase/JWT/local-pathパターンを検出して伏せ字にするが、未知の秘密まで検出できるとは断言しない。手動共有前にユーザーが対象URLと出力内容を確認する。
- Receiptはローカル保存のみ。request/response sizeは正確値ではなくbucket化する。
- Login with ChatGPTはDocs only / 採用審査であり、本体ログインではない。
- YouTube/TikTok/X非公式動画DLはAI/Link death insuranceの解決策にしない。

## Validation
- Android: `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ExportRepositoryTest --tests jp.mimac.urlsaver.AiTransparencyPolicyTest --tests jp.mimac.urlsaver.AiTransparencyRepositoryTest --tests jp.mimac.urlsaver.LinkDeathInsuranceContractTest`
- iOS: `xcodebuild ... build-for-testing` and `test-without-building` on a dedicated simulator.
- iOS shared-tag boundary: `SharedTagStoreTests/testChatGptPersonalLinkPolicyKeepsLocalLinksAndExcludesSharedOnlyLinks`.
- Android/iOS ChatGPT handoff: local-tag OR選択、eligible-only preview/archive、既知パターン伏せ字、未知の秘密に関する警告と明示確認、0件拒否、専用filename、質問payloadなしを自動テストで検証済み。物理iPhoneはChatGPT通常トーク画面への専用ZIP添付まで確認済み。Android実機は未検証。
- Web: `npm run typecheck` under `web/admin`
- MCP static: `python3 scripts/verify_mcp_contract.py`

## Failure handling
repo内の実装/contract/test失敗、既知パターンの未伏せ字、共有前の未知secret警告/ユーザー確認欠落、Privacy/Store/App Review文言の不一致は `NO_GO_INTERNAL` とする。CoreSimulator Busy/preflightのみなら環境要因として `NOT_VERIFIED` に分ける。production deploy、OpenAI submission、store submission、production secret投入、store/live再確認はManual stepsであり、repo内部ブロッカーに混ぜない。
