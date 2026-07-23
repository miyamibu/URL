# Mobile UI Regression Contract

## Goal
Android/iPhone のホーム、カード一覧、タグ管理、詳細タグ欄を、過去に戻したはずの表示へ巻き戻さない。

## Context
2026-06-30 の復元作業で、保存修正計画後に以下の戻りが混ざった。

- ホーム上部に不要な Info 系アイコンが復活した。
- 一覧カードへ自作タグの割当が渡らず、サービス名/保存時刻表示へ戻った。
- 自作タグ行と共有タグ上の `+` 導線が消えた。
- タグ管理に `リンク` / `JSON` / 文字付き `削除` が出た。
- 詳細画面のローカルタグ見出しが `自作タグ` ではなく `タグ` に戻った。
- Android 実機DBが schema 19 のまま、復元コードが古い schema へ戻り、起動時に migration missing で落ちた。

## Canonical Route
この領域を触る変更は、次の一本道を通す。

1. `AGENTS.md` を読む。
2. `.agents/skills/rinbam-single-route/SKILL.md` を読む。
3. この契約を読む。
4. 変更前に `python3 scripts/verify_mobile_ui_contract.py` を実行し、既存の崩れを先に把握する。
5. Android/iOS の片側だけを直す場合も、対応する反対側の契約を壊していないか確認する。
6. Room/SQLite schema を触る、または過去コミットへ復元する場合は、実機DBの `user_version` と migration 経路を確認する。
7. 変更後に `python3 scripts/verify_mobile_ui_contract.py`、Android build/test/lint、iOS build/test のうち影響範囲に最も近いものを実行する。
8. 実機確認は、Android adb 証跡、iPhone install/launch、iPhone Appium/WDA UI 証跡を混ぜずに報告する。

## UI Contract

### Home / Main
- Android の canonical app id は `jp.miyamibu.urlalbum`。
- iOS の canonical bundle id は `com.mibu.codebridge.ios`。
- ホーム上部に不要な Info/データ取り扱いアイコンを復活させない。
- ホーム上部にハンバーガーメニューを置き、メニュー内からプロフィール/共有タグクラウド画面を開けるようにする。
- 表示モード、選択、使い方、データの取り扱いもホーム上部のハンバーガーメニューにまとめる。
- 検索は再発見性のため上部に直接表示し、ハンバーガーメニューとは分ける。
- タグ詳細/共有タグ詳細の上段にも Info/ビックリマーク系の説明アイコンを復活させない。
- 使い方は book/bookmark 系の既存導線を使う。
- 下部バーは `グループ` / `エクスポート` / 中央 `+` / `タグ` / `アーカイブ` を維持する。

### Local Tags On Cards
- 一覧カードは自作タグ割当をカードへ渡す。
- 自作タグが1件以上あるカードは、自作タグチップを表示する。
- 自作タグがある時は、サービス名、ショート種別、保存時刻をカード見出しとして出さない。
- 自作タグがないカードはサービス名/ショート種別のみへフォールバックし、保存時刻と右側のメタデータ状態ドットは表示しない。

### Tag Rows
- ホーム上段のサービスフィルタと自作タグは、iPhone と同じ 1 本の横スクロール行に並べる。
- 自作タグの `+` 作成導線も同じ上段行の先頭に置き、`+` のみを表示する。
- 共有タグ行は自作タグ/サービスフィルタ行とは別に維持する。
- 共有タグ行と `+` 導線は、クラウド未設定・未サインイン・0件の状態でもホームに表示する。
- 共有タグ見出しの上、または共有タグ行の先頭に `+` 導線を維持する。
- タグチップは文字幅に応じた可変幅表示を基本にする。

### Tag Management
- 自作タグ管理に `リンク` / `JSON` の共有導線を戻さない。
- 自作タグ管理一覧には共有導線を追加しない。
- 自作タグ詳細には、ユーザー向けの技術語を使わない `自作タグを共有` の導線を1つだけ置いてよい。
- `自作タグを共有` は共有前にタグ名、対象URL件数、含まれない情報を確認し、OS標準の共有先選択を開く。
- 新規共有データはタグ名とURLだけを出力し、タイトル、メモ、共有タグ情報、取得本文を含めない。旧データのタイトル/メモは受信互換としてのみ扱う。
- このタグ詳細からの共有は、エクスポート画面の `ChatGPTに聞く` とは別機能。ChatGPT用ZIPには AI-safe Export Contract を適用する。
- `リンク` / `JSON` / `payload` はユーザー向けラベルとして表示しない。
- 削除操作はアイコン中心にし、管理リストへ大きな文字付き `削除` ラベルを復活させない。
- 自作タグ名の編集導線を壊さない。ダブルタップ/編集操作の既存導線がある場合は維持する。

### ChatGPT Personal Link Sync
- プロフィールに `ChatGPTに保存リンクを同期` の状態カードを置く。ホームや下部バーへ新しい導線を追加しない。
- 外部接続の確認が完了していないビルドでは、カードを表示しても操作は無効にし、`現在は利用できません` と理由を表示する。
- 同期対象は、端末で保存した ACTIVE のURLのうち、共有参照がなく、削除待ちでないものだけとする。
- 取得本文、raw prompt、token、secret、local path を同期データ、Receipt、永続ログへ含めない。本文取得の設定UIを追加しない。
- 同期を有効にする前と手動同期前に、対象件数と除外件数を示して確認を取る。

