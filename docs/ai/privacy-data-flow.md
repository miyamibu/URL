# AI Privacy And Data Flow

## Goal
AI-friendly export と read-only MCP で、外部へ出るデータと出さないデータを明示する。

## Context
りんばむはURL保存アプリであり、AI連携は補助的な外部利用導線である。Android/iOS の共有タグプロフィール画面にはChatGPT個人リンク同期カードを表示し、外部接続未設定時は「現在は利用できません」と表示して操作不可とする。

## Data Flow
| Flow | User action | Data sent | Data not sent by default |
|---|---|---|---|
| 通常Export / Backup ZIP・JSON | Export画面でユーザーがバックアップ/レビュー用に保存 | 標準manifest、通常のentry/tag ID、選択条件、保存データ。既存のbody全文除外は維持する | ChatGPT Handoff用のAI-safe契約ではない。通常ExportをAI-safeと呼ばず、AI共有へ使わない |
| ChatGPT manual handoff ZIP | Export画面で自作タグと対象URL/出力内容を確認し、未知の秘密がないことを明示確認してOS共有を実行 | eligibleなACTIVE/local URLのAI-safe export項目、`publicSafeId`、自作タグ名、保存時刻。既知のemail/phone/token-like/Supabase/JWT/local-pathパターンは伏せ字 | 質問文、ローカルentry/tag ID、full fetched body、raw prompt、app-owned production credentials、shared-tag entries/members、archived/pending-delete entries。未知の秘密は自動検出を保証しない |
| MCP search | Authenticated MCP request, endpoint explicitly enabled | ACTIVE-only matching saved-link summaries, local tags, publicSafeId, URL, author/body kind metadata | deleted/disabled/PENDING_DELETE/archived records, raw fetched_body, raw DB UUID, business-data writes, live URL refetch |
| MCP fetch | Authenticated MCP request by publicSafeId, endpoint explicitly enabled | one ACTIVE saved-link summary text, saved snapshot notice, metadata | deleted/disabled/PENDING_DELETE/archived records, raw fetched_body, raw prompt, business-data writes, live URL refetch |
| AI receipt | Internal/debug AI preview flow | action kind, destination, sent/blocked publicSafeIds, redaction profile, size buckets | raw prompts, raw bodies, tokens, attachments, exact request/response bytes |
| AI draft/diff | Internal/debug AI preview flow | user-visible draft body and proposed userTitle/memo diffs | automatic DB mutation; changes apply only after explicit confirmation |

## Constraints
- publicSafeId はAI/外部向けの不透明ID。DB主キーをそのまま出さない。
- shared-tag entries are AI-ineligible by default.
- Archived and pending-delete entries are AI-ineligible by default unless a future explicit flow opts them in.
- Production secrets stay in server or ignored config only.
- MCP is default disabled unless `URLSAVER_MCP_ENABLED=true`.
- MCP search/list use user-bound DB RPCs instead of an application-side latest-200/latest-500 scan. Fetch uses `(user_id, public_safe_id)` equality lookup. A distributed per-user rate-limit RPC fails closed when unavailable.
- MCP rejects unknown input fields, sanitizes outputs recursively, and returns no response body for JSON-RPC notifications.
- Receipt/Draft/Diff are local-only and must be cleared by local data/account deletion flows.
- AI透明化のentry/previewは Android の `DEBUG && AI_TRANSPARENCY_ENABLED`、iOS の `DEBUG` と Info.plist flag に限定された Debug-only UI で、通常のRelease UIには出さない。
- MCP/Store/Supabase live は未検証の外部ゲートであり、repo内のローカル実装/検証とは分離する。
- Login with ChatGPT is Docs only / adoption review; it is not the app login system.
- ChatGPT manual handoffは、端末内での選択/preview/ZIP生成とOS共有までであり、read-only MCP、ChatGPT個人リンク同期、production AI provider接続とは別経路。りんばむはChatGPTへloginせず、OpenAI API/OAuth/MCP/provider endpointを呼ばない。共有タグクラウドへサインイン中の場合は、共有対象を誤って含めないため、ZIP生成直前に既存の共有タグ状態をSupabaseから更新する。この同期にZIPや質問文は送らない。共有先の選択、質問入力、添付送信はユーザーが共有先アプリで行う。
- Google Doc第13章の34項目は、添付後にChatGPTへ依頼できる活用例であって、りんばむ内の質問欄・自動実行機能ではない。りんばむの責務は自作タグ選択、対象/出力内容確認、ZIP生成、OS共有に限る。
- ユーザーが `ChatGPTに送る` を押すまでは、ZIPは端末内の一時artifactに留まる。AndroidでChatGPTへの直接共有を開始した時点、またはOS共有画面で共有先を選んだ時点から、その共有先へ一時ファイルの読み取り権限が渡り、共有先のprivacy policyとdata handlingが適用される。りんばむは、共有先アプリ内で添付が最終送信されたかを観測・保証しない。
- YouTube/TikTok/X video download is not an AI or MCP data path.

## Validation method
- Export tests assert `fetchedBody` is absent and redaction artifacts exist.
- `scripts/verify_mcp_contract.py` fails on forbidden Supabase write calls and missing read-only annotations.
- Web typecheck must pass before deploying MCP code.
- Android/iOS AI transparency tests assert Receipt metadata-only storage, bucketed sizes, separate Draft storage, and confirm-gated Diff apply.
- ChatGPT手動共有を含むbinaryを公開する前に、public privacy policyとApp Review notesには、ユーザー主導の外部共有、既知パターンの伏せ字、未知の秘密の共有前確認、API/OAuthなしを説明する。Google Play Data Safety/App Store Privacyのform回答は、その時点の公式定義と提出binaryをownerが確認し、ユーザー主導のOS共有を自動的にdeveloper collection/sharingと断定しない。

## Failure handling
raw body/app-owned secret、既知のsecret-likeパターンの未伏せ字、未知secret警告/明示確認の欠落がある場合は release blocker とし、該当archive/API responseを共有せず `NO_GO_INTERNAL` とする。
