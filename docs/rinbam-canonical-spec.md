# りんばむ 正準仕様

## Goal
りんばむの Android / iPhone / 共有拡張 / backend / DB のデザインと機能を一本化し、過去実装の復元や別ブランチ作業で UI や機能が巻き戻らない状態にする。

## Context
- この仕様は、ユーザーと確認した採用/不採用判断を正準化する。
- `docs/mobile-ui-regression-contract.md` は巻き戻り防止の静的ガードとして残す。
- 本書は画面仕様、機能仕様、検証条件、運用ルールをまとめる。
- 実装前に本書と `docs/mobile-ui-regression-contract.md` を確認し、実装後に契約チェックと対象ビルドを実行する。

## Source Of Truth
1. 現在のユーザー明示指示
2. `AGENTS.md`
3. `DESIGN.md`
4. 本書
5. `docs/mobile-ui-regression-contract.md`
6. 現在の Android/iOS/backend/DB 実装
7. 実機確認結果と本番 backend 確認結果

## Canonical IDs And Runtime Targets
- Android applicationId: `jp.miyamibu.urlalbum`
- iPhone bundle ID: `com.mibu.codebridge.ios`
- iPhone share extension: `com.mibu.codebridge.ios.share`
- 正準ブランチ: `main`
- Render backend はデプロイ前に、実際に監視している branch と commit を確認する。
- 検証報告には Android / iPhone / Render の package、bundle、branch、commit、検証範囲を分けて書く。

## Implementation Rules
- `main` を正準ブランチにする。
- 古い branch や commit は正準にしない。必要な hunk だけ移植する。
- 古い実装の復元で file 全体コピーはしない。
- UI 変更前に `docs/mobile-ui-regression-contract.md` を確認する。
- UI 変更後に `python3 scripts/verify_mobile_ui_contract.py` を実行する。
- Android 変更時は最低 `./gradlew assembleDebug` を実行する。
- Android のロジック/DB/Repository 変更時は `./gradlew testDebugUnitTest` も実行する。
- iOS 変更時は `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` を実行する。
- DB schema 変更時は実機DBを消さず、migration で開けることを優先する。
- Android 実機では `pm clear`、uninstall、データ削除を勝手に行わない。
- iPhone 実機確認は可能なら Appium/WebDriverAgent で UI 操作証跡を取る。
- `devicectl install/launch` だけでは iPhone UI 確認済みと言わない。
- 実装ソースと実機 screenshot / XML / DB / artifact は別扱いにする。
- 実機証跡 artifact は原則 commit しない。必要な場合は別途相談する。
- まとまった機能ごとに小さく commit する。
- commit 前に `git diff --cached --check` と staged file list を確認する。
- 未コミット差分がある状態で別作業に入る時は、commit / 保留 / ゴミ箱移動を相談する。

## Home And Top Navigation
### Adopted
- `りんばむ` タイトルを押すとホームへ戻る。
- タイトル押下時は検索、タグ、サービス、選択状態、グループ画面、使い方画面を解除する。
- ホーム上部にビックリマーク/Info系アイコンを出さない。
- ホーム上部には `選択`、`使い方`、`検索`、画面切り替えボタンを置く。
- `選択` 押下時は表示中URLをすべて選択済みにする。
- 選択バーの件数表示は `2件` のように件数だけにする。
- `すべて選択` は文字だけにし、左アイコンを出さない。
- 選択バーにタグアイコンボタンを置き、選択URLを自作タグへ一括追加できる。
- 選択バー順は `件数 / すべて選択 / タグ / アーカイブ / 削除 / 閉じる`。
- 下部バーは `グループ / エクスポート / 中央+ / タグ / アーカイブ` を固定する。
- `使い方` はオンボーディング spotlight ではなく説明一覧ページを開く。

### Done When
- Android/iPhone のホーム上部に不要な Info 系アイコンがない。
- `りんばむ` 押下で全フィルタと選択状態が解除される。
- 選択バーの表示と操作順が両OSで揃っている。

