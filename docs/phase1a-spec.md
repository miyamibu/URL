# Phase 1a Specification (Implemented Contract)

## 1. Goal
共有された URL を保存し、あとで再オープンできるようにする。  
投稿本文・画像本体・埋め込みデータそのものは主役にしない。

## 2. Scope / Non-Scope
### Scope
- `ACTION_SEND` 単一 URL 共有受信
- `ACTION_SEND_MULTIPLE` 複数 URL 共有受信（順次保存 + 集計通知）
- 手動貼り付け保存
- URL 抽出 / 正規化 / 重複排除
- Main / Archive / Detail の 3 画面
- metadata WorkManager enqueue
- アーカイブ / 削除猶予 Undo
- 仕様・テスト・文書の整備

### Non-Scope
- WebView
- スワイプ archive/delete
- 検索機能
- タグ / 高度検索
- 過剰アニメーション
- Phase 1b 拡張

## 3. URL 保存思想
- 主対象は URL
- 重複判定主キーは `normalizedUrl`
- `originalUrl` は生入力保持
- `canonicalId` は補助情報（主キーにしない）
- `displayUrl` は表示専用
- `openUrl` は開く/コピー専用
- Phase 1a 固定: `openUrl = normalizedUrl`
- 一覧カードタップは詳細遷移のみ（直接 open しない）
- 詳細画面「開く」「コピー」は `openUrl` を使う

## 4. Data Model / DB
### Entity
- `pendingDeletionUntil` は epoch millis `Long`
- `recordState`: `ACTIVE` / `ARCHIVED` / `PENDING_DELETE`
- `metadataState`: `PENDING` / `READY` / `FAILED` / `UNAVAILABLE`

### Unique
- `normalizedUrl` に DB-level unique index

### Migration + Dedup
- migration で重複を dedup してから unique 制約を維持
- 生存レコード優先:
  1. `ACTIVE`
  2. `ARCHIVED`
  3. `PENDING_DELETE`
- 同 state 内優先:
  1. `metadataState = READY`
  2. `updatedAt` 降順
  3. `createdAt` 降順
  4. `id` 降順
- 補完対象:
  - `fetchedTitle`
  - `thumbnailUrl`
  - `canonicalId`
  - `rawSourceHost`
  - `normalizedHost`
  - `metadataFetchedAt`
- 上書きしない:
  - `normalizedUrl`
  - 主キー
  - `recordState`
  - `originalUrl`
- `originalUrl` は survivor の値を保持

## 5. URL Extraction / Normalization / Display
### Share extraction priority
1. `Intent.EXTRA_TEXT`
2. `ClipData`
3. `intent.dataString`

各 source ごとに:
- 候補文字列を抽出
- source 内で最初に見つかった有効な absolute `http/https` URL を 1 件採用
- 有効 URL がなければ次 source へフォールバック

共有 payload に複数 URL が含まれる場合:
- `ACTION_SEND`: 保存対象は先頭 1 件のみ（1件目保存を通知）
- `ACTION_SEND_MULTIPLE`: 抽出された複数 URL を重複除外して順次保存し、集計結果を通知する

結果:
- 候補文字列が一度もない: `NO_URL_FOUND`
- 候補文字列はあるが最後まで有効 URL がない: `INVALID_URL`

### Normalization (minimum)
- scheme lowercase
- host lowercase
- fragment 除去
- default port 除去 (`http:80`, `https:443`)
- path が root (`/`) 以外のときのみ末尾 slash 除去
- query は保持
- 有効 scheme は `http`/`https` のみ
- absolute URL 必須
- host 非空必須
- scheme なしは無効

### displayUrl
- 生成元は `normalizedUrl`
- scheme 非表示
- fragment 非表示
- query は原則非表示
- 例外: YouTube は `v=` のみ表示維持
- TikTok/X/Instagram/WEB は query 非表示
- 長い表示は `maxLines = 1` + `TextOverflow.Ellipsis`

