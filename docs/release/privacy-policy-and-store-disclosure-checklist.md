# Privacy Policy And Store Disclosure Checklist

## Goal
Keep Privacy Policy, Google Play Data Safety, App Store Privacy Labels, and OpenAI connector disclosures aligned with the actual implementation.

## AI Data Boundary

| Category | Treatment |
|---|---|
| Data that may leave the app through manual handoff | After local-tag selection, preview, and explicit confirmation, the user can pass an eligible ZIP through the OS share surface. It can contain redacted URL/title/author/tag, a legacy compatibility-only collection field where old data requires it, memo/body excerpts, and saved snapshot metadata. The legacy field does not expose an active Collection UI. The user chooses ChatGPT or another recipient. |
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
- Google Play Data Safety / App Store Privacy treatment of the user-initiated OS share itself still requires exact-binary/current-definition review. This narrow question does not excuse the separate cloud account, shared-tag, support, and purchase flows that are transmitted off device and must be declared.
- Production MCP/provider disclosure: separate from manual handoff and still required only when that network integration is enabled.
- Local implementation tests, pre-transfer disclosure, and the repo-local Privacy Policy source are aligned for the manual handoff. The 2026-08-21 public page is stale versus that source and must be redeployed/reverified before it can be called aligned. Exact-binary evidence separates the stores: the signed iOS 1.0.17 (19) archive is local-only, so its `データの収集なし` answer is not a confirmed privacy mismatch; its product description is inaccurate because it advertises disabled cloud/sync/invite features. Google Play remains a confirmed Data safety Major for the cloud-enabled signed Android v21 candidate. No console value was changed.

## Current Public Disclosure Recheck (2026-08-21 JST)

| Surface | Direct current observation | Release state |
|---|---|---|
| App Store | Official app ID `6771251450` shows `1.0.17`, released `2026-07-28`, `7.2 MB`, `EN 英語`, cloud/shared-tag sync/invitation copy, and App Privacy `データの収集なし`. | Privacy remains supported by the matching local-only `1.0.17 (19)` archive. Listing functionality remains a confirmed Major; localization remains a separate Minor. |
| Google Play | Official package `jp.miyamibu.urlalbum` shows `1.0.17`, updated `2026/08/05`, and cloud-sync copy. Public Data safety says Web browsing history is shared for app functionality, no data is collected, and data is encrypted in transit. No public deletion display was found in the returned Data safety text. | `CONFIRMED_MAJOR / BLOCKED_EXTERNAL`; no Console answer was changed in this pass. |
| Public policy | The public page is HTTP 200 but lacks the repo-local 2026-08-13 disclosure of the persistent sync-source identifier, non-ephemeral operation/result records, and hashed-IP pseudonymous audit identifier. Public SHA-256 `15a1e8d5cc11a3be0227e5e2f063d486b9264856fcf6e0d94c353fcabb2eb953`; local source SHA-256 `f96ba1f6ee11119e11053767119ae0b48f6fae4e6552e79de6728ef7a0e9686f`. | `PUBLIC_CONTENT_STALE_VS_REPO / BLOCKED_EXTERNAL`; deploy/reverify is required. |
| Account deletion | Public and repo-local HTML are byte-identical, SHA-256 `16cbc0f0ecf05f5380afecff98c4919f66f7d337a4625e240f7b6ac270d56be6`. | `VERIFIED` for source/public correspondence only. |

### External correction packet (not executed)

