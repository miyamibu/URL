# Store Listing Draft

## Goal
App Store / Google Play へ提出する文言を、現在の source baseline（Android `1.0.17 (versionCode=21)` / iOS `1.0.19 (build=21)`）と、実際に選択した release mode に一致させる。

## Current source snapshot (manifest-backed)

- Android: `jp.miyamibu.urlalbum`, `versionName=1.0.17`, `versionCode=21`
- iOS: `com.mibu.codebridge.ios`, `shortVersion=1.0.19`, `build=21`; share extension `com.mibu.codebridge.ios.share`
- Supabase migration head: `20260824090000_fix_apply_personal_link_ops_conflict.sql`

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
Current source baseline is Android `1.0.17 (versionCode=21)` and iOS `1.0.19 (build=21)`. The official App Store URL showed public `1.0.18` on 2026-08-25 with the approved local-only metadata for build 20. Exact current-source version 1.0.19 build 21 was archived, Apple Distribution exported, verified, and uploaded successfully on 2026-08-25; version creation, build attachment, review submission, approval, and propagation remain before current-source Store GO.

- Android release mode depends on `release.shared.tag.cloud.enabled` and release Supabase values from local/env configuration.
- iOS defaults to tracked local-only xcconfig; cloud-sharing Archive/TestFlight must pass ignored `ios/Config/URLSaverSecrets.xcconfig` explicitly.
- Ads, external analytics, and third-party crash reporting remain disabled. Google Play Billing / StoreKit subscriptions are enabled for paid plans.
- Exact-binary evidence separates the current stores. The submitted signed iOS 1.0.18 (20) archive is local-only and uses the dedicated local-only product copy; App Privacy `Data Not Collected` remains consistent with that bounded binary. The signed Android v21 candidate is cloud-enabled, and its corrected Data Safety questionnaire is now under Google review.
- Store forms must disclose account sign-in, shared-tag cloud sync/collaboration, invite sync, contact support, and purchase/entitlement processing for the exact cloud-enabled binary. The current platform definitions must still be reviewed before classifying the separate user-initiated OS share itself as developer collection/sharing.
- Manual ChatGPT handoff implementation, pre-transfer confirmation, Privacy/App Review wording, and automated validation were reflected in the 1.0.18 Store metadata submitted on 2026-08-23.

## 2026-08-21 Direct Public Store Recheck

| Surface | Direct observation | Action boundary |
|---|---|---|
| App Store app ID `6771251450` | Version 1.0.18 (20) was uploaded and submitted with the dedicated local-only copy and Japanese declarations; App Store Connect shows `審査待ち`. | Recheck the public description, version, and language after Apple approval/publication. |
| Google Play package `jp.miyamibu.urlalbum` | Owner-reviewed Data Safety v3 CSV was imported, saved, and submitted. Publishing overview shows `審査中の変更`. | Recheck the public Data Safety page after Google approval/propagation. |
| Public Privacy Policy | Vercel deployment `dpl_8yPxLjXnf4quJTa45q5dHEoTN51B` serves `https://miyamibu.xyz`; the post-deploy public-web verifier passes. | Keep the live policy and submitted Store declarations aligned with future release-mode changes. |

### Exact Store action packet (not submitted)

- **Google Play:** `artifacts/full-go-audit/2026-08-23/google-play-data-safety-import-v3.csv` (SHA-256 `b347b759e816eef3306035cee4733cac9a8836d97f8b4c4d0d1c9c87c2fcc08e`) was owner-reviewed, imported, saved, and submitted for `jp.miyamibu.urlalbum`; Google review remains pending.
- **App Store Connect:** app ID `6771251450` / bundle `com.mibu.codebridge.ios` version 1.0.18 build 20 was uploaded with the dedicated local-only copy and submitted; Apple review remains pending.
- **Owner actions:** release owner authorization covered the Store writes above. Review correspondence and any rejection remediation remain future external actions.

## 2026-08-13 Public Store Evidence

