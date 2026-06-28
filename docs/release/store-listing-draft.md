# Store Listing Draft

## Goal
App Store / Google Play へ提出する `1.0.11` の文言を、提出 binary の cloud 有効構成と一致させる。

## App Identity
- App name: URL Saver
- Subtitle / short description: 共有したURLをあとで見返すための軽量保存アプリ。
- Category: Productivity
- Release posture: shared-tag cloud enabled, ads disabled, in-app subscriptions enabled, account sign-in available for sync/collaboration, no third-party analytics, no third-party crash reporting.
- Android package name: `jp.miyamibu.urlalbum`
- iOS bundle ID: `com.mibu.codebridge.ios`
- Support contact: `miyamibu@privaterelay.appleid.com`
- Privacy policy URL candidate: `https://miyamibu.xyz/privacy/`
- Account deletion URL: required for this cloud-enabled release. Use the deployed public deletion route that matches `web/invite-link/account-deletion/index.html`.

## Current Release Branch Decision
Store release `1.0.11` is cloud-enabled:

- Android release uses `release.shared.tag.cloud.enabled=true` with production Supabase values from local release configuration.
- iOS Release uses `URLSAVER_SHARED_TAG_CLOUD_ENABLED=true` with production Supabase and contact-support values from `ios/Config/URLSaverSecrets.xcconfig`.
- Ads, external analytics, and third-party crash reporting remain disabled. Google Play Billing / StoreKit subscriptions are enabled for paid plans.
- Store forms must disclose account sign-in, shared-tag cloud sync/collaboration, invite sync, and contact support processing.

## Short Description
SNSやメッセージで見つけたURLを保存し、あとで一覧・詳細・アーカイブから開き直せます。

## Full Description
URL Saver は、共有メニューや手入力からURLを保存し、あとで見返しやすく整理するためのアプリです。

主な機能:
- 共有メニューからURLを保存
- 複数URLの共有保存
- 手動入力でURLを追加
- 保存済みURLの一覧、詳細、アーカイブ表示
- 重複URLの検出
- 削除猶予とUndo
- ページタイトル、説明、サムネイルなどのmetadata取得
- JSON / ZIP形式のエクスポート
- サインインして共有タグをクラウド同期
- 招待リンクで共有タグに参加
- アプリ内問い合わせ送信

提出版 `1.0.11` では共有タグクラウド、アカウント機能、Standard / Pro のアプリ内サブスクリプション購入を有効にします。広告表示、外部アナリティクス、第三者クラッシュ収集サービスは使用しません。

## Keywords
URL, bookmark, share, later, archive, save, link, productivity

## Screenshot Plan
1. Main list: saved URLs and service filters.
2. Manual add: URL input sheet.
3. Detail: saved URL, title, memo, metadata.
4. Archive: archived links and restore flow.
5. Export: JSON / ZIP export options.
6. Privacy info: local storage, metadata fetch, cloud sync, and contact support explanation.

## Review Notes Draft
- The app saves URLs provided by the user through share actions or manual input.
- Saved URLs, titles, memos, tags, and metadata are stored locally on the device.
- When the user signs in and uses shared tags, shared-tag URLs and shared-tag metadata are synced through Supabase for collaboration.
- The app may request web pages and public oEmbed endpoints to fetch page metadata.
- The contact-support form sends the user's contact email, name, and inquiry body to the configured support endpoint for email delivery.
- Paid plans can be purchased through Google Play Billing / StoreKit. Store purchase identifiers are used to grant app entitlements.
- No ads, external analytics, or third-party crash reporting are enabled in this release.

## Store Console Input Sheet

| Field | Google Play draft | App Store draft | Status |
|---|---|---|---|
| App name | URL Saver | URL Saver | DONE |
| Short description / subtitle | 共有したURLをあとで見返すための軽量保存アプリ。 | URLをあとで見返す軽量保存アプリ | DONE |
| Full description / description | Use `Full Description` above. | Use `Full Description` above, shortened if App Store field limits require it. | DONE |
| Category | Productivity | Productivity | DONE |
| Support email | `miyamibu@privaterelay.appleid.com` | `miyamibu@privaterelay.appleid.com` | DONE |
| Privacy policy URL | `https://miyamibu.xyz/privacy/` if current deployment is verified | `https://miyamibu.xyz/privacy/` if current deployment is verified | NEEDS_USER_ACTION |
| Marketing URL | optional | optional | NOT_APPLICABLE |
| Review contact | Use developer account owner contact in console | Use App Store Connect review contact | NEEDS_USER_ACTION |
| Review notes | Use `Review Notes Draft` above. Provide a test account if store review requires cloud-sharing sign-in. | Use `Review Notes Draft` above. Provide a test account if store review requires cloud-sharing sign-in. | NEEDS_USER_ACTION |
| Keywords | URL, bookmark, share, later, archive, save, link, productivity | URL, bookmark, share, later, archive, save, link | DONE |
| Copyright | Developer legal name required from account | Developer legal name required from account | NEEDS_USER_ACTION |

## Age Rating Draft
- User-generated content: no public feed; users save their own URLs.
- Web access: the app opens user-saved URLs in an external browser and fetches metadata from user-provided URLs.
- Purchases: Standard / Pro subscriptions are available as in-app purchases.
- Ads: none for this release.
- Account creation/sign-in: available for shared-tag cloud sync.

## Done When
- Final screenshots match the submitted build.
- App Store / Google Play wording matches the cloud-enabled `1.0.11` binary.
- Privacy and Data safety answers match the submitted binary.