### ChatGPT Manual Handoff

- 正式な入口は下部バーの `エクスポート` 画面内の `ChatGPTに聞く`。ホームや下部バーに6個目の操作は追加しない。
- ユーザーは自作タグを1件以上選び、複数選択時はいずれかのタグに該当するURLを対象とする。共有タグは選択肢に出さない。
- ZIP生成前に、伏せ字適用後の対象URLと出力内容、対象件数、除外件数と理由を画面で確認できるようにする。既知のemail/phone/token-like/Supabase/JWT/local-pathパターンは伏せ字にする。
- ChatGPT用ZIPに含めるのは、端末で保存した ACTIVE のURLのうち、削除待ちでなく、共有参照を持たないものだけ。対象が0件ならZIPを生成しない。
- 既知パターン検出は未知の秘密まで保証しない。ユーザーにその境界を示し、previewを確認して未知の秘密が含まれていないことを明示確認するまではZIP生成/共有を許可しない。
- Google Doc第13章の34項目は、添付後にChatGPTへ依頼できる活用例であり、りんばむ内の34機能ではない。ChatGPT用ZIPのREADMEには1〜34を個別列挙し、契約テストで全項目を照合する。りんばむの責務は自作タグ選択、対象/出力内容確認、ZIP生成、OS共有までとする。
- りんばむに質問入力欄を作らない。質問文の自動入力、自動送信、OpenAI API/OAuth/login、MCP/provider接続も行わない。ChatGPT個人リンク同期/read-only MCPとは別機能として扱う。
- Androidの `ChatGPTに送る` はChatGPTアプリへの直接共有を試し、利用できない場合はOS標準の共有先選択へフォールバックする。
- iPhoneの `ChatGPTに送る` はOS標準の共有シートを開く。共有先の選択、ChatGPTでの質問入力、送信はユーザーが行う。
- 通常のZIP/JSONエクスポートの範囲、保存先選択、共有導線は維持する。

### AI Transparency
- AI Preview / Receipt / Draft / Diff は Debug かつ明示flag有効時だけプロフィールから開ける。
- Releaseでは入口を表示せず、外部AI providerへ接続しない。
- Debug画面は端末内Mockであることを明示し、Receiptにはmetadataとサイズ区分だけを残す。
- Diff適用はbefore/afterを表示して確認を取り、適用直前の状態・適格性・文字数・before一致を再検証する。再検証に失敗した場合は1件も更新しない。

### Detail Tag Sections
- 詳細画面のローカルタグ見出しは `自作タグ`。
- 詳細画面の共有タグ見出しは `共有タグ`。
- 両方の見出し右側に編集ボタンを置く。見出しの下に編集ボタンを縦積みしない。
- Android の詳細タグ見出し `自作タグ` / `共有タグ` は、文字サイズを調整して 1 行で表示する。

### Media Viewer
- メディア表示シートのタイトルは 1 行で表示し、入りきらない文字は横スクロールで確認できるようにする。
- メディア本体の下に、保存ファイル名、`image` / `video` / MIME type / provider id などの英語由来の内部文字列を表示しない。
- Android の複数メディア点インジケータは、写真/動画ページごとの高さに追従させず、同じカルーセル内で見える位置に縦固定する。
- Android のメディア枠は、写真から動画へ横スワイプした瞬間に点インジケータや外側レイアウトが再配置されないよう、カルーセル内で安定した枠を使う。

## Data / Schema Contract
- Android の実機データはユーザーデータとして扱う。`pm clear`、uninstall、destructive migration で消さない。
- 過去状態へコードを戻す場合でも、実機DBの schema が進んでいたら、保存済みデータを残す forward migration を先に用意する。
- schema 差分を吸収する必要がある場合は、追加列を落とすだけで済むかを確認し、URL/タグ本体を削除しない。
- 退役する Collection / 保存先 / Android UserLabel の既存テーブル、列、過去migrationはDB互換殻として保持する。
- Collection / UserLabel の作成、改名、並べ替え、削除、割当、保存先選択、ローカルタグ連携をactive UI/業務経路へ戻さない。
- 退役コードは `.repo-trash/20260713/collections-userlabels/` に元パスと復元手順を記録し、DBの既存行は書き換えたり削除したりしない。

## Verification
必須の静的ガード:

```bash
python3 scripts/verify_mobile_ui_contract.py
```

Android 変更時:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

iOS 変更時:

```bash
xcodebuild -workspace ios/URLSaveriOS.xcworkspace -scheme URLSaveriOS -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' build
```

実機確認をした場合は、端末、package/bundle id、インストール時刻、前面Activity/画面証跡、未検証事項を分けて報告する。

## Failure Handling
- 静的ガードが落ちたら、先に契約違反を直す。
- ユーザーの新しい明示指示がこの契約と衝突する場合は、コードを変える前にこの契約も同じ差分で更新する。
- 古いコミットへ戻す作業では、`git checkout --` / broad restore で進めず、対象ファイルと戻す理由を明示し、DB/schema差分を確認する。
