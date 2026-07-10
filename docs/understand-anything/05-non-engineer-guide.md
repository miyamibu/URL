# Non-Engineer Guide

## まず何を見るか
- 全体像: `docs/understand-anything/01-project-overview.md`
- 機能とコード対応: `docs/understand-anything/02-feature-to-code-index.md`
- 操作の流れ: `docs/understand-anything/03-business-flow-map.md`
- 変更リスク: `docs/understand-anything/04-impact-risk-map.md`
- 図: `docs/understand-anything/diagrams/`

## このアプリを一言で言うと
スマホで共有されたURLを、同じURLかどうかを判定して保存し、あとで見やすいカードとして表示し、必要なタグだけクラウド同期するアプリです。

## 用語集
- URL正規化: URL表記ゆれを揃える処理。例: ホスト小文字化、不要な末尾スラッシュ整理など。
- normalizedUrl: Android側の正規化済みURL。一意キー。
- openUrl: 実際に開くURL。設計上 `normalizedUrl` と同じ。
- duplicate: すでに保存済みの同じURL。
- metadata: タイトル、本文抜粋、サムネイル、サービス画像など、URLから後で取得する情報。
- worker: 画面の裏側で動く処理。AndroidはWorkManager、iOSはBackgroundTasks/actor。
- Room: AndroidのローカルDBライブラリ。
- SQLite: iOS側で使うローカルDB。
- outbox: 共有タグ同期で、まだサーバーへ送れていない操作の待ち行列。
- pull: サーバーから最新状態を取得すること。
- apply: ローカルの変更をサーバーへ反映すること。
- Shared Tag: 共有できるタグ。通常保存カード全体ではなく、タグ内URLリストを同期する。
- archive: 削除ではなく、MainからArchiveへ移す状態。
- updatedAt: ユーザー編集や状態変更の更新時刻。metadata取得だけでは変えない。

## UI / API / Service / DB / Worker の関係
- UIはユーザー操作を受け取る場所です。例: `UrlSaverRoot.kt`, `RootView.swift`。
- Service/Logicはルールを判断する場所です。例: `UrlRules.kt`, `DefaultUrlRepository.kt`, `SharedTagCloud.swift`。
- DBは保存場所です。AndroidはRoom、iOSはSQLite。
- Workerは裏側でmetadata取得や共有タグ同期を進めます。
- API/ExternalはSupabaseやWebサイトです。共有タグ同期とmetadata取得で使います。

## 画面で起きることとコードの関係
- 共有保存: `ShareReceiverActivity.kt` / `ShareViewController.swift` から始まる。
- 一覧表示: `UrlSaverRoot.kt` / `RootView.swift` がDBの保存内容をカードにする。
- 詳細表示: `DetailViewModel.kt` / `DetailView.swift` が1件のURLを表示する。
- 共有タグ表示: `TagDetailScreen.kt` / `SharedTagManagementSheets.swift` がタグ内URLを表示する。
- 再取得: DetailのボタンがmetadataをPENDINGに戻し、workerが取り直す。

## 触ると危ない領域
- `UrlRules.kt` と `URLRules.swift`: URLの同一判定が変わる。
- `AppDatabase.kt` と `URLRepository.swift` のschema: 起動・保存・migrationに直結。
- `SharedTagSyncCoordinator.kt` と `SharedTagCloud.swift`: 同期欠落や他ユーザー混入リスク。
- `FetchMetadataWorker.kt` と `MetadataFetcher.*`: 「取得できません」やサムネ欠落に直結。
- Supabase migrations: 本番データに影響し得るので、読むだけでも慎重に扱う。

## 変更依頼を出すときの言い方
- 良い例: 「共有タグ詳細でカードを外した時、Android/iOS両方でoutboxへREMOVE_URL_TO_TAG相当が入り、pull後も消えることを確認して」
- 良い例: 「metadata再取得ボタンで `updatedAt` を変えず、Main一覧順が変わらないことをテストして」
- 避けたい例: 「同期を直して」だけ。どの操作、どの端末、どのタグ、どの表示かが必要です。

## Codexに聞くときの質問例
- 「共有タグのカード詳細で項目が見つからない原因を、entry ID、sharedReferenceCount、tag cross refの観点で調べて」
- 「TikTok metadataでサムネイルとアイコンが表示されない経路をAndroid/iOS両方で比較して」
- 「`normalizedUrl` を変える場合の影響範囲をテスト一覧付きで出して」
- 「metadata更新だけで `updatedAt` が変わらないテストを追加して」

## 仕様確認チェックリスト
- 保存時に `normalizedUrl` / `normalized_url` が一意キーになっているか。
- `openUrl = normalizedUrl` が維持されているか。
- metadata更新で `updatedAt` / `updated_at` を更新していないか。
- 共有タグ同期がauthUserIdでスコープされているか。
- 共有タグv1でmetadataやmemoまで同期すると誤解していないか。
- Android/iOS両方に同じ仕様のテストがあるか。

## 改善提案
- 共有タグ詳細で「通常保存カード」と「共有タグからだけ見えているカード」をUI上で区別すると混乱が減る。
- metadata取得不可の理由を、外部サイト制限/ネットワーク/解析不可に分けてユーザーへ短く表示するとよい。
- 起動直後の遅さ調査用に、DB load、metadata backlog、sync pullの所要時間ログをdebug限定で見られると便利。
