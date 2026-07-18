# Store Listing Draft

## Goal
App Store / Google Play へ次回提出する文言を、現在の source baseline（Android `1.0.15 (versionCode=18)` / iOS `1.0.15 (build=16)`）と、実際に選択した release mode に一致させる。

## App Identity
- App name: りんばむ
- Subtitle / short description: 共有したURLをあとで見返すための軽量保存アプリ。
- Category: Productivity
- Release posture: release mode dependent for shared-tag cloud/account sign-in; ads disabled, in-app subscriptions enabled, no third-party analytics, no third-party crash reporting.
- Android package name: `jp.miyamibu.urlalbum`
- iOS bundle ID: `com.mibu.codebridge.ios`
- Support contact: `miyamibu@privaterelay.appleid.com`
- Privacy policy URL candidate: `https://miyamibu.xyz/privacy/`
- Account deletion URL: required for this cloud-enabled release. Use the deployed public deletion route that matches `web/invite-link/account-deletion/index.html`.

## Current Release Branch Decision
Current source baseline is Android `1.0.15 (versionCode=18)` and iOS `1.0.15 (build=16)`, but store/live submission state was not reverified in this document. Do not reuse the historical `1.0.11` submission state as current proof.

- Android release mode depends on `release.shared.tag.cloud.enabled` and release Supabase values from local/env configuration.
- iOS defaults to tracked local-only xcconfig; cloud-sharing Archive/TestFlight must pass ignored `ios/Config/URLSaverSecrets.xcconfig` explicitly.
- Ads, external analytics, and third-party crash reporting remain disabled. Google Play Billing / StoreKit subscriptions are enabled for paid plans.
- Store forms must disclose account sign-in, shared-tag cloud sync/collaboration, invite sync, and contact support processing only when those features are enabled in the submitted binary. The current platform definitions must be reviewed before classifying a user-initiated OS share as developer collection/sharing.
- Manual ChatGPT handoff implementation, pre-transfer confirmation, Privacy/App Review local wording, and automated validation are aligned. Final release remains `NO_GO_INTERNAL` because signed upload artifacts/device E2E are incomplete; store/live submission and public-policy deployment remain separate external gates.

## Short Description
SNSやメッセージで見つけたURLを保存し、あとで一覧・詳細・アーカイブから開き直せます。

## Full Description
りんばむは、共有メニューや手入力からURLを保存し、あとで見返しやすく整理するためのアプリです。

主な機能:
- 共有メニューからURLを保存
- 複数URLの共有保存
- 手動入力でURLを追加
- 保存済みURLの一覧、詳細、アーカイブ表示
- 重複URLの検出
- 削除猶予とUndo
- ページタイトル、説明、サムネイルなどのmetadata取得
- JSON / ZIP形式のエクスポート
- 自作タグから対象と伏せ字後の内容を確認し、ChatGPT向けZIPを作ってOS共有（質問入力・自動送信・OpenAI API/OAuth連携なし）
- サインインして共有タグをクラウド同期
- 招待リンクで共有タグに参加
- アプリ内問い合わせ送信

次回提出版では、選択した release mode に応じて共有タグクラウド、アカウント機能、Standard / Pro のアプリ内サブスクリプション購入を説明します。広告表示、外部アナリティクス、第三者クラッシュ収集サービスは現時点のrelease構成では使用しません。

## Keywords
URL, bookmark, share, later, archive, save, link, productivity

## Screenshot Plan
1. Main list: saved URLs and service filters.
2. Manual add: URL input sheet.
3. Detail: saved URL, title, memo, metadata.
4. Archive: archived links and restore flow.
5. Export: JSON / ZIP options and manual `ChatGPTに聞く` preview/confirmation.
6. Privacy info: local storage, metadata fetch, cloud sync, contact support, and user-directed external sharing explanation.

## Review Notes Draft
- The app saves URLs provided by the user through share actions or manual input.
- Saved URLs, titles, memos, tags, and metadata are stored locally on the device.
- When the user signs in and uses shared tags, shared-tag URLs and shared-tag metadata are synced through Supabase for collaboration.
- The app may request web pages and public oEmbed endpoints to fetch page metadata.
- The contact-support form sends the user's contact email, name, and inquiry body to the configured support endpoint for email delivery.
- `ChatGPTに聞く` lets the user select local tags, review the eligible URLs and redacted fields, create a ZIP on device, and open the OS share surface. It has no question field, automatic input/send, OpenAI API/OAuth/login, MCP, or provider connection; the user chooses the recipient and sends the file.
- Known email/phone/token-like/Supabase/JWT/local-path patterns are masked, but unknown secret formats may remain. The app warns the user and requires confirmation after preview. Chapter 13's 34 items are examples of what the receiving ChatGPT conversation can do, not 34 app-executed features.
- If the user is signed in to shared-tag cloud, the app refreshes existing shared-tag state before creating the ZIP so shared content stays excluded; the ZIP and question text are not sent to Supabase. Starting direct share or choosing an OS-share recipient gives that recipient temporary file access, and Rinbam does not observe the recipient app's final send.
- Paid plans can be purchased through Google Play Billing / StoreKit. Store purchase identifiers are used to grant app entitlements.
- No ads, external analytics, or third-party crash reporting are enabled in this release.

## Store Console Input Sheet

| Field | Google Play draft | App Store draft | Status |
|---|---|---|---|
| App name | りんばむ | りんばむ | DONE |
| Short description / subtitle | 共有したURLをあとで見返すための軽量保存アプリ。 | URLをあとで見返す軽量保存アプリ | DONE |
| Full description / description | Use `Full Description` above. | Use `Full Description` above, shortened if App Store field limits require it. | DONE |
| Category | Productivity | Productivity | DONE |
| Support email | `miyamibu@privaterelay.appleid.com` | `miyamibu@privaterelay.appleid.com` | DONE |
| Privacy policy URL | `https://miyamibu.xyz/privacy/` | `https://miyamibu.xyz/privacy/` | LOCAL_SOURCE_UPDATED_DEPLOY_NOT_RUN: 2026-06-29 live proof covers older billing wording only; deploy approval and current live handoff-disclosure recheck remain. |
| Marketing URL | optional | optional | NOT_APPLICABLE |
| Review contact | Use developer account owner contact in console | Use App Store Connect review contact | NEEDS_USER_ACTION |
| Review notes | Use `Review Notes Draft` above. Provide a test account if store review requires cloud-sharing sign-in. | Use `Review Notes Draft` above. Provide a test account if store review requires cloud-sharing sign-in. | DRAFT_UPDATED / NEEDS_CURRENT_BINARY_RECHECK |
| Keywords | URL, bookmark, share, later, archive, save, link, productivity | URL, bookmark, share, later, archive, save, link | DONE |
| Copyright | Developer legal name required from account | Developer legal name required from account | NEEDS_USER_ACTION |

## Age Rating Draft
- User-generated content: no public feed; users save their own URLs.
- Web access: the app opens user-saved URLs in an external browser and fetches metadata from user-provided URLs.
- Purchases: Standard / Pro subscriptions are available as in-app purchases.
- Ads: none for this release.
- Account creation/sign-in: available only when the submitted binary enables shared-tag cloud sync.

## Done When
- Final screenshots match the submitted build.
- App Store / Google Play wording matches the actually submitted Android `1.0.15 (versionCode=18)` / iOS `1.0.15 (build=16)` or later binary.
- Privacy and Data safety answers match the submitted binary.
- The live Privacy Policy and App Review notes explain manual user-directed sharing, redaction limits, and no API/OAuth/automatic send; current store-form answers and rationale are owner-reviewed for the exact binary.
