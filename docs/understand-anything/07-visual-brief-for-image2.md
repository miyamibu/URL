# Visual Brief for image-2

## 画像の目的
非エンジニアが1枚で `URL共有アプリ_UrlSaver` の構造、主要フロー、危険領域を理解できる説明画像を作る。

## 見せたい物語
左側に複雑なAndroid/iOS/DB/Worker/Supabaseコードベースがあり、中央の案内役がURL保存、正規化、metadata、共有タグ同期を整理し、右側に分かりやすい地図として変換する。

## 主要モチーフ
- スマホ2台: AndroidとiOS。
- 共有メニューから飛んでくるURL。
- 中央の「正規化ゲート」。
- ローカルDBの箱。
- Workerが裏でmetadataを取りに行く黄色い小型ロボット。
- SupabaseクラウドとShared Tag Syncの赤/紫ライン。
- 危険領域を示す注意標識。

## 画面に入れるべき要素
- URL保存。
- URL正規化。
- 重複判定。
- Main / Archive / Detail。
- Metadata取得。
- Shared Tag Sync。
- Android。
- iOS。
- DB。
- Worker。
- External。
- Test。
- P0危険領域: `normalizedUrl`, `openUrl`, DB schema, sync apply/pull。

## 色分けルール
- UI: 青。
- API: 紫。
- Service/Logic: 緑。
- DB/Data: オレンジ。
- Worker/Cron: 黄。
- Config/Env: グレー。
- External: 赤。
- Test: 薄グレー。

## レイアウト
- 黒背景。
- 左: 複雑なコードの森。Android, iOS, Share Extension, Room, SQLite, Supabase SQLが絡む。
- 中央: 案内役キャラクターと金色の光の線。「理解ツアー」「整理中」。
- 右: 大きなレイヤーマップ。UI, Service, DB, Worker, External, Testが整理されている。
- 下部: 危険領域の短いチェックリスト。

## ラベル文言
- URL保存。
- 正規化URLで同一判定。
- Main / Archive / Detail。
- Metadata取得。
- Shared Tag Sync。
- Android / iOS。
- Local DB。
- Worker。
- Supabase。
- Tests。
- 触ると危ない: normalizedUrl / openUrl / DB schema / apply-pull。

## 入れてはいけないもの
- 実シークレット、APIキー、メールアドレス。
- 細かすぎる実ファイル名の羅列。
- 本番DBへ接続しているような表現。
- 共有タグが全カードやmetadataまで同期するような誤解を招く描写。

## 参照する実ファイル・実機能
- `ShareReceiverActivity.kt`, `ShareViewController.swift`
- `UrlRules.kt`, `URLRules.swift`
- `DefaultUrlRepository.kt`, `URLRepository.swift`
- `FetchMetadataWorker.kt`, `MetadataCoordinator.swift`
- `SharedTagSyncCoordinator.kt`, `SharedTagCloud.swift`
- `AppDatabase.kt`, `SQLiteDatabase.swift`
- `supabase/migrations/20260420120000_shared_tag_sync.sql`

## 1枚絵で伝えるべき結論
このアプリは、URLを正規化してローカルDBへ保存し、metadataは裏で取り、共有タグだけをapply/pullで同期する。危険なのはURL同一判定、DB schema、metadata状態、Shared Tag Syncである。
