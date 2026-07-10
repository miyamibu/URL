# AI Privacy And Data Flow

## Goal
AI-friendly export と read-only MCP で、外部へ出るデータと出さないデータを明示する。

## Context
りんばむはURL保存アプリであり、AI連携は補助的な外部利用導線である。通常UIのChatGPT同期設定は非表示。

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
- MCP is default disabled unless `URLSAVER_MCP_ENABLED=true`.
- Receipt/Draft/Diff are local-only and must be cleared by local data/account deletion flows.
- Login with ChatGPT is Docs only / adoption review; it is not the app login system.
- YouTube/TikTok/X video download is not an AI or MCP data path.

## Validation method
- Export tests assert `fetchedBody` is absent and redaction artifacts exist.
- `scripts/verify_mcp_contract.py` fails on forbidden Supabase write calls and missing read-only annotations.
- Web typecheck must pass before deploying MCP code.
- Android/iOS AI transparency tests assert Receipt metadata-only storage, bucketed sizes, separate Draft storage, and confirm-gated Diff apply.

## Failure handling
raw body、secret、token、local pathが出力に混ざった場合は release blocker とし、該当archive/API responseを共有しない。
