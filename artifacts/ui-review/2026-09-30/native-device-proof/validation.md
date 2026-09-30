# りんばむ メニュー・ホーム色・手動追加UI 検証

Status: **PARTIAL**（Android実機確認済み。iPhoneの最新更新後に保存URL・タグが表示されないため、データ状態の調査と選択タグの目視確認が必要）

## 変更範囲

- ホーム専用のベージュ背景、上部・下部の背景色、中央＋の金色、右下AIの濃色。
- 右端・下端まで続く濃色メニュー。現行4項目はプロフィール、表示切替、選択、使い方。「データの取り扱い」のメニュー項目のみ外し、既存画面・データは削除しない。
- 下部「エクスポート」は1行全文表示。中央＋の位置・文字サイズは維持。
- 手動追加画面の小さな「＋」を「タグを追加する」文字ボタンへ変更し、タグ作成と割当の処理は維持。
- iPhone手動追加画面の選択中タグからチェックマークを外し、Androidに近い濃い水色の塗りと濃色の文字へ変更。

## Android

- 実機: Pixel 9a（ADBで一意識別）。保存データのあるPlayアプリ `jp.miyamibu.urlalbum` は署名が異なるため変更していない。
- 別IDのテスト専用アプリ `jp.miyamibu.urlalbum.menuui20260930` でホーム、右端メニュー、外側・右上ボタンでの閉操作、「使い方」、エクスポート画面を実機確認した。
- 現在の4項目／文字ボタン／ベージュのステータスバーを含む最終APK: `android-menu-test/rinbam-menuui20260930-v10.apk`。Pixelの別IDアプリへデータを消さずに更新した。
- 最終版の画像: `android-menu-test/home-v10.png`、`menu-v10.png`、`manual-tag-v10.png`、`tag-create-dialog-v10.png`、`usage-v10.png`、`home-after-usage-v10.png`。4項目、削除指定項目なし、上端と下端のベージュ、1行のエクスポート、タグ文字ボタンとその作成ダイアログ、使い方からの戻りを確認した。

## iPhone

- 実機: iPhone 12（物理UDID確認済み）、iOS 26.6.1、wired。Bundle ID `com.mibu.codebridge.ios`。端末識別子そのものはGitに含めない。
- 今回のソースをXcode 27で署名ビルドし、`devicectl device install app` で同一Bundle IDへ上書きインストールした。アンインストール・初期化なし。途中の実機画面では保存カードとローカルタグが見えていたが、最後の選択タグ色変更を入れた後には両方が表示されなくなった。CoreDevice databaseUUIDとApp GroupコンテナUUIDは同一だが、DB内容の保持は確認できていない。
- 既存WDAランナーをApple native tunnel経由で起動し、Appium/XCUITestセッションの物理UDIDと `window/rect=390×844` を確認した。
- Appium UI: 右上メニューは4項目、削除指定の項目なし。ホーム背景と上・下段、金色の中央＋、濃色AI、1行のエクスポートを画面で確認した。「使い方」の説明一覧を開き、各節と下部バー非表示、戻る操作を確認した。
- 手動追加画面の「タグを追加する」は 346×56 pt。押下でタグ作成ダイアログを開き、作成せずキャンセルできた。
- 最後の更新後、Appium/XCUITestのホーム画面には「保存したURLはまだありません」、手動追加画面には「タグがまだありません」と表示された。アクティブなアプリは `com.mibu.codebridge.ios`、フィルタは「すべて」選択中。ソース上では手動選択タグのチェックマーク非表示と青色背景を確認し、実機向けビルドも成功したが、タグが見えないため選択状態の実機目視は **NOT VERIFIED**。原因を断定せず、追加インストール・保存操作は停止した。
- スクリーンショットはプライベートなURLを含み得るため、Git作業ツリー外の `~/Library/Application Support/Codex/RinbamDeviceProof/menu-ui-20260930/` に保存した。主なファイルは `iphone-home-final4.png`、`iphone-menu-four-final.png`、`iphone-manual-tag-add-final.png`、`iphone-usage-four.png`。
- App GroupのDBを `devicectl device copy from` で事前・事後に取得しようとしたが、端末サービスのコンテナアクセス制限で拒否された。暗号化バックアップも開始したが、iPhoneがロック状態になり `Device locked (MBErrorDomain/208)` で終了した。端末解除待ちで、完成したバックアップはない。データ内容の変更・削除操作はしていない。

## ローカル検証

- `python3 scripts/verify_mobile_ui_contract.py`: PASS。
- Android `assembleDebug`、`lintDebug`、Java 21での `testDebugUnitTest`: PASS。
- iOS `swiftc -frontend -parse`、実機向けXcode署名ビルド: PASS。
- `git diff --check`: PASS。
- `connectedDebugAndroidTest` は通常の実機データを守るため実行していない。

## 境界

iPhoneの保存URL・タグが最後の更新後に表示されない原因と、選択タグの実機表示は未確認。Androidの通常Playアプリへの反映、Store提出・公開は行っていない。Gitへの記録・同期状態はこのファイルではなくGitのHEADとremote refで確認する。
