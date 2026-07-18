# Privacy Policy And Store Disclosure Checklist

## Goal
Keep Privacy Policy, Google Play Data Safety, App Store Privacy Labels, and OpenAI connector disclosures aligned with the actual implementation.

## AI Data Boundary

| Category | Treatment |
|---|---|
| Data that may leave the app through manual handoff | After local-tag selection, preview, and explicit confirmation, the user can pass an eligible ZIP through the OS share surface. It can contain redacted URL/title/author/tag/collection, memo/body excerpts, and saved snapshot metadata. The user chooses ChatGPT or another recipient. |
| Data excluded from manual handoff | shared-tag-derived URLs, archived entries, pending-delete entries, raw `fetchedBody`, attachments, app-owned production credentials, raw prompts, raw DB ids. Known email/phone/token-like/Supabase/JWT/local-path patterns are redacted; unknown secret formats are not guaranteed and require user review. |
| Receipt | Local metadata only. Stores provider/model/action, source IDs, redaction flags, and bucketed request/response size. |
| Draft | Local-only user-visible AI proposal body or mock provider result. Not applied to main DB without explicit confirmation. |
| Diff proposal | Local apply-before-change proposal. Apply updates only allowed fields after explicit confirmation. |
| Deletion | Local/account deletion must remove AI receipt/draft/diff local data. |
| Manual ChatGPT file handoff | Local selection/preview/ZIP plus OS share only. No question field, automatic input/send, OpenAI API/OAuth/login, MCP, or provider endpoint. Chapter 13's 34 items are examples for the receiving ChatGPT conversation, not app-executed functions. |
| MCP / ChatGPT personal-link sync | Separate network integration. Read-only; default disabled; auth/user boundary required; raw body default excluded; production deploy/OAuth/provider review are separate gates. |
| OpenAI / third-party recipient | For manual handoff, the user selects the external recipient and that recipient's terms apply after sharing. Direct app-to-provider processing applies only if a separately gated MCP/provider/API integration is enabled. |

## Disclosure Decision For Manual Handoff

- Public Privacy Policy and App Review notes: `REQUIRED_BEFORE_SUBMISSION` when `ChatGPTに聞く` is visible in the submitted binary. Explain user-directed transfer, included/excluded data, known-pattern redaction, unknown-secret user review, recipient policy, and no API/OAuth/automatic send.
- In-app pre-transfer disclosure: `REQUIRED`. Show the redacted preview and require explicit confirmation that the user checked for unknown secrets before ZIP generation/share.
- Google Play Data Safety / App Store Privacy form classification: `NEEDS_CURRENT_CONSOLE_AND_OWNER_REVIEW`. A user-initiated OS share is not automatically classified here as developer collection/sharing; check the platform's current definitions and the exact submitted binary instead of guessing.
- Production MCP/provider disclosure: separate from manual handoff and still required only when that network integration is enabled.
- Local implementation tests, pre-transfer disclosure, and Privacy/App Review source text are aligned for the manual handoff. Final submission remains `NO_GO_INTERNAL`: editing the local web source does not update the live policy without an approved deploy/live recheck, and the current store-form classification still needs owner review against the exact signed binary.

## Google Play Data Safety

- [ ] Saved URLs and tags: disclose according to release mode.
- [ ] Account/auth data: disclose if cloud sync, contact support, or subscriptions are enabled.
- [ ] Purchases/subscriptions: disclose if Play Billing is active.
- [ ] App activity/content: disclose selected saved URL metadata if AI/MCP feature is enabled.
- [ ] Manual ChatGPT handoff: review the current Data Safety definitions for user-initiated OS sharing; record the console answer and rationale for the exact submitted binary.
- [ ] Data deletion: confirm account deletion and local deletion behavior.
- [ ] Stop if Data Safety excludes a category that the build actually collects or sends.

## App Store Privacy Labels

- [ ] User content: saved URLs, memos, tags where applicable.
- [ ] Identifiers/account data: only if account/cloud features enabled.
- [ ] Purchases: if StoreKit subscriptions enabled.
- [ ] Diagnostics: only if actual diagnostics collection exists.
- [ ] Manual ChatGPT handoff: review the current App Privacy definitions for user-directed OS sharing; record the console answer and rationale for the exact submitted binary.
- [ ] Direct third-party processing: include OpenAI/AI provider when production MCP/provider/API integration is enabled.
- [ ] Stop if labels imply no third-party processing while MCP/OpenAI connector is enabled.

## User-facing Disclosure Draft

> `ChatGPTに聞く` は、選択した自作タグに該当する対象を端末内で確認し、ZIPを作って共有先を開く手動機能です。共有タグクラウドへサインイン中の場合は、共有対象の誤混入を防ぐため、ZIP生成前に既存の共有タグ状態を更新しますが、ZIPや質問文をSupabaseへ送りません。既知のメールアドレス、電話番号、token風文字列等は伏せ字にしますが、未知の秘密をすべて検出できる保証はありません。内容を確認してから共有してください。りんばむは質問の入力・自動送信、OpenAI API/OAuth/MCP接続を行いません。Androidの直接共有開始時またはOS共有先の選択時から共有先へ一時ファイルの読み取り権限が渡り、その共有先の規約とプライバシーポリシーが適用されます。りんばむは共有先アプリ内の最終送信を観測しません。

## App Review Notes Draft

- りんばむ saves URLs for later reopening and supports local organization by tags/archive/export.
- AI Transparency and MCP/provider sync remain separately release-gated. The manual `ChatGPTに聞く` export entry can be visible independently because it performs local selection/preview/ZIP creation and opens the OS share surface only.
- Manual ChatGPT handoff does not include a question field, automatic input/send, OpenAI API/OAuth/login, or MCP/provider connection. Chapter 13's 34 items are receiving-side ChatGPT usage examples, not 34 app-executed features.
- AI-safe Export excludes raw fetched page body, app-owned production credentials, attachments, raw prompts, and raw DB IDs. Known sensitive patterns are redacted, while the user must review the preview for unknown secret formats before sharing.
- MCP integration is read-only, disabled by default, requires authentication, and returns only the authenticated user's eligible saved-link summaries.
- Shared-tag-derived URLs are excluded from AI/MCP eligibility by default.
- Receipt/Draft/Diff data is local-only and deleted with local/account deletion.

## Stop Conditions

- Store forms omit active cloud sync, purchases, contact support, or AI/MCP data processing.
- Public privacy policy contradicts actual build behavior.
- The submitted build exposes manual handoff without pre-transfer preview, unknown-secret warning/confirmation, or current Privacy/App Review disclosure.
- Store form answers classify user-directed OS sharing without checking the platform's current definitions and the exact submitted binary.
- Any review note claims production OpenAI/MCP availability before deployment and approval.
- Any screenshot/log includes secret values.