1. **Public policy:** deploy `web/invite-link` to the existing linked Vercel project `invite-link` and production domain `https://miyamibu.xyz`; do not create another project. After deploy, verify HTTP 200, all three missing disclosure concepts, source/body correspondence, the unchanged account-deletion page, reset/invite/security contracts, and `scripts/verify_public_web_release.sh`. Preserve deployment ID, source revision, time, alias, and hashes.
2. **Google Play form:** first export/capture the current Data safety form for package `jp.miyamibu.urlalbum`, then import `artifacts/full-go-audit/2026-08-13/google-play-data-safety-proposed.csv` (SHA-256 `a98bbb4d8ffa09f264a54cf1efc04248f3dd51faedc47b8738cb1ba5422a91c8`; UTF-8 BOM; schema `Question ID`, `Response ID`, `Response value`, `Answer requirement`, `Human-friendly question label`; 782 response rows). Import is an overwrite operation; do not save or submit until all answers are reviewed against the exact distributed v21 artifact.
3. **Eight selected Play categories:** Name; Email address; User IDs; Purchase history; Web browsing history; Other user-generated content; Other actions; Device or other IDs. Confirm collection/sharing, non-ephemeral treatment, required/optional state, purposes, linkage, encryption, account creation, and deletion for each applicable flow. The exact row-level draft remains in the CSV and S2 raw report.
4. **Two interpretation gates:** the owner must document whether each Supabase/support/billing processor qualifies under Google's current service-provider exception, and whether participant-visible shared-tag/manual OS-share flows qualify under the current user-initiated-action exception. Do not apply either exception globally or infer it from provider names alone.
5. **Play completion evidence:** after owner review, save, review, and submit; retain sanitized before/after screenshots and the exact artifact version/code; then re-read the official public listing and Data safety detail until the public state matches the approved form. Store authentication, 2FA, overwrite acceptance, contractual interpretation, save, and submit are owner-only actions.
6. **App Store listing:** target app ID `6771251450`, current local-only `1.0.17 (19)`. Use only the local-only short description/full description/review notes in `store-listing-draft.md`; remove cloud/sync/invite/purchase promises. If current version metadata is locked, create the next version and attach an exact matching local-only binary rather than claiming the released page was edited. Resolve `EN 英語` only after a signed archive contains Japanese localization. Authentication/2FA, version creation, metadata save, build selection, agreements, and review submission remain owner actions.

## Confirmed Public Disclosure Mismatch (2026-08-13 JST)

| Platform | Public observation | Contradicting evidence | Final state |
|---|---|---|---|
| App Store | Official product URL shows version `1.0.17`, cloud/shared-tag functionality, and App Privacy `データの収集なし`. The matching signed archive is `1.0.17 (19)`, bundle `com.mibu.codebridge.ios`, Team `8R3B5675ZJ`, with shared-tag cloud disabled and Supabase/contact-support values empty. | Apple's definition requires retained off-device data to be declared, but the matching archive evidence does not establish those cloud flows in this binary. The prior privacy Major is withdrawn. The public description still promises cloud/sync/invite functionality the archive cannot provide. | `APP_PRIVACY_FALSE_POSITIVE / LISTING_ACCURACY_CONFIRMED_MAJOR / BLOCKED_EXTERNAL` |
| Google Play | Official product page describes cloud synchronization while Data safety says no data is collected. Authenticated Console read by C0 shows Step 2 `collect/share=yes`, encryption `yes`; Step 5 previews `Shared: Web browsing history`, `Collected: no data collected`; account creation is `does not allow`, and the deletion choice is unanswered. | The Console steps conflict with each other, and the account-creation answer conflicts with the Supabase sign-up implementation. No field was changed or saved. Google requires complete and accurate declarations for app and SDK collection/sharing. | `CONFIRMED_MAJOR / BLOCKED_EXTERNAL / CONSOLE_READ_ONLY_VERIFIED` |

Official definition sources:

- Apple App Privacy Details: https://developer.apple.com/app-store/app-privacy-details/
- Google Play Data safety: https://support.google.com/googleplay/android-developer/answer/10787469

This is a technical release classification, not legal advice. Console owners must confirm the exact signed binaries and current platform forms before saving changes.

## Google Play Data Safety Console Draft

