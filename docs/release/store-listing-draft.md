# Store Listing Draft

## Goal
契約前に App Store / Google Play の提出文言を準備し、ストア画面で必要になる入力を下書き化する。

## App Identity
- App name: URL Saver
- Subtitle / short description: 共有したURLをあとで見返すための軽量保存アプリ。
- Category: Productivity
- Initial release posture: local-only, ads disabled, billing disabled, shared-tag cloud disabled, no account creation, no third-party analytics, no third-party crash reporting.
- Android package name: `jp.mimac.urlsaver`
- iOS bundle ID: `jp.mimac.urlsaver.ios`
- Support contact: `miyamibu@privaterelay.appleid.com`
- Privacy policy URL candidate: `https://miyamibu.xyz/privacy/`
- Account deletion URL: `NOT_APPLICABLE` for the local-only initial release because account creation is not exposed. Required again if shared-tag cloud/account creation ships in v1.1 or later.

## Current Release Branch Decision
Initial store release is fixed to local-only:

- Android release keeps `ADS_ENABLED=false`, `SHARED_TAG_CLOUD_ENABLED=false` by default, and empty Supabase values unless explicit release cloud properties are supplied.
- iOS Debug and Release build settings now keep `URLSAVER_SHARED_TAG_CLOUD_ENABLED=false`, `URLSAVER_SUPABASE_URL=""`, and `URLSAVER_SUPABASE_ANON_KEY=""`.
- Shared-tag cloud, account creation, login, invite sync, and Supabase production integration are v1.1-or-later scope and must not be described as public v1.0 features.

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

初回公開では端末内保存を前提とし、広告表示、本物の課金、共有タグクラウド、アカウント作成、外部アナリティクス、第三者クラッシュ収集サービスは使用しません。

## Keywords
URL, bookmark, share, later, archive, save, link, productivity

## Screenshot Plan
1. Main list: saved URLs and service filters.
2. Manual add: URL input sheet.
3. Detail: saved URL, title, memo, metadata.
4. Archive: archived links and restore flow.
5. Export: JSON / ZIP export options.
6. Privacy info: local storage and metadata fetch explanation.

## Review Notes Draft
- The app saves URLs provided by the user through share actions or manual input.
- Saved URLs, titles, memos, tags, and metadata are stored locally on the device by default.
- The app may request web pages and public oEmbed endpoints to fetch page metadata.
- No ads, billing, external analytics, or third-party crash reporting are enabled in the initial release.
- The initial release does not expose account creation, login, shared-tag cloud sync, invite sync, or Supabase-backed collaboration.

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
| Review notes | Use `Review Notes Draft` above. No test account is needed for local-only v1.0. | Use `Review Notes Draft` above. No test account is needed for local-only v1.0. | DONE |
| Keywords | URL, bookmark, share, later, archive, save, link, productivity | URL, bookmark, share, later, archive, save, link | DONE |
| Copyright | Developer legal name required from account | Developer legal name required from account | NEEDS_USER_ACTION |

## Age Rating Draft
- User-generated content: no public feed; users save their own URLs.
- Web access: the app opens user-saved URLs in an external browser and fetches metadata from user-provided URLs.
- Purchases: none for initial release.
- Ads: none for initial release.
- Account creation: none for local-only v1.0.

## Done When
- Final screenshots match the submitted build.
- App Store / Google Play wording matches the local-only v1.0 binary.
- Privacy and Data safety answers match the submitted binary.