## Entry Cards
### Adopted
- 一覧カードは簡素化するが、タイトルは今まで通り必ず表示する。
- サービスアイコンは常に表示する。
- 自作タグがあるカードは、サービスアイコン右に自作タグチップを表示する。
- 自作タグがあるカードでは、上部に `保存 15:xx` のような保存時刻を出さない。
- 自作タグがないカードは、上段に投稿主名を表示する。
- 投稿主名が取れない場合も、サービス名/ホスト名で必ず代替表示する。
- 自作タグチップは空きスペースを埋める折り返しレイアウトにする。カードが大きくなりすぎる場合は最大行数で省略してよい。
- 表示切り替えは `通常 / コンパクト`。
- コンパクト表示でも自作タグは見えるようにする。
- カード通常タップは詳細画面を開く。
- カード内の自作タグチップを押すと、そのタグで一覧を絞り込む。
- アクティブ一覧は右スワイプでアーカイブ、左スワイプで削除予約 + Undo。
- アーカイブ一覧もスワイプ削除できる。
- 選択モード中は選択済みが見た目で分かる。
- 長押しで選択モードに入れる。
- メディア保存状態は一覧で強く出さず、主に詳細画面で扱う。

### Rejected
- カード通常タップで外部URLを直接開く。
- タイトル/サービスアイコンで詳細と外部URLを分ける。
- 自作タグがあるカードで保存時刻を上段に出す。

### Done When
- 自作タグ付きカードはタグが主表示になり、投稿主名/保存時刻に戻らない。
- タグなしカードでも上段が空にならない。
- カードタップは詳細遷移に固定されている。

## Local Tags And Shared Tags
### Adopted
- ホーム上段は自作タグ行と共有タグ行を分ける。
- 自作タグ作成ボタンは `+` のみ。
- 共有タグ作成ボタンも `+` のみ。
- 新規作成した自作タグは先頭に表示する。
- 自作タグは長押しドラッグで並び替え可能にする。
- 共有タグも長押しドラッグで並び替え可能にする。
- 自作タグはダブルタップで名前変更できる。
- タグ管理はタグ名、件数、ゴミ箱アイコンを中心にする。
- タグ管理でリンク、JSON、文字付き `削除` を出さない。
- ゴミ箱アイコン押下時は確認ダイアログを出す。
- タグ一覧/追加画面は空きスペースを埋める折り返しレイアウトにする。
- 長いタグ名は省略してよいが、追加/削除などの操作ボタンは必ず表示する。
- 自作タグと共有タグは色を分ける。
- 自作タグチップ内に件数を出さない。
- タグ管理画面では件数表示してよい。
- 自作タグ削除ではURL本体を削除せず、紐付けだけ消す。

### Done When
- Android/iPhone で自作タグと共有タグの行、作成ボタン、色、管理表示が一致している。
- 長いタグ名でも操作ボタンが隠れない。

## Manual Add And Share Save
### Adopted
- 中央 `+` は手動URL/テキスト追加画面を開く。
- 手動追加画面で自作タグを複数選択できる。
- 手動追加画面で `+` から自作タグを作れる。
- 手動追加で作成したタグはすぐ選択状態にする。
- 手動追加のタグ一覧は折り返しレイアウトにする。
- 他アプリ共有保存でも保存先タグ画面を出す。
- 共有保存でも `+` から自作タグを作れる。
- 共有保存画面でタグが少ない時はタグの分だけの高さにする。
- タグが多い時はタグ領域だけスクロールし、保存/キャンセルボタンは常に押せる。
- タグ未選択でも保存できる。
- 複数タグを選んで保存できる。
- 保存後は `保存しました` と本体へ戻る/開く導線を出す。
- 重複URLは `すでに保存済み` と既存詳細導線を出す。
- 複数URL共有は可能な限り全部保存する。
- 共有時に選んだタグは複数URLすべてに付ける。
- URLではないテキストも保存する。

### Done When
- Android/iPhone/share extension で保存時タグ選択、タグ作成、複数選択ができる。
- 少数タグ時に共有拡張パネルが不必要に大きくならない。

