# Audit Report

## Analyst A: 構造分析

### 見たもの
- AndroidのEntry point、Room、Repository、Worker、Compose UI。
- iOSのApp/Share Extension、SQLite、SwiftUI、BackgroundTasks。
- Supabase migrationsとcontracts。

### 結論
- アプリ本体は「UI -> Repository/Service -> Local DB -> Worker/External」の層で整理されている。
- 共有タグ同期は通常URL保存と完全同期ではなく、タグ内URLリストを同期する別系統。

### 根拠ファイル
- `app/src/main/java/jp/mimac/urlsaver/app/AppContainer.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/AppDatabase.kt`
- `ios/URLSaveriOS/App/AppServices.swift`
- `ios/URLSaverShared/Data/URLRepository.swift`
- `contracts/shared-tag-sync/README.md`

### 不安点
- Android/iOSでDB schema名や同期投影の持ち方が違うため、仕様差分が出やすい。

## Analyst B: 業務フロー分析

### 見たもの
- 共有保存、手動保存、metadata取得、共有タグ作成/招待/同期、アーカイブ、詳細表示。

### 結論
- ユーザー体験の中心は、保存自体はローカルで即時完了し、metadataと共有タグ同期は後続処理で補完する設計。
- 「表示されない」「項目が見つかりません」は、保存失敗だけでなく、フィルタ、record state、shared-only entry、sync projectionのズレでも起こる。

### 根拠ファイル
- `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
- `ios/URLSaverShareExtension/ShareViewController.swift`
- `ios/URLSaveriOS/App/URLSaverAppModel.swift`
- `ios/URLSaverShared/Data/SharedTagCloud.swift`

### 不安点
- 実機での起動直後パフォーマンスはコード読解だけでは確定しない。

## Analyst C: 変更リスク分析

### 見たもの
- URL正規化、重複判定、metadata更新SQL、Shared Tag Sync outbox/apply/pull、DB schema。

### 結論
- P0領域は `normalizedUrl`, `openUrl`, DB schema, shared tag sync, authUserIdスコープ。
- P1領域は metadata取得/再取得、起動時background処理、Main/Detailの表示可視性。
- metadata更新は意図的に `updatedAt` を変えないSQLになっている。

### 根拠ファイル
- `app/src/main/java/jp/mimac/urlsaver/domain/UrlRules.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/UrlEntryDao.kt`
- `ios/URLSaverShared/Data/URLRepository.swift`
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt`
- `supabase/migrations/20260420120000_shared_tag_sync.sql`

### 不安点
- Supabase live環境は接続していないため、RLSとRPCの実運用挙動はSQL定義ベースの判断。

## Auditor: 監査

### 一致点
- Android/iOSともURL保存の中心は正規化URL。
- 共有タグ同期は通常カード全体の同期ではなく、タグ内URL同期。
- metadataは外部依存が強く、失敗時のUI/文言が重要。
- DB schemaとmigrationは最重要リスク領域。

### 不一致点
- AndroidはRoom + WorkManager、iOSはSQLite + BackgroundTasks/actorで実装方式が違う。
- Androidには明示的outbox tableがあるが、iOSは `SharedTagCloud.swift` と executor中心に整理されている。

### 不一致理由
- プラットフォーム差と、iOS実装がAndroid仕様に追従している途中の領域があるため。

### 未確定事項
- 実機での起動遅延の支配要因。
- 共有タグ同期の本番/開発Supabase設定の現在値。
- image-2画像生成の直接ファイル保存可否。

### 追加調査すべきファイル
- `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/TagDetailViewModel.kt`
- `ios/URLSaveriOS/App/URLSaverAppModel.swift`
- `ios/URLSaveriOS/App/SharedTagSyncExecutor.swift`
- `ios/URLSaveriOS/UI/SharedTagManagementSheets.swift`

### 信頼度 0-5
- 4

### 結論
主要コード、DB、同期、metadata、テスト位置は確認済み。実機挙動とSupabase実接続は未確認なので、運用状態まで含む判断は追加検証が必要です。

### 次に依頼すべき作業
- 共有タグ詳細の「項目が見つかりません」を、sync projectionとentry可視性の観点で実機再現する。
- 起動直後のカード表示遅延を、DB読み込み、metadata backlog、sync pullに分けて計測する。
- Android/iOSのURL正規化ベクトルをCIで同一入力比較できる形にする。