## 6. Title / Memo
### effectiveTitle priority
1. `userTitle`
2. `fetchedTitle`
3. `サービス名 + のリンク`
4. `normalizedHost`
5. `保存したリンク`

### Normalization + limits
- `userTitle` trim 後空白のみ: `null`
- `memo` trim 後空白のみ: `""`
- `userTitle` max: 120
- `memo` max: 2000
- 超過時は保存しない（編集 UI でエラー表示）

### Service labels
- `TIKTOK` -> `TikTok`
- `YOUTUBE` -> `YouTube`
- `X` -> `X`
- `INSTAGRAM` -> `Instagram`
- `WEB` -> `Webサイト`

## 7. Timestamp / Clock Rules
- 同一 clock source を使用:
  - `createdAt`
  - `updatedAt`
  - `archivedAt`
  - `pendingDeletionUntil`
  - `metadataFetchedAt`
- `updatedAt` を更新してよい操作:
  - create / archive / restore / pending delete / final delete
  - `userTitle` 保存 / `memo` 保存 / 明示編集
- metadata 更新だけでは `updatedAt` を更新しない
- `metadataFetchedAt` は最終結果時に更新:
  - `READY` / `FAILED` / `UNAVAILABLE`

## 8. Metadata Classification / Worker
### Classification
- `FAILED`: `TIMEOUT`, `NETWORK_IO`, `HTTP_5XX`
- `UNAVAILABLE`: `NON_HTML`, `OVERSIZED`, `TOO_MANY_REDIRECTS`, `HTTP_404`, `HTTP_4XX`, `PARSE_FAILED`
- 固定: `PARSE_FAILED = UNAVAILABLE`

### Limits
- HTML とみなす MIME: `text/html`, `application/xhtml+xml`
- Content-Type 欠損時は最大 512KB 読んで HTML 判定
- body 上限 512KB
- redirect 上限 5
- connect timeout 10 秒
- read timeout 30 秒

### Retry / WorkManager
- retry 合計 3 回（初回 + retry 2 回）
- unique work key: `metadata:{entryId}`
- `entryId` は Room PK `Long`
- `ExistingWorkPolicy.KEEP`
- `NetworkType.CONNECTED`
- exponential backoff（初期 10 秒）

### Worker field ownership
- `ResolveWorker` が更新してよい:
  - `canonicalId`
  - 必要な補助メタ情報
- `ResolveWorker` が更新禁止:
  - `normalizedUrl`, `originalUrl`, `displayUrl`, `openUrl`
  - `recordState`, `userTitle`, `memo`
- `FetchMetadataWorker` が更新してよい:
  - `fetchedTitle`, `thumbnailUrl`, `metadataState`
  - `metadataFetchedAt`, `metadataError`
  - `canonicalId`（確定時）
  - 補助 host/source 情報
- `FetchMetadataWorker` が更新禁止:
  - `normalizedUrl`, `originalUrl`, `displayUrl`, `openUrl`
  - `recordState`, `userTitle`, `memo`
  - `createdAt`, `updatedAt`

### `RESTORED_FROM_PENDING_DELETE` re-enqueue
- `READY`: 再 enqueue しない
- `UNAVAILABLE`: 自動再取得しない
- `FAILED`: 再 enqueue 対象
- 未取得相当（`PENDING` + 未取得）: 再 enqueue 対象
- `KEEP` により既存 unique work がある場合は重複実行しない

## 9. Deletion Grace / Cleanup
- cleanup 実行箇所は `MainActivity.onStart` のみ
- `Application` / `onResume` では cleanup しない
- cleanup 対象は期限切れ `PENDING_DELETE` のみ
- フォアグラウンド中の物理削除は in-memory timer
- `pendingDeletionUntil - now` delay 後に finalize delete
- 期限到達時に state が `PENDING_DELETE` 以外なら no-op
- process death 後は `onStart` cleanup が回収
- タイマーは Activity-scoped ViewModel の `Map<Long, Job>` 相当
- 同じ entryId のみ古い job を cancel して差し替え
- undo / restore / final delete 完了時に該当 job を破棄

