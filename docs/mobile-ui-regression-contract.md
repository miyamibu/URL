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
- タグ詳細/共有タグ詳細の上段にも Info/ビックリマーク系の説明アイコンを復活させない。
- 使い方は book/bookmark 系の既存導線を使う。
- 下部バーは `グループ` / `エクスポート` / 中央 `+` / `タグ` / `アーカイブ` を維持する。

### Local Tags On Cards
- 一覧カードは自作タグ割当をカードへ渡す。
- 自作タグが1件以上あるカードは、自作タグチップを表示する。
- 自作タグがある時は、サービス名、ショート種別、保存時刻をカード見出しとして出さない。
- 自作タグがないカードだけ、従来のサービス名/保存時刻表示へフォールバックする。

### Tag Rows
- ホーム上段のサービスフィルタと自作タグは、iPhone と同じ 1 本の横スクロール行に並べる。
- 自作タグの `+` 作成導線も同じ上段行の先頭に置き、`+` のみを表示する。
- 共有タグ行は自作タグ/サービスフィルタ行とは別に維持する。
- 共有タグ見出しの上、または共有タグ行の先頭に `+` 導線を維持する。
- タグチップは文字幅に応じた可変幅表示を基本にする。

### Tag Management
- 自作タグ管理に `リンク` / `JSON` の共有導線を戻さない。
- 自作タグ詳細に `リンク` / `JSON` の共有導線を戻さない。
- 削除操作はアイコン中心にし、管理リストへ大きな文字付き `削除` ラベルを復活させない。
- 自作タグ名の編集導線を壊さない。ダブルタップ/編集操作の既存導線がある場合は維持する。

### Detail Tag Sections
- 詳細画面のローカルタグ見出しは `自作タグ`。
- 詳細画面の共有タグ見出しは `共有タグ`。
- 両方の見出し横に編集ボタンを置く。

## Data / Schema Contract
- Android の実機データはユーザーデータとして扱う。`pm clear`、uninstall、destructive migration で消さない。
- 過去状態へコードを戻す場合でも、実機DBの schema が進んでいたら、保存済みデータを残す forward migration を先に用意する。
- schema 差分を吸収する必要がある場合は、追加列を落とすだけで済むかを確認し、URL/タグ本体を削除しない。

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
