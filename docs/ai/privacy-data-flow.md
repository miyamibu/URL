# AI Privacy And Data Flow

## Goal
AI-friendly export と read-only MCP で、外部へ出るデータと出さないデータを明示する。

## Context
りんばむはURL保存アプリであり、AI連携は補助的な外部利用導線である。Android/iOS の共有タグプロフィール画面にはChatGPT個人リンク同期カードを表示し、外部接続未設定時は「現在は利用できません」と表示して操作不可とする。

## Data Flow
| Flow | User action | Data sent | Data not sent by default |
|---|---|---|---|
| Export ZIP / JSON | Export画面でユーザーが共有/保存 | title, original/normalized/open URL, provider permalink, author, body summary/excerpt, memo excerpt, tags, timestamps, metadata status | full fetched body, raw prompt, secrets, tokens, local paths, shared-tag members |
| MCP search | Authenticated MCP request, endpoint explicitly enabled | matching saved-link summaries, tags, publicSafeId, URL, author/body kind metadata | raw fetched_body, raw DB UUID, write operations, live URL refetch |
| MCP fetch | Authenticated MCP request by publicSafeId, endpoint explicitly enabled | one saved-link summary text, saved snapshot notice, metadata | raw fetched_body, raw prompt, write operations, live URL refetch |
| AI receipt | Internal/debug AI preview flow | action kind, destination, sent/blocked publicSafeIds, redaction profile, size buckets | raw prompts, raw bodies, tokens, attachments, exact request/response bytes |
| AI draft/diff | Internal/debug AI preview flow | user-visible draft body and proposed userTitle/memo diffs | automatic DB mutation; changes apply only after explicit confirmation |

## Constraints
- publicSafeId はAI/外部向けの不透明ID。DB主キーをそのまま出さない。
- shared-tag entries are AI-ineligible by default.
- Archived and pending-delete entries are AI-ineligible by default unless a future explicit flow opts them in.
- Production secrets stay in server or ignored config only.
- MCP is default disabled unless both `URLSAVER_MCP_ENABLED=true` and `URLSAVER_PERSONAL_LINK_SNAPSHOT_PROTOCOL_ENABLED=true`; the latter remains false until the explicit snapshot protocol is deployed and verified.
- Receipt/Draft/Diff are local-only and must be cleared by local data/account deletion flows.
- AI透明化のentry/previewは Android の `DEBUG && AI_TRANSPARENCY_ENABLED`、iOS の `DEBUG` と Info.plist flag に限定された Debug-only UI で、通常のRelease UIには出さない。
- MCP/Store/Supabase live は未検証の外部ゲートであり、repo内のローカル実装/検証とは分離する。
- Login with ChatGPT is Docs only / adoption review; it is not the app login system.
- YouTube/TikTok/X video download is not an AI or MCP data path.

## Validation method
- Export tests assert `fetchedBody` is absent and redaction artifacts exist.
- `scripts/verify_mcp_contract.py` fails on forbidden Supabase write calls and missing read-only annotations.
- Web typecheck must pass before deploying MCP code.
- Android/iOS AI transparency tests assert Receipt metadata-only storage, bucketed sizes, separate Draft storage, and confirm-gated Diff apply.

## Failure handling
raw body、secret、token、local pathが出力に混ざった場合は release blocker とし、該当archive/API responseを共有しない。