## Detail Screen
### Adopted
- ボタン順は `開く / コピー / メディアを開く or メディアを保存 / アーカイブ / 削除 / メモを編集 / タグ / 共有タグ / 詳細情報`。
- 保存済みメディアがある場合は `メディアを開く`。
- 未保存で対象投稿の場合は `メディアを保存`。
- 通常ページなど対象外では `メディアを保存` を出さない。
- TikTok / Instagram / YouTube / X は動画、画像、複数画像を判別する。
- 複数画像投稿は複数画像として保存・表示する。
- 動画投稿は動画として保存・再生/表示する。
- YouTube 通常動画/Shorts も保存対象にする。
- Instagram は認証付き resolver または別方式を用意する。
- メディア保存先はまずアプリ内保存領域にする。
- 将来的に写真アプリ保存も追加できる余地を残す。
- `開く` は外部アプリ/ブラウザで開く。
- `コピー` は保存元URLをコピーする。
- `アーカイブ` は詳細からも実行可能にする。
- `削除` は確認ダイアログ後に削除予約する。
- `メモを編集` はメモだけ編集する。
- `タグ` は自作タグ編集を開く。
- `共有タグ` は共有タグ編集を開く。
- `詳細情報` は正規化URLと保存時刻のみ表示する。

### Done When
- 対象投稿だけにメディア保存導線が出る。
- 詳細情報に余計な技術情報を出さない。

## Media Viewer
### Adopted
- `メディアを開く` はアプリ内ビューアを開く。
- 画像はフル画面に近く、余白少なめに表示する。
- 複数画像は左右スワイプで切り替える。
- 複数画像では `1枚目 / 5枚` のような枚数表示を出す。
- 複数画像ではドットインジケータを出す。
- 動画は黒背景で中央表示する。
- 動画は再生/一時停止、シーク、音量/ミュート操作ができる。
- 右上に閉じるボタンを出す。
- ビューア内に共有ボタンを出す。
- 将来の `写真に保存` ボタン用の場所を残す。
- 動画と画像が混在しても同じビューアで見られるようにする。
- メディアが壊れている/見つからない場合は再保存ボタンを出す。
- 見た目は各OSのネイティブらしさを優先し、機能は同じにする。

## Archive, Delete, Undo
### Adopted
- アーカイブは削除ではなく、アーカイブ一覧へ移動する。
- アクティブ一覧の右スワイプでアーカイブする。
- アーカイブ直後は Undo を出す。
- 詳細画面からもアーカイブできる。
- アーカイブ一覧から元に戻せる。
- アーカイブ一覧では右スワイプで元に戻す。
- アクティブ一覧の左スワイプ削除は削除予約 + Undo にする。
- Undo は一定時間だけ出す。
- 詳細画面の削除は確認後、削除予約にする。
- アーカイブ一覧のスワイプ削除も削除予約 + Undo にする。
- 削除予約中のカードは一覧からすぐ消す。
- アプリ再起動後も猶予時間内なら Undo 可能にする。
- 猶予時間後に完全削除する。
- 完全削除時は自作タグ/共有タグ紐付け、メディア保存情報、メモ、保存済みメディアファイル本体も削除する。
- 重複URLがアーカイブ済みなら復元する。
- 重複URLが削除予約中なら復元する。
- 重複URLが通常保存済みなら既存詳細へ誘導する。
- 選択モードで複数件アーカイブ/削除できる。
- 複数件操作後も Undo を出す。
- 削除確認にはURL/JSONなど技術情報を出さず、タイトルや件数だけ表示する。

