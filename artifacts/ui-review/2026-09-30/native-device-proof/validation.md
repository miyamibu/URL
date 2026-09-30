# りんばむ メニュー・ホーム色・手動追加UI 検証

Status: **PARTIAL**（Android実機とiPhone上段の選択表示は確認済み。2026-10-01に既存URL・タグの削除は意図的と確認された。新しい提出物の検証とストア受付は未完了）

## 変更範囲

- ホーム専用のベージュ背景、上部・下部の背景色、中央＋の金色、右下AIの濃色。
- 右端・下端まで続く濃色メニュー。現行4項目はプロフィール、表示切替、選択、使い方。「データの取り扱い」のメニュー項目のみ外し、既存画面・データは削除しない。
- 下部「エクスポート」は1行全文表示。中央＋の位置・文字サイズは維持。
- 手動追加画面の小さな「＋」を「タグを追加する」文字ボタンへ変更し、タグ作成と割当の処理は維持。
- iPhoneのホーム上段と手動追加画面の選択中チップからチェックマークを外し、Androidと同じ `#67B0FF` の背景と濃色文字へ変更。

## Android

- 実機: Pixel 9a（ADBで一意識別）。保存データのあるPlayアプリ `jp.miyamibu.urlalbum` は署名が異なるため変更していない。
- 別IDのテスト専用アプリ `jp.miyamibu.urlalbum.menuui20260930` でホーム、右端メニュー、外側・右上ボタンでの閉操作、「使い方」、エクスポート画面を実機確認した。
- 現在の4項目／文字ボタン／ベージュのステータスバーを含む最終APK: `android-menu-test/rinbam-menuui20260930-v10.apk`。Pixelの別IDアプリへデータを消さずに更新した。
- 最終版の画像: `android-menu-test/home-v10.png`、`menu-v10.png`、`manual-tag-v10.png`、`tag-create-dialog-v10.png`、`usage-v10.png`、`home-after-usage-v10.png`。4項目、削除指定項目なし、上端と下端のベージュ、1行のエクスポート、タグ文字ボタンとその作成ダイアログ、使い方からの戻りを確認した。

## iPhone

- 実機: iPhone 12（物理UDID確認済み）、iOS 26.6.1、wired。Bundle ID `com.mibu.codebridge.ios`。端末識別子そのものはGitに含めない。
- 今回のソースをXcode 27で署名ビルドし、`devicectl device install app` で同一Bundle IDへ上書きインストールした。アンインストール・初期化なし。以前の実機画面では保存カードとローカルタグが見えていたが、前回の更新後から両方が表示されなくなり、今回の更新後も戻っていない。CoreDevice databaseUUIDとApp GroupコンテナUUIDは同一だが、DB内容の保持は確認できていない。
- 既存WDAランナーをApple native tunnel経由で起動し、Appium/XCUITestセッションの物理UDIDと `window/rect=390×844` を確認した。
- Appium UI: 右上メニューは4項目、削除指定の項目なし。ホーム背景と上・下段、金色の中央＋、濃色AI、1行のエクスポートを画面で確認した。「使い方」の説明一覧を開き、各節と下部バー非表示、戻る操作を確認した。
- 手動追加画面の「タグを追加する」は 346×56 pt。押下でタグ作成ダイアログを開き、作成せずキャンセルできた。
- 今回のAppium/XCUITestでは物理iPhoneの `window/rect=390×844`、正規アプリの前面表示を確認した。「すべて」から「X」へ選択を切り替え、両方とも濃い水色でチェックマークなし、アクセシビリティ値は選択中／未選択に更新された。ホームには引き続き「保存したURLはまだありません」と表示され、自作タグがないため手動追加画面の自作タグ選択状態は **NOT VERIFIED on physical iPhone**。
- スクリーンショットはプライベートなURLを含み得るため、Git作業ツリー外の `~/Library/Application Support/Codex/RinbamDeviceProof/menu-ui-20260930/` に保存した。前回の `iphone-home-final4.png`、`iphone-manual-tag-add-final.png` に加え、今回の `iphone-home-selected-chip-new.png`、`iphone-home-filter-x-selected-new.png` を取得した。
- App GroupのDBを `devicectl device copy from` で取得する経路は端末サービスのコンテナアクセス制限で拒否された。最初の暗号化バックアップはロック状態で失敗し、USBでの最初の再試行も通信切断で未完了。その後の再試行は終了コード0で完了し、`~/Library/Application Support/Codex/RinbamDeviceBackups/menu-ui-20260930-retry/` に約49GBの暗号化バックアップを保持している。`Status.plist` は `finished`、`Manifest.db` は作成済み。バックアップ内のDB内容は未確認で、データ内容の削除操作はしていない。