## 10. Android Flow / Manifest / Extras
### ShareReceiverActivity
- 共有入力解釈 + 保存 use case 実行
- Snackbar 表示しない
- 結果を Intent extras で `MainActivity` へ渡す
- `startActivity` 直後に `finish()`
- `android:noHistory="true"`
- `launchMode="standard"`
- `android:exported` を明示

### MainActivity
- `launchMode="singleTop"`
- `onCreate` / `onNewIntent` で `consumeShareResult(intent, currentRoute)` を呼ぶ
- extras は remove しない
- consume は idempotent

### Flags
- `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`
- `FLAG_ACTIVITY_NEW_TASK` は使わない

### Extras keys
- `EXTRA_SHARE_SAVE_RESULT`
- `EXTRA_SHARE_ENTRY_ID`
- `EXTRA_SHARE_NORMALIZED_URL`
- `EXTRA_SHARE_BATCH_TOTAL_COUNT`
- `EXTRA_SHARE_BATCH_CREATED_COUNT`
- `EXTRA_SHARE_BATCH_DUPLICATE_COUNT`
- `EXTRA_SHARE_BATCH_RESTORED_COUNT`
- `EXTRA_SHARE_BATCH_FAILED_COUNT`

### Extras usage
- `BATCH_PROCESSED`: `SAVE_RESULT` + batch count extras
- `CREATED`: `EXTRA_SHARE_SAVE_RESULT` のみ（`ENTRY_ID`/`NORMALIZED_URL` は入れない）
- `DUPLICATE_ACTIVE`: `SAVE_RESULT` 必須
- `DUPLICATE_ARCHIVED`: `SAVE_RESULT` 必須
- `RESTORED_FROM_PENDING_DELETE`: `SAVE_RESULT` + `EXTRA_SHARE_ENTRY_ID` 必須
- `SAVE_FAILED` / `INVALID_URL` / `NO_URL_FOUND`: `SAVE_RESULT` 必須

### `ACTION_SEND_MULTIPLE`
- manifest/runtime で受信する
- 抽出した有効 URL を順次保存し、`BATCH_PROCESSED` + 集計 extras を MainActivity に渡す

## 11. Navigation / Snackbar / Events
### Routes
- Main: `"main"`
- Archive: `"archive"`
- Detail: `"detail/{entryId}"` (`entryId: Long`)
- start destination: `"main"`

### Activity-scoped channels
- `Channel<SnackbarEvent>(capacity = 64, onBufferOverflow = SUSPEND)`
- `Channel<MainNavigationEvent>(capacity = Channel.BUFFERED)`
- `Channel<DetailEffect>(capacity = Channel.BUFFERED)`（DetailViewModel 側）
- SharedFlow は使わない

### Typed events
```kotlin
sealed interface MainNavigationEvent {
    data object NavigateToArchive : MainNavigationEvent
}
```
```kotlin
sealed interface DetailEffect {
    data class NavigateBackAfterPendingDelete(val entryId: Long) : DetailEffect
    data class NavigateBackAfterArchive(val entryId: Long) : DetailEffect
    data class NavigateBackAfterRestore(val entryId: Long) : DetailEffect
    data class TitleEdited(val entryId: Long, val oldTitle: String?) : DetailEffect
}
```

### SnackbarEvent
- fields:
  - `kind`, `message`, `actionLabel`, `duration`, `customDurationMillis`, `entryId`, `targetRoute`
- `kind`:
  - `INFO`
  - `UNDO_PENDING_DELETE`
  - `UNDO_ARCHIVE`
  - `OPEN_ARCHIVE`
  - `UNDO_TITLE_EDIT`