## Text Cards
### Adopted
- URLなしテキストも `テキストカード` として保存する。
- テキストカードはURLカードと同じ一覧に並べる。
- 自作タグ/共有タグ、メモ、アーカイブ、削除、タグ編集、共有タグ編集、詳細情報に対応する。
- タイトルはテキスト先頭から自動生成する。
- 初期実装では本文からタイトルだけ自動生成する。
- 日時/場所/金額/名前の専用抽出UIや専用DB列は作らない。
- 本文全文は検索対象にする。
- 明らかなURLは抽出してURLカード化する。
- URL + 文章の共有は、URLを主カード、文章をメモへ入れる。
- 複数URL + 文章は、各URLを別カードにし、文章を各カードのメモへ入れる。
- URLなし共有は1枚のテキストカードにする。
- テキストカードは専用テキストアイコンを使う。
- テキストカードに `開く` ボタンは出さない。
- テキストカードの `コピー` は本文全体をコピーする。
- テキストカードの詳細情報は保存時刻のみ表示する。
- テキストカードも検索、エクスポート対象にする。
- 重複判定は本文完全一致にする。
- テキストカードにメディア保存ボタンは出さない。
- 将来AIで要約/タグ候補/日付候補を作る余地を残す。

### Deferred
- 日時/場所/金額/名前の専用抽出UI。
- 日時/場所/金額/名前の専用DB列。

## Search, Filter, Display Modes
### Adopted
- 検索対象はタイトル、URL、本文、投稿内容、メモ、自作タグ名、共有タグ名、サービス名、投稿主名。
- 検索中も自作タグ/共有タグ/サービスフィルタを併用できる。
- 自作タグチップ、共有タグチップで絞り込みできる。
- サービスフィルタは残す。
- サービスフィルタは長押しで並び替え可能にする。
- 自作タグ + サービスフィルタを同時指定できる。
- 検索を閉じたら検索文字だけ解除し、タグ/サービスフィルタは残す。
- `りんばむ` タイトル押下時だけ全解除してホームへ戻る。
- 表示切り替えは `通常 / コンパクト`。
- 表示切り替え状態はアプリ再起動後も保持する。
- 並び順は新しい保存が上。
- 新規作成タグは先頭表示する。
- ユーザーがタグ並び替えした後は手動順を優先する。
- アーカイブ一覧にも検索、タグ、サービスフィルタを置く。
- アーカイブ一覧に `右スワイプで戻す` の軽い案内を出す。
- 検索結果0件時は `見つかりませんでした` と表示する。
- 大文字小文字、全角半角の違いをできるだけ吸収する。

## Shared Tags, Groups, Cloud Sync
### Adopted
- 共有タグは自作タグとは別行/別色で表示する。
- 共有タグはサインイン後に使える。
- 共有タグ入口は表示し、サインインしていなければ押下時にサインイン案内を出す。
- 共有タグの `+` は共有タグ作成を開く。
- 共有タグチップ通常タップは一覧を絞り込む。
- 共有タグ長押しは並び替えに使う。
- 権限があれば共有タグ名を変更できる。
- 権限に応じて削除/退出できる。
- 参加者/権限を表示する共有タグ詳細画面を持つ。
- グループ機能は残す。
- 下部バー左は `グループ`。
- グループ画面でグループ、共有タグ、メンバーを管理できる。
- 招待リンクで共有タグ/グループに参加できる。
- サインイン前の招待は保留し、サインイン後に参加できる。
- 同期失敗時は失敗表示と再同期ボタンを出す。
- 同期中は小さく状態表示する。
- `ChatGPT連携` 欄は今は非表示。
- `公開ページも同期` など未完成/未運用欄も非表示。
- 将来のAI/ChatGPT向けエクスポートや同期の余地は残す。

## Export And Backup Boundary
### Adopted
- 下部バーの `エクスポート` は残す。
- エクスポートはバックアップではなく、外部利用向け出力にする。
- 対象は保存中URL、アーカイブURL、テキストカード、自作タグ、共有タグ、メモ、投稿内容。
- メディアファイル本体は含めない。
- メディア保存済みかどうかは含める。
- Markdown、JSON、CSV を用意する。
- AI向けエクスポートではタイトル、URL、投稿内容、メモ、タグ、共有タグ、保存時刻を整理して出す。
- テキストカードもAI向けエクスポートに含める。
- 範囲選択を用意する。全件、保存中のみ、アーカイブのみ、現在の検索・タグ絞り込み結果のみ。
- 共有タグだけのエクスポートモードを残す。
- 共有タグの参加者情報は含めない。
- 削除予約中のURLは含めない。
- エクスポート後は共有シート/保存先選択で外部へ渡せる。
- バックアップ/復元は別機能として扱う。
- 将来のバックアップ/復元追加余地は残す。
- `ChatGPT連携` 表示は今は出さない。
- `AIに渡しやすい形式` のような表現は残す。
- エクスポート画面は形式、範囲、実行ボタン中心でシンプルにする。