## ローカル検証

- `python3 scripts/verify_mobile_ui_contract.py`: PASS。
- Android `assembleDebug`、`lintDebug`、Java 21での `testDebugUnitTest`: PASS。
- iOS `swiftc -frontend -parse`、実機向けXcode署名ビルド: PASS。
- `git diff --check`: PASS。
- `connectedDebugAndroidTest` は通常の実機データを守るため実行していない。

## iPhoneの保存データ調査（2026-09-30）

- 端末データを消さずに診断用Debugビルドを同じBundle IDへ入れ、App Groupの正規DBを読み取り確認した。App Groupアクセスは有効、正規DBは存在し整合性検査に通るが、現在の `url_entries` と `local_tags` は各0件。復旧用DBは存在しない。診断用コードはリリース成果物へ含めない。
- 正規DB本体とWALの原本コピーをGit外の端末証拠フォルダに保持した。WAL履歴より前のDBコピーには、削除待ちのURL35件、自作タグ2件、割当8件がある。削除待ち時刻は9月30日18:11頃で、後続のWALにはURLとタグが順次削除された履歴がある。誰のどの操作が起点かは未確認。
- コピー上でURL35件をACTIVEに戻した復元候補を作り、`integrity_check=ok`、外部キー違反0件を確認した。現在のDBの論理コピーへ3表だけを追加する予行演習は成功し、その他13表の内容ハッシュは一致した。**実機の正規DBへはまだ書き戻していない。**
- Androidの狭幅・拡大文字時のエクスポート1行表示と、両ストア用の現行ソース署名成果物・端末データ保持確認も未完了。この調査時点ではストア提出を `NO-GO` とした。

## 2026-10-01 のユーザー確認と提出準備

- 既存URLと自作タグの削除はユーザーの意図によるものと確認された。35件・2タグ・8割当の復元は行わない。暗号化バックアップと読み取り専用の調査コピーは保持する。
- Androidの既存実機確認はユーザーが了承した。エクスポートを1行に固定する後続のAndroidソース差分はビルド・関連テスト・Lintに通過済みで、別IDテストAPKを保持している。
- 一時的なiPhone診断・復元ガードのコードは最終ソースから外した。ストア提出用の署名ビルド、版番号更新、受付確認は別途実施する。
- Android/iOSの提出候補は `1.0.23 (38)`。ユーザーはAndroidの既存実機確認を了承し、削除済みURL・タグは復元しないよう指定した。
- iPhone 12の正規Bundle IDへ診断コードを含まない署名済みDebug `1.0.23 (38)` をデータ消去なしで上書きした。Appium/XCUITestの実機 `390×844` で、上・下端のベージュ、中央＋の位置と金色、AIの濃色、エクスポート1行、青色でチェックなしの選択チップを目視した。右端から下端までの4項目メニューと「使い方」の説明一覧・戻り、下部バー非表示を確認した。
- 実機の手動追加画面で「タグを追加する」文字ボタン `346×55pt` を押し、タグ作成ダイアログを確認して作成せずキャンセルした。既存タグは意図的に削除済みのため、手動追加画面で選択中の自作タグチップの目視だけは未確認。共通コンポーネントの上段チップ選択状態は実機で確認済み。
- 新しいスクリーンショットはGit外の `~/Library/Application Support/Codex/RinbamDeviceProof/menu-ui-20261001/` に保存した。主なファイルは `iphone-1.0.23-home.png`、`iphone-1.0.23-menu.png`、`iphone-1.0.23-usage.png`、`iphone-1.0.23-manual-tag.png`。

## 境界

iPhoneの手動追加画面での自作タグ選択表示は未確認。ホーム上段の共通チップの選択表示は実機確認済み。Androidの通常Playアプリへの反映、最新ソースのStore提出・公開は行っていない。Gitへの記録・同期状態はこのファイルではなくGitのHEADとremote refで確認する。