- Current authenticated form state was read on 2026-08-13 for the canonical Play app. It is not an access-inaccessible gate: the remaining gate is correction, save/review, and post-change public verification. No mutation was performed in this review.
- Resolve the current internal form contradiction first: Step 2 says collection/sharing `Yes`, but Step 5 says no data is collected while separately listing shared Web browsing history.
- Change the account-creation answer from `does not allow` to the current option that states the app allows account creation, then complete the deletion mechanism answers using the in-app and public deletion routes. The current deletion choice is unanswered.
- [ ] Set `Data collected = Yes` for a cloud-enabled release; do not retain the current public `No data collected` answer.
- [ ] Personal info: declare email address and user/account identifier for Supabase Auth; declare name/email when the optional support form is submitted.
- [ ] User content: declare shared-tag names, saved/shared URLs, membership/invitation collaboration data, and support inquiry body under the closest current console categories.
- [ ] Financial info / purchase history: declare store product, purchase token/transaction, and entitlement processing if Play Billing is active.
- [ ] Purpose: select app functionality/account management for auth, sync/collaboration, support delivery, purchase verification, and entitlement management; do not select analytics/advertising without implementation evidence.
- [ ] Required versus optional: cloud account/shared-tag/purchase data is required only for those opted-in features; support submission is user-initiated/optional. Record the per-category answer.
- [ ] Sharing: assess Supabase/email-provider processing and participant-visible shared-tag data against Google's current service-provider and user-action definitions. Do not assert blanket `not shared` without recording the applicable rationale.
- [ ] Security: confirm in-console whether all declared collected data is encrypted in transit; do not infer this solely from some HTTPS endpoints.
- [ ] Manual ChatGPT handoff: review the current Data Safety definitions for user-initiated OS sharing; record the console answer and rationale for the exact submitted binary.
- [ ] Data deletion: enter/verify the public account-deletion URL and confirm in-app deletion behavior for the exact cloud account.
- [ ] SDK review: include Supabase, billing, and any delivery/provider SDK or service behavior in the declaration review.
- [ ] Save before/after screenshots, review timestamp, submitted binary version/code, and public-page recheck without exposing account data.
- [ ] Stop if Data Safety excludes a category that the build actually collects or sends.

## App Store Privacy And Listing Console Draft

- [x] Exact-binary check: signed 1.0.17 (19) archive is local-only; do **not** change `Data Not Collected` solely from source or the conditional Privacy Policy.
- [ ] Correct the current product description by removing cloud/shared-tag sync/invitation claims, or submit a cloud-enabled replacement build and then use the cloud data-type draft below.
- [ ] Future cloud-enabled build only: declare Contact Info, User Content, Identifiers, Purchases, App Functionality purposes, and linked-to-user treatment from the exact archive.
- [ ] Tracking: `No` only while no cross-app/site tracking or advertising profile is implemented.
- [ ] Diagnostics: only if actual diagnostics collection exists.
- [ ] Manual ChatGPT handoff: review the current App Privacy definitions for user-directed OS sharing; record the console answer and rationale for the exact submitted binary.
- [ ] Direct third-party processing: include OpenAI/AI provider when production MCP/provider/API integration is enabled.
- [ ] Save before/after screenshots, review timestamp, submitted version/build, and public product-page recheck without exposing account data.
- [ ] Stop if labels omit active collection in the exact binary, or if product copy advertises features disabled in that binary.

## App Store Localization Follow-up (Confirmed Packaging Minor)

- [ ] Public page currently reports `EN 英語`.
- [x] Source inspection: Xcode `developmentRegion=ja`, `knownRegions=(ja, Base)`; app and Share Extension `CFBundleLocalizations=[ja]`.
- [x] Signed archive inspection: `CFBundleDevelopmentRegion=en`, no `CFBundleLocalizations`, no `.lproj`; public `EN 英語` matches the archive.
- [ ] Produce and inspect a new signed archive in which Japanese localization survives packaging, then update/recheck the public page. Keep this separate from the listing Major.

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
- Google Play Data safety says no data is collected while the cloud-enabled Android binary sends and retains declared data off device.
- App Store product copy advertises cloud/sync/invite functionality while the matching signed archive disables it.
- Public privacy policy contradicts actual build behavior.
- The submitted build exposes manual handoff without pre-transfer preview, unknown-secret warning/confirmation, or current Privacy/App Review disclosure.
- Store form answers classify user-directed OS sharing without checking the platform's current definitions and the exact submitted binary.
- Any review note claims production OpenAI/MCP availability before deployment and approval.
- Any screenshot/log includes secret values.