### Deferred
- 本当のバックアップ/復元機能。

## Metadata And Post Content
### Adopted
- URL保存後、自動でタイトル、投稿主名、投稿内容、サムネイルを取得する。
- TikTok / Instagram / YouTube / X は通常ページより優先して専用 resolver で取得する。
- 投稿主名はカード上段や検索に使う。
- 投稿内容/キャプションは詳細画面に表示する。
- 投稿内容/キャプションは検索、エクスポート対象にする。
- サムネイルはカードに表示する。
- サムネイルがない時はサービスアイコンだけ表示する。
- メタデータ取得中はカード上で大きく目立たせない。
- メタデータ取得失敗時もカードは保存済みとして表示する。
- 取得失敗時は詳細画面で再取得ボタンを出す。
- メタデータは後から更新できる。
- メタデータ更新だけでは保存時刻を変えない。
- 投稿内容は短くても詳細画面では折りたたみ表示にする。
- 詳細画面では投稿内容をコピーできる。
- 投稿主名が取れない場合はサービス名/ホスト名で代替表示する。
- 認証が必要な場合は、認証付き resolver / 別方式を検討する。
- 投稿内容取得に失敗しても、メディア保存できるならメディア保存は試す。
- メタデータ取得状態とメディア保存状態は別管理にする。
- 投稿内容取得はバックグラウンド worker でも行う。

## Notifications And Errors
### Adopted
- 保存成功は `保存しました`。
- 複数件保存は `3件保存しました`。
- 一部失敗は `3件中2件保存しました`。
- 重複は `すでに保存済みです`。
- 重複時は `見る` / `詳細を開く` 導線を出す。
- アーカイブ復元は `アーカイブから戻しました`。
- 削除取り消しは `削除を取り消しました`。
- メタデータ取得失敗は一覧で大きく目立たせず、詳細で再取得可能にする。
- メディア保存成功は `メディアを保存しました`。
- メディア保存失敗は理由が分かる短い文言を表示する。
- オフラインでも保存はローカルで行い、後で取得する。
- オフライン時のメディア保存は後で再試行案内を出す。
- 共有タグ同期失敗は共有タグ画面内で表示する。
- 共有タグ同期失敗を毎回大きな警告ダイアログにはしない。
- 削除確認はタイトル/件数だけにする。
- エラー文は日本語で、次の行動が分かる文にする。
- 開発者向け詳細エラーはログ/詳細側に残し、通常UIには出しすぎない。
- 再試行できるものは再試行ボタンを出す。
- 取り消せる操作は必ず Undo を出す。
- Android/iPhone それぞれ自然な Toast/Snackbar/シート内表示にする。

## Settings, Profile, Sign-In
### Adopted
- プロフィール/設定画面は残す。
- サインイン状態を表示する。
- 未サインイン時は共有タグ用のサインイン案内を表示する。
- サインイン後はメールアドレスまたは表示名を表示する。
- 表示名を編集できる。
- アバター/プロフィール画像を表示する。
- アバター/プロフィール画像を編集できる。
- サインアウトできる。
- アカウント削除導線を表示する。
- アカウント削除時は共有タグクラウドデータの注意を表示する。
- 課金/プラン表示は出す。
- プラン状態の表示は出す。
- 未運用の購入ボタンや課金導線は、本番で使える状態になってから出す。
- 優待コード/プロモコード入力欄は用意する。
- 優待コードが本番で使えるなら表示する。使えない状態では通常UIに出さない。
- `ChatGPT連携` 欄は出さない。
- `公開ページも同期` 欄は出さない。
- AI/ChatGPT向けはエクスポート画面にだけ軽く出す。
- プライバシー/データ取り扱い説明は設定/使い方内に置く。
- 設定画面は共有タグ、アカウント、データ説明に絞る。
- 開発者向け設定やデバッグ情報は通常UIに出さない。