- `message` / `actionLabel` は Activity-scoped ViewModel で生成
- action lambda は持たない
- `OPEN_ARCHIVE` は ViewModel が `NavController` を持たず typed navigation event で UI へ渡す

### Undo display policy
- 優先制御は表示側 collect/show 処理で行う（PriorityQueue 化しない）
- Undo Snackbar 表示中は後続通常 Snackbar を defer
- `customDurationMillis != null` の場合:
  - `showSnackbar(..., duration = SnackbarDuration.Indefinite)`
  - `delay(customDurationMillis)` 後に `dismiss()`
- 厳密 5 秒が必要な Undo は `5000ms` 固定（削除/アーカイブ/タイトル）

### Undo actions
- `UNDO_PENDING_DELETE`: `repository.restore(entryId)`
- `UNDO_ARCHIVE`: `repository.unarchive(entryId)`
- `UNDO_TITLE_EDIT`: 直前永続値 `oldTitle` を DB に書き戻す
- Undo 後に追加 Snackbar は自動発火しない

### `UNDO_TITLE_EDIT` invalidation
- 連続編集開始時: 既存 `UNDO_TITLE_EDIT` を dismiss + 旧 Undo 情報破棄
- archive / delete / 別 entry detail 遷移時: 既存 `UNDO_TITLE_EDIT` を dismiss + 失効
- 一覧への back navigation だけでは失効させない
- Undo は直近 1 回分のみ有効

### Share result consume + CTA
- `consumeShareResult(intent, currentRoute)` で処理
- `DUPLICATE_ARCHIVED` の action は `見る`
- `currentRoute == "archive"` のとき `見る` を出さない

## 12. Main / Archive UI
- Main/Archive は別 destination
- Archive に FAB は置かない
- 一覧更新は Room/Flow 購読で自動反映（手動 refresh API を追加しない）

### Filter chips
- 単一行 `LazyRow`
- 折り返しなし、横スクロール
- selected semantics + `stateDescription`
- 固定順・常時表示:
  1. `すべて`
  2. `YouTube`
  3. `TikTok`
  4. `X`
  5. `Instagram`
  6. `Webサイト`
- 件数 0 でも表示
- cold start / process death 復帰は `ALL`

### Card
- ヘッダー:
  - 左: サービスアイコン（24dp / tint / content description）
  - 中央: タイトル（最大 2 行）
  - 右: タイムスタンプ（上端揃え）
- サブ情報:
  - `WEB`: `normalizedHost`
  - 既知サービス: サービス名
  - 非 `STANDARD` のときのみ `contentContext` chip 1 つ
- URL 行: `displayUrl`（1行 + Ellipsis）
- 状態行:
  - `メタデータ取得中…`
  - `取得に失敗しました`
  - `取得対象外です`
- カードタップは常に詳細遷移

### Empty state
- Material Symbols Outlined 系 icon 1 つ
- 見出し + 補助テキスト
- イラスト禁止
- 初回空:
  - `まだ保存したURLはありません`
  - `SNSやブラウザの共有、または手動追加から保存できます`
- Archive 空:
  - `アーカイブしたURLはまだありません`

## 13. Manual Paste Bottom Sheet
- URL 入力は `OutlinedTextField`
- `INVALID_URL` / `NO_URL_FOUND` は `isError + supportingText`
- この 2 つは Snackbar を出さない
- 保存ボタン:
  - trim 後空文字なら disabled
  - trim 後 1 文字以上で submit 可
- 入力途中で閉じたら内容破棄（再表示で自動復元しない）

## 14. Detail UI
- 画面全体は縦スクロール 1 カラム
- 主アクション群はサービス情報の直後、折りたたみ詳細の前
- 主アクション群は固定フッターにしない
- SnackbarHost は Activity-level の標準 bottom

### Detail display
- `effectiveTitle`
- `displayUrl`
- `memo` 読み取り表示（空なら `メモなし`）
- サービス情報
- 主アクション:
  - `開く`
  - `コピー`
  - `アーカイブ` / `アーカイブ解除`
  - `削除`
