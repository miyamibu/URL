# Project Overview

## これは何をするアプリか
`URL共有アプリ_UrlSaver` は、スマホの共有メニューや手入力からURLを保存し、あとで一覧・詳細・アーカイブ・タグで見返せるようにするAndroid/iOSアプリです。保存後はタイトル、本文抜粋、サムネイル、サービスアイコン相当のmetadataをバックグラウンドで取得します。

- Android共有入口: `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`
- iOS共有入口: `ios/URLSaverShareExtension/ShareViewController.swift`
- AndroidメインUI: `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- iOSメインUI: `ios/URLSaveriOS/UI/RootView.swift`

## 主要ユーザー
- SNS、ブラウザ、メッセージからURLをよく保存するユーザー。
- 後で読むURLをMain/Archive/Detailで整理したいユーザー。
- 共有タグを使って、特定のタグ内URLだけを別端末や別ユーザーと同期したいユーザー。
- PM、デザイナー、サポート担当など、保存・同期・metadata不具合の影響範囲を追いたい非エンジニア。

## 代表的な使い方
- Android/iOSの共有メニューからURLをUrlSaverへ送る。
- アプリ内の手入力欄からURLを保存する。
- Main一覧でカードを開き、DetailでURL、metadata、メモ、タグを確認する。
- 不要なカードをArchiveへ移す、または復元する。
- Shared Tagを作成し、招待URLで他ユーザーを参加させ、タグ内URLだけを同期する。

## 主な画面
- Main一覧: Android `UrlSaverRoot.kt`, iOS `RootView.swift`。
- Archive一覧: Android `ArchiveViewModel.kt`, iOS `RootView.swift`。
- Detail画面: Android `DetailViewModel.kt` と `UrlSaverRoot.kt`, iOS `DetailView.swift`。
- Shared Tag一覧/詳細: Android `TagFilterRow.kt`, `TagDetailScreen.kt`, iOS `SharedTagCloudSheet.swift`, `SharedTagManagementSheets.swift`。
- 認証/招待: Android `SharedTagCloudScreens.kt`, iOS `SharedTagCloudSheet.swift`。
- Export: Android `ExportScreen.kt`。

## 主な処理フロー
- URL保存: 共有または手入力 -> URL抽出 -> 正規化 -> 重複判定 -> `url_entries` 保存 -> metadata取得予約 -> 一覧表示。
- metadata取得: PENDING -> Android WorkManager / iOS BackgroundTasks -> READY, FAILED, UNAVAILABLE更新。
- 共有タグ同期: ローカル操作 -> outbox/apply -> pull snapshot -> ローカルDB反映 -> タグ詳細に表示。
- アーカイブ: `recordState` / `record_state` をACTIVEからARCHIVEDへ変更し、Archive一覧へ移す。

## Android側の構造
- Entry point: `MainActivity.kt`, `ShareReceiverActivity.kt`。
- DI/依存組み立て: `app/src/main/java/jp/mimac/urlsaver/app/AppContainer.kt`。
- Domain: `domain/UrlRules.kt`, `domain/Models.kt`, `domain/SharedTagSyncContracts.kt`。
- Data: `DefaultUrlRepository.kt`, `DefaultTagRepository.kt`, `AppDatabase.kt`, `UrlEntryDao.kt`, `TagDao.kt`, `SharedTagSyncDao.kt`。
- UI: `ui/UrlSaverRoot.kt`, `ui/TagDetailScreen.kt`, `ui/SharedTagCloudScreens.kt`, `ui/components/EntryCard.kt`。
- Worker: `worker/FetchMetadataWorker.kt`, `worker/SharedTagSyncWorker.kt`, `worker/ResolveWorker.kt`。

## iOS側の構造
- App entry: `ios/URLSaveriOS/App/URLSaveriOSApp.swift`。
- App state/services: `URLSaverAppModel.swift`, `AppServices.swift`。
- UI: `RootView.swift`, `DetailView.swift`, `SharedTagCloudSheet.swift`, `SharedTagManagementSheets.swift`。
- Shared domain/data: `ios/URLSaverShared/Domain/URLRules.swift`, `Data/URLRepository.swift`, `Data/SharedTagCloud.swift`。
- Share Extension: `ios/URLSaverShareExtension/ShareViewController.swift`。
- Background: `MetadataCoordinator.swift`, `AppBackgroundScheduler.swift`, `SharedTagSyncExecutor.swift`。

## 同期・外部連携
- Supabase Auth: Android `SharedTagAuthRemoteDataSource.kt`, iOS `SharedTagCloud.swift` 内の auth data source。
- Supabase RPC: `apply_shared_tag_ops`, `pull_shared_tag_snapshot`, `create_shared_tag_invite`, `accept_shared_tag_invite`, `delete_my_account`。
- Supabase schema: `supabase/migrations/20260420120000_shared_tag_sync.sql`, `supabase/migrations/20260422120000_shared_tag_invites.sql`。
- Web metadata: Android `MetadataFetcher.kt`, iOS `MetadataFetcher.swift` がHTTP/oEmbed/OpenGraph/HTML解析を行う。

## 主なDBテーブル / Entity
- Android Room `url_entries`: URL本体、metadata、状態を保持。`normalizedUrl` にunique index。根拠: `RoomModels.kt`。
- Android `tags`, `tag_url_cross_refs`: ローカル/同期タグとカード紐付け。根拠: `TagEntity.kt`, `TagUrlCrossRef.kt`。
- Android `shared_tag_members`, `shared_tag_sync_outbox`, `shared_tag_sync_state`: 同期メンバー、送信待ち、同期状態。根拠: `SharedTagSyncEntities.kt`。
- iOS SQLite `url_entries`: `normalized_url TEXT NOT NULL UNIQUE`。根拠: `URLRepository.swift`。
- iOS SQLite `local_tags`, `local_tag_entries`, `shared_tags`, `shared_tag_members`, `shared_tag_urls`, `shared_tag_sync_state`。根拠: `URLRepository.swift`, `SharedTagCloud.swift`。
- Supabase `shared_tags`, `shared_tag_members`, `shared_tag_urls`, `shared_tag_invites`。根拠: `supabase/migrations/*.sql`。

## 重要コード領域
- URL正規化: Android `UrlRules.kt`, iOS `URLRules.swift`。
- 重複判定: Android `DefaultUrlRepository.findExistingEntry`, iOS `URLRepository.findExistingEntry` 相当。
- `openUrl = normalizedUrl`: Android `UrlRules.parseUrl`, iOS `URLRules.parseURL` 相当。
- metadata更新で `updatedAt` を更新しない: Android `UrlEntryDao.updateMetadata`, iOS `URLRepository.applyMetadataUpdate`。
- Shared Tag Sync: Android `SharedTagSyncCoordinator.kt`, iOS `SharedTagCloud.swift`, Supabase `apply_shared_tag_ops` / `pull_shared_tag_snapshot`。

## 変更危険領域
- `normalizedUrl` / `normalized_url`: 保存・重複・共有タグ一致・同期の土台。変更はP0。
- `openUrl = normalizedUrl`: 外部ブラウザ起動とmetadata取得の入力がズレると再取得や表示が壊れる。変更はP0/P1。
- metadata state: READY/FAILED/UNAVAILABLE/PENDINGの遷移変更は再取得UIとユーザー文言に影響。変更はP1。
- Shared Tag Sync: outbox/apply/pull/reconcileを誤るとカード欠落、重複、別ユーザー混入が起きる。変更はP0。
- DB migration/schema: Android RoomとiOS SQLiteの片側だけ変えるとクロスプラットフォーム差異が広がる。変更はP0/P1。

## 最初に読むべきファイル順
1. `README.md`
2. `AGENTS.md`
3. `docs/shared-tag-sync-architecture.md`
4. Android: `app/src/main/java/jp/mimac/urlsaver/domain/UrlRules.kt`
5. Android: `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
6. Android: `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
7. iOS: `ios/URLSaverShared/Domain/URLRules.swift`
8. iOS: `ios/URLSaverShared/Data/URLRepository.swift`
9. iOS: `ios/URLSaveriOS/UI/RootView.swift`
10. 同期: `SharedTagSyncCoordinator.kt`, `SharedTagCloud.swift`, `supabase/migrations/20260420120000_shared_tag_sync.sql`

## ひとことで言うと
URL保存アプリの心臓は「正規化URLを一意キーにして保存し、metadataは非同期で補い、共有タグだけをSupabaseで同期する」構造です。