## DB And Migration
### Adopted
- Android実機データはユーザーデータとして扱う。
- DB schema 変更では保存済みURL、タグ、メモ、メディア情報を消さない migration を優先する。
- schema 変更時は Room schema JSON と migration テスト/起動確認を更新する。
- テキストカード導入時は `normalizedUrl` 前提を壊さない別種別設計にする。
- URLカードの duplicate key は引き続き `normalizedUrl`。
- テキストカードの duplicate key は本文完全一致。

### Requires Design
- メディア保存 tables と現在の schema 17/18/19 保護 migration の統合方針。
- テキストカードの DB モデルと URLカードとの共存方法。

## Backend And Media Resolver
### Adopted
- TikTok / Instagram / YouTube / X の投稿種別を判別し、動画/画像/複数画像を保存できるようにする。
- 外部サービス制限で失敗した場合も `できませんでした` で終わらせず、認証付き resolver、別方式、再試行へつなげる。
- Render デプロイ前に対象 branch と commit を確認する。
- 本番 URL の `/health` と代表 `/resolve` を確認する。

### Known Risk
- TikTok / Instagram / YouTube / X はログイン要求、地域制限、非公開投稿、期限切れURL、規約変更で失敗し得る。
- `絶対保存できる` は product goal とし、実装上は検証対象URLで成功するまで改善ループする。

## Adopted Rejection List
- ホーム上部の Info/ビックリマーク系アイコン。
- カード通常タップで外部URLを直接開く。
- タイトルとサービスアイコンで詳細/外部URLを分ける操作。
- 自作タグがあるカードで保存時刻を上段表示すること。
- タグ管理でリンク/JSON/文字付き削除を表示すること。
- 削除確認にURL/JSONなど技術情報を出すこと。
- メディアファイル本体をエクスポートに含めること。
- `ChatGPT連携` と `公開ページも同期` を通常UIに出すこと。
- 開発者向け設定やデバッグ情報を通常UIに出すこと。
- 実機データ削除で migration 問題を回避すること。
- 古い commit から file 全体コピーで復元すること。

## Deferred List
- 写真アプリへ直接保存。
- 本当のバックアップ/復元。
- AIによる要約、タグ候補、日付候補。
- テキストから日時/場所/金額/名前を専用UI/専用DB列として抽出する機能。
- Instagram/YouTube/X の認証付き resolver の具体方式。

## Verification Matrix
| Area | Required Checks |
| --- | --- |
| Mobile UI contract | `python3 scripts/verify_mobile_ui_contract.py` |
| Android UI/source | `./gradlew assembleDebug` |
| Android logic/DB | `./gradlew testDebugUnitTest` |
| Android lint when broad UI changes | `./gradlew lintDebug` |
| iOS source/UI | `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` |
| Android real device | package `jp.miyamibu.urlalbum`, no data clear, screenshot/XML proof |
| iPhone real device | bundle `com.mibu.codebridge.ios`, Appium/WDA UI proof when possible |
| Render backend | watched branch/commit, `/health`, representative `/resolve` |
| Commit | staged file list, `git diff --cached --check` |

## Initial Priority Buckets
### P0
- Preserve current source stability and regression contract.
- Resolve DB schema/migration direction before adding media tables or text-card schema.
- Keep Android/iPhone home/card/tag/detail UI from regressing.
- Ensure save/share flows do not lose user data.

### P1
- Restore and harden media save end to end.
- Implement text cards.
- Complete multi-tag save and share-extension parity.
- Complete search/filter parity across main/archive.

### P2
- AI-assisted title/tag/date suggestions.
- Photo library save.
- Full backup/restore.
- Advanced provider auth UX for resolver operations.

## Commit Plan
1. Commit this specification by itself.
2. Leave existing source diffs untouched for the next audit.
3. Next audit classifies current code as `matches spec`, `needs change`, or `blocked`.
4. Implementation proceeds in small commits per feature lane.
