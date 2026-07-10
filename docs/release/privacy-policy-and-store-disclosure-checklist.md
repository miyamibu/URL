# Privacy Policy And Store Disclosure Checklist

## Goal
Keep Privacy Policy, Google Play Data Safety, App Store Privacy Labels, and OpenAI connector disclosures aligned with the actual implementation.

## AI Data Boundary

| Category | Treatment |
|---|---|
| Data that may be sent to AI | Only user-selected saved URL metadata, title, host, service type, memo excerpt, body summary/excerpt, tags eligible for local AI use, and saved snapshot metadata. |
| Data not sent to AI by default | shared-tag-derived URLs, archived entries, pending-delete entries, raw `fetchedBody`, attachments, tokens, refresh tokens, Supabase secrets, raw prompts, raw DB ids. |
| Receipt | Local metadata only. Stores provider/model/action, source IDs, redaction flags, and bucketed request/response size. |
| Draft | Local-only user-visible AI proposal body or mock provider result. Not applied to main DB without explicit confirmation. |
| Diff proposal | Local apply-before-change proposal. Apply updates only allowed fields after explicit confirmation. |
| Deletion | Local/account deletion must remove AI receipt/draft/diff local data. |
| MCP | Read-only; auth required; user boundary required; raw body default excluded. |
| OpenAI / third-party provider | Treat as external processor only when the user explicitly tests/enables selected-link AI workflows. |

## Google Play Data Safety

- [ ] Saved URLs and tags: disclose according to release mode.
- [ ] Account/auth data: disclose if cloud sync, contact support, or subscriptions are enabled.
- [ ] Purchases/subscriptions: disclose if Play Billing is active.
- [ ] App activity/content: disclose selected saved URL metadata if AI/MCP feature is enabled.
- [ ] Data deletion: confirm account deletion and local deletion behavior.
- [ ] Stop if Data Safety excludes a category that the build actually collects or sends.

## App Store Privacy Labels

- [ ] User content: saved URLs, memos, tags where applicable.
- [ ] Identifiers/account data: only if account/cloud features enabled.
- [ ] Purchases: if StoreKit subscriptions enabled.
- [ ] Diagnostics: only if actual diagnostics collection exists.
- [ ] Third-party data sharing: include OpenAI/AI provider only when production AI provider is enabled.
- [ ] Stop if labels imply no third-party processing while MCP/OpenAI connector is enabled.

## User-facing Disclosure Draft

> AI整理機能はテスト中です。選択した保存URLだけを対象に、要約・タグ提案・差分案を作成します。共有タグは原則除外されます。本文全文やプロンプト、トークンは保存しません。

## App Review Notes Draft

- りんばむ saves URLs for later reopening and supports local organization by tags/archive/export.
- AI-related functionality is release-gated. Normal public UI does not expose unapproved AI entry when the feature flag is off.
- AI-safe Export uses redacted metadata and excerpts. It does not export raw fetched page body, tokens, refresh tokens, attachments, Supabase secrets, or raw prompts.
- MCP integration is read-only, disabled by default, requires authentication, and returns only the authenticated user's eligible saved-link summaries.
- Shared-tag-derived URLs are excluded from AI/MCP eligibility by default.
- Receipt/Draft/Diff data is local-only and deleted with local/account deletion.

## Stop Conditions

- Store forms omit active cloud sync, purchases, contact support, or AI/MCP data processing.
- Public privacy policy contradicts actual build behavior.
- Any review note claims production OpenAI/MCP availability before deployment and approval.
- Any screenshot/log includes secret values.