- 折りたたみ詳細（初期 collapsed、見出し `詳細情報`）:
  - `originalUrl`
  - `normalizedUrl`
  - `canonicalId`（値があるとき）
  - `metadataError`（値があるとき）
  - `archivedAt`（Archive 系 state）
  - `metadataFetchedAt`（値があるとき）
- 長い値は省略せず折り返して全文表示
- `metadataState != PENDING` かつ `metadataFetchedAt == null` でも行は増やさない

### `userTitle` inline edit
- ダイアログ禁止、インライン編集
- 開始トリガー: タイトル行右端の鉛筆アイコン
- 鉛筆は常時表示、`contentDescription = タイトルを編集`
- 編集中はキャンセルアイコンへ差し替え、`contentDescription = 編集をキャンセル`
- 編集入力: `OutlinedTextField`
- 初期値:
  - 保存済み `userTitle`
  - `userTitle == null` なら空文字
  - `fetchedTitle` を初期値に使わない
- `KeyboardOptions(imeAction = ImeAction.Done)`
- 保存確定は IME Done のみ
- フォーカスロストでは保存しない
- system back / back gesture は保存せず編集キャンセル
- 最大 120、超過は `isError + supportingText`（レイアウトシフト許容）
- 保存直後に DB 即書き込み
- `DetailEffect.TitleEdited(entryId, oldTitle)` で Activity-scoped ViewModel へ通知

### `memo` dialog edit
- ダイアログ編集（インライン編集しない）
- `OutlinedTextField`, 複数行、`minLines = 4`
- 初期値は保存済み `memo`
- ボタンは `キャンセル` / `保存`
- 最大 2000、超過は `isError + supportingText`
- Undo は付けない

### Detail action navigation
- Active 詳細で `削除`: `PENDING_DELETE` 化 + Undo snackbar 経路へ通知 + 元一覧へ戻る
- Active 詳細で `アーカイブ`: archive 実行 + Undo snackbar 経路へ通知 + Main へ戻る
- Archive 詳細で `アーカイブ解除`: restore 実行 + `復元しました` + Main へ戻る（Undo なし）
- `開く` / `コピー` / `メモ保存` はその場に留まる

## 15. Notification Table
- 手動貼り付け:
  - `CREATED`: `保存しました`
  - `DUPLICATE_ACTIVE`: `すでに保存済みです`
  - `DUPLICATE_ARCHIVED`: `アーカイブ済みです` + `見る`（Archive 画面では action なし）
  - `RESTORED_FROM_PENDING_DELETE`: `削除を取り消して復元しました`
  - `SAVE_FAILED`: `保存できませんでした`
  - `INVALID_URL` / `NO_URL_FOUND`: ボトムシート内エラー
- `ACTION_SEND`:
  - `INVALID_URL`: `有効なURLではありませんでした`
  - `NO_URL_FOUND`: `URLが見つかりませんでした`
- 削除: `削除しました` + `元に戻す`
- アーカイブ: `アーカイブしました` + `元に戻す`
- 復元: `復元しました`（Undo なし）
- タイトル編集: `タイトルを保存しました` + `元に戻す`
- コピー成功: `リンクをコピーしました` (`INFO`)
- memo 保存: `メモを保存しました`

## 16. Timestamp Display
- 当日: `HH:mm`
- 当年別日: `M/d`
- 別年: `yyyy/M/d`
- Main 一覧: `createdAt`
- Archive 一覧: `archivedAt`
- locale は `Locale.JAPAN`
- テストは `Asia/Tokyo` + `Locale.JAPAN`

## 17. Verification Commands
- `./gradlew assembleDebug`
- `./gradlew testDebugUnitTest`
- `./gradlew lintDebug`
- 必要に応じて: `./gradlew connectedDebugAndroidTest`