| Surface | Direct observation | Release consequence |
|---|---|---|
| App Store | Public `1.0.17`, July 28 release, 7.2 MB, language `EN 英語`; listing describes shared tags/sync/invitations/cloud. Matching signed archive is local-only (`SharedTagCloudEnabled=false`, empty Supabase/contact-support config), development language English, with no localization bundle. | App Privacy Major withdrawn for this binary. Correct listing functionality (Major) and package/recheck Japanese localization (Minor), or replace with an accurately disclosed cloud-enabled build. |
| Google Play | Listing describes cloud synchronization while Data safety says no data is collected. C0's authenticated read-only Console check found Step 2 collect/share `Yes`, Step 5 no collection plus shared Web browsing history, account creation `does not allow`, and deletion unanswered. | Data safety Major; Console is accessible, but correction/save/review and public recheck were not performed. |
| Public policy | `https://miyamibu.xyz/privacy/` documents Supabase account/shared-tag data, support submission/audit, and purchase/entitlement processing. | Use as a data-flow cross-check; it does not prove Store form completion. |
| iOS localization | Current source declares Japanese, but the signed archive packages English as development language and no localizations; public `EN 英語` matches the archive. | Confirmed packaging Minor. Fix the archive content before expecting the public language field to change. |

## Short Description
SNSやメッセージで見つけたURLを保存し、あとで一覧・詳細・アーカイブから開き直せます。

## Full Description

This section is the **cloud-enabled Android draft**. Do not paste it into the submitted local-only iOS `1.0.18 (20)` listing. Use the dedicated App Store copy in the next section.

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

## Local-only App Store Copy — iOS 1.0.18 (20)

The following text is the complete copy for the matching signed local-only App Store binary. It intentionally does not promise shared-tag cloud, sign-in, invitations, contact submission, or paid plans.

### 短い説明

共有したURLを端末に保存し、検索・タグ・アーカイブであとから見返せます。

### 説明全文

りんばむは、共有メニューや手入力からURLを端末に保存し、あとで見返しやすく整理するためのアプリです。

主な機能:

- 他のアプリから共有した1件または複数のURLを保存
- 手入力したURLを保存
- URLを正規化し、同じURLの重複保存を防止
- 保存したURLの一覧、詳細、アーカイブ表示
- 削除前の猶予とUndo
- ページタイトル、説明、サムネイルなどのメタデータ取得
- 保存したURLの検索と自作タグによる整理
- JSON / ZIP形式での書き出し
- 自作タグを選び、対象URL、対象件数、除外件数、伏せ字後の内容を確認してからChatGPT向けZIPを作成し、OSの共有機能を開く手動受け渡し

「ChatGPTに聞く」は、りんばむが質問を入力・自動送信したり、OpenAI API、OAuth、MCPへ接続したりする機能ではありません。共有先の選択、質問入力、送信はユーザーが行います。

このiOS 1.0.18 (20)では、共有タグのクラウド同期、アカウントのサインイン、招待、アプリ内課金を提供しません。保存データは端末内で管理されます。ページ情報の取得時は、保存したURLの対象ページまたは公開oEmbedエンドポイントへ通信することがあります。

広告、外部アナリティクス、第三者クラッシュ収集サービスは使用しません。

### 審査メモ

- 提出対象は `com.mibu.codebridge.ios` version `1.0.18`, build `20` のlocal-only構成です。
- 共有タグクラウドは無効で、Supabaseおよび問い合わせ送信先は設定されていません。サインイン、招待、クラウド同期、問い合わせ送信、アプリ内課金はこのbinaryでは利用できません。
- ユーザーは共有メニューまたは手入力からURLを端末内へ保存できます。単一/複数URL、正規化、重複判定、一覧、Archive、Detail、削除猶予+Undo、メタデータ取得、検索、自作タグ、JSON/ZIP exportが対象です。
- `ChatGPTに聞く`は端末上で対象と伏せ字後の内容を確認し、ZIPを生成してOSの共有画面を開く手動機能です。質問の入力・自動送信、OpenAI API/OAuth/MCP接続は行いません。共有先の選択と最終送信はユーザー操作です。
- ページタイトル等の取得のため、ユーザーが保存したURLの対象ページまたは公開oEmbedエンドポイントへ通信する場合があります。りんばむのサーバーへ保存データを同期する機能はこのbinaryでは無効です。
- 広告、外部アナリティクス、第三者クラッシュ収集サービスはありません。

## Current Local-only App Store Submission Copy — iOS 1.0.19 (21)

Version 1.0.19 inherits the published 1.0.18 local-only product description above. Before saving the new version, replace only the exact sentence prefix `このiOS 1.0.18 (20)では` with `このiOS 1.0.19 (21)では`; keep every cloud/account/purchase exclusion unchanged.

### このバージョンの新機能

共有メニューから保存できない場合の案内を改善し、URLを安全に本体アプリから保存できるようにしました。安定性とプライバシー保護を改善しました。

