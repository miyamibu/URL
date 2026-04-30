# Business Flow Map

## 非エンジニア向け全体像
このアプリは「URLを受け取る」「同じURLか判定する」「保存する」「裏で情報を取りに行く」「一覧や詳細で見せる」「共有タグだけクラウド同期する」という流れで動きます。保存済みカードの本体は各端末のローカルDBにあり、共有タグクラウドはタグ内URLリストを同期する仕組みです。

## フロー1: URLを共有して保存する

### ユーザー視点
- ブラウザ/SNS/メッセージで共有ボタンを押す。
- UrlSaverを選ぶ。
- アプリに戻り、保存・重複・失敗の結果を見る。

### システム内部処理
- Androidは `AndroidManifest.xml` の `ACTION_SEND` / `ACTION_SEND_MULTIPLE` が `ShareReceiverActivity.kt` を起動する。
- iOSは `ios/URLSaverShareExtension/ShareViewController.swift` が `NSExtensionItem` からURLやテキストを読む。
- URL抽出は Android `UrlRules.kt`、iOS `URLRules.swift`。
- 正規化後、`DefaultUrlRepository.kt` / `URLRepository.swift` がDB内の既存 `normalizedUrl` / `normalized_url` を探す。

### DB保存内容
- `originalUrl` / `original_url`: 入力元URL。
- `normalizedUrl` / `normalized_url`: 重複判定キー。
- `openUrl` / `open_url`: 開くURL。原則 `normalizedUrl` と同一。
- `metadataState` / `metadata_state`: 初期値PENDING。
- `recordState` / `record_state`: 初期値ACTIVE。

### 失敗時分岐
- URLなし: NO_URL_FOUND。
- URL不正: INVALID_URL。
- 入力大きすぎ: INPUT_TOO_LARGE。
- 既存ACTIVE: DUPLICATE_ACTIVE。
- 既存ARCHIVED: DUPLICATE_ARCHIVED。
- PENDING_DELETEから復元: RESTORED_FROM_PENDING_DELETE。

### 手動対応ポイント
- 「保存されない」は共有入口、URL抽出、正規化、DB保存のどこで止まったかを切り分ける。
- Androidは `ShareReceiverActivityTest.kt`、iOSは `URLRepositoryTests.swift` と `URLRulesTests.swift` を確認する。

### 関連コード
- `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`
- `app/src/main/java/jp/mimac/urlsaver/domain/UrlRules.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
- `ios/URLSaverShareExtension/ShareViewController.swift`
- `ios/URLSaverShared/Domain/URLRules.swift`
- `ios/URLSaverShared/Data/URLRepository.swift`

## フロー2: URLを手動保存する
- ユーザーがMain画面の入力欄にURLを入れる。
- Android `UrlSaverRoot.kt` から `DefaultUrlRepository.saveFromManualInput`。
- iOS `RootView.swift` / `URLSaverAppModel.swift` から `URLRepository.saveFromManualInput`。
- 共有保存と同じく正規化、重複判定、DB保存、metadata予約へ進む。

## フロー3: Metadataを取得する
- 保存時に `metadataState=PENDING` でDBへ入る。
- Androidは `MetadataWorkScheduler.kt` が `ResolveWorker` -> `FetchMetadataWorker` をWorkManagerへ積む。
- iOSは `MetadataCoordinator.swift` がfetchし、`AppBackgroundScheduler.swift` がバックログ処理を登録する。
- fetcherはWeb、YouTube、X、Instagram、TikTok等を解析する。
- 成功: READY。再試行可能失敗: FAILED。仕様上/制限上無理: UNAVAILABLE。
- Android `UrlEntryDao.updateMetadata` とiOS `URLRepository.applyMetadataUpdate` は `updatedAt` / `updated_at` を更新しない。

## フロー4: 共有タグを作る
- ユーザーが共有タグ作成を選ぶ。
- Android `DefaultTagRepository.createSyncedTag` 系がローカルタグをSYNCEDとして作り、outboxへCREATE_TAGを積む。
- iOS `SharedTagCloud.swift` がSupabase RPC用操作を作る。
- Sync Worker/Executorが `apply_shared_tag_ops` へ送る。
- pull snapshotでローカルDBへ確定状態を反映する。

## フロー5: 共有タグでカードを同期する
- タグ内へURLを追加すると、ローカルではすぐ見えるように反映し、outboxへADD_URL_TO_TAGを積む。
- Android `SharedTagSyncCoordinator.syncSession` は pending outboxをapplyし、その後pullする。
- iOS `SharedTagCloud.refreshLocalState` はsnapshotを取り込み、`shared_tag_urls` と `url_entries` をつなぐ。
- v1ではタグ内URLリストが同期対象。タイトル、メモ、metadataは同期保証対象ではない。根拠: `contracts/shared-tag-sync/README.md`。

## フロー6: アーカイブする / 復元する
- アーカイブはURLを削除せず、一覧の見え方を変える操作。
- Android `DefaultUrlRepository.archive/unarchive` が `recordState` と `archivedAt` を更新する。
- iOS `URLRepository.archive/unarchive` が `record_state` と `archived_at` を更新する。
- metadataやnormalizedUrlは維持される。

## フロー7: 詳細を見る
- ユーザーがカードをタップする。
- AndroidはDetail routeから `DetailViewModel` がentryを読む。
- iOSは `DetailView.swift` が `URLSaverAppModel` の選択中entryを表示する。
- 共有タグ詳細から開く場合、同期反映で作られたローカルentry IDと表示対象の可視性が一致している必要がある。

## 業務上の注意点
- 共有タグに追加したURLは、相手端末の「通常保存カード」として保存されるのではなく、共有タグ内の同期表示として扱われる設計がある。
- metadataは外部サイト都合で取れない場合があるため、UNAVAILABLEは必ずしもアプリの保存失敗ではない。
- `normalizedUrl` の仕様変更は既存カードの一致条件を変えるため、軽微な修正に見えても高リスク。

## よくある壊れ方
- 共有保存後にカードが出ない: 共有入口、正規化、DB保存、Mainフィルタのどこか。
- Detailで「項目が見つかりません」: entry IDが古い、同期snapshot後にshared-only entryが消えた、可視性条件が一致していない。
- 共有タグが同期できない: Supabase config/auth、outbox、apply RPC、pull snapshot、authUserIdスコープのどこか。
- TikTok/Instagram等の画像が出ない: metadata fetcherのoEmbed/HTML解析、外部サイト制限、サムネURLの抽出差異。
- 起動後カード表示が遅い: SQLite/Room読み込み、metadata backlog、sync pull、Share Extension handoff処理が競合している可能性。