### 1.0.19 審査メモ

- 提出対象は `com.mibu.codebridge.ios` version `1.0.19`, build `21` のlocal-only構成です。
- 1.0.18 (20)からの機能差分は、Share ExtensionがApp Groupへアクセスできない場合のfail-closed処理です。共有URLをcustom URL schemeへ埋め込まず保存を中止し、共有元でURLをコピーして本体アプリの`＋`から貼り付け保存する案内を表示します。
- 共有タグクラウドは無効で、Supabase、問い合わせ送信先、media resolver backendは設定されていません。サインイン、招待、クラウド同期、問い合わせ送信、アプリ内課金はこのbinaryでは利用できません。
- `ChatGPTに聞く`は端末上で対象と伏せ字後の内容を確認し、ZIPを生成してOSの共有画面を開く手動機能です。質問の入力・自動送信、OpenAI API/OAuth/MCP接続は行いません。共有先の選択と最終送信はユーザー操作です。
- ページタイトル等の取得のため、ユーザーが保存したURLの対象ページまたは公開oEmbedエンドポイントへ通信する場合があります。りんばむのサーバーへ保存データを同期する機能はこのbinaryでは無効です。
- 広告、外部アナリティクス、第三者クラッシュ収集サービスはありません。

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
| Full description / description | Use cloud-enabled `Full Description` above. | Use `Local-only App Store Copy — iOS 1.0.18 (20)` only. Do not mix in cloud/account/invite/purchase promises. | DRAFT_SEPARATED_BY_EXACT_BINARY |
| Category | Productivity | Productivity | DONE |
| Support email | `miyamibu@privaterelay.appleid.com` | `miyamibu@privaterelay.appleid.com` | DONE |
| Privacy policy URL | `https://miyamibu.xyz/privacy/` | `https://miyamibu.xyz/privacy/` | PUBLIC_VERIFIED_AFTER_DEPLOYMENT `dpl_8yPxLjXnf4quJTa45q5dHEoTN51B` |
| Marketing URL | optional | `https://miyamibu.xyz/` | SUBMITTED |
| Review contact | Use developer account owner contact in console | Use App Store Connect review contact | NEEDS_USER_ACTION |
| Review notes | Use `Review Notes Draft` above. Provide a test account if store review requires cloud-sharing sign-in. | Use the local-only `審査メモ` above; no cloud test account is applicable to iOS 1.0.18 (20). | DRAFT_SEPARATED_BY_EXACT_BINARY |
| Keywords | URL, bookmark, share, later, archive, save, link, productivity | URL, bookmark, share, later, archive, save, link | DONE |
| Copyright | Developer legal name required from account | Developer legal name required from account | NEEDS_USER_ACTION |
| Privacy / Data safety | Corrected cloud-enabled Android answers were submitted; public propagation is pending. | `Data Not Collected` remains bounded to the verified local-only 1.0.18 (20) archive. | STORE_SUBMITTED / PUBLIC_RECHECK_PENDING |
| Product-page functionality | Match the exact Android artifact. | Local-only 1.0.18 (20) copy was submitted without cloud/sync/invite claims. | APP_STORE_SUBMITTED / PUBLIC_RECHECK_PENDING |
| Product-page localization | Japanese listing expected from product language and source. | Submitted archive declares Japanese development/localization. | ARCHIVE_VERIFIED / PUBLIC_RECHECK_PENDING |

## Age Rating Draft
- User-generated content: no public feed; users save their own URLs.
- Web access: the app opens user-saved URLs in an external browser and fetches metadata from user-provided URLs.
- Purchases: Standard / Pro subscriptions are available as in-app purchases.
- Ads: none for this release.
- Account creation/sign-in: available only when the submitted binary enables shared-tag cloud sync.

## Done When
- Final screenshots match the submitted build.
- App Store / Google Play wording matches the submitted Android `1.0.17 (versionCode=21)` / iOS `1.0.18 (build=20)` binaries.
- Privacy and Data safety answers match the submitted binary.
- Google Play and App Store public pages reflect the submitted declarations after platform review/propagation. Until then, console submission evidence identifies the exact version/build or versionCode and remains distinct from public-live proof.
- The live Privacy Policy and App Review notes explain manual user-directed sharing, redaction limits, and no API/OAuth/automatic send; current store-form answers and rationale are owner-reviewed for the exact binary.
