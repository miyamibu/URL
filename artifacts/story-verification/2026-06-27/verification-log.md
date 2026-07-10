# りんばむ 正準ストーリー検証ログ

- 作成日時: 2026-06-27 15:42:52 +0900
- 正準スプレッドシート: `docs/qa/rinbam_canonical_story_status.xlsx`
- CSVミラー: `docs/qa/rinbam_canonical_story_status.csv`
- 制約: 大きさデザイン変更は禁止。実機検証は正準IDで非破壊操作に限定。

## 予定検証

- Android: `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, `./gradlew assembleRelease`
- iOS: `xcodebuild ... build/test`, `xcodebuild ... -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`
- Web admin: `npm run typecheck`, `npm run build`
- Supabase: validation SQL/CLI 実行可能性確認
- 実機: Android `jp.miyamibu.urlalbum`, iPhone `com.mibu.codebridge.ios` の非破壊主要導線確認


## 実行結果 (2026-06-27 15:51:33 +0900)

- Android local: PASS `./gradlew assembleDebug testDebugUnitTest lintDebug assembleRelease`
- Android physical: PASS Pixel 9a `55211JEBF16639`, package `jp.miyamibu.urlalbum`; evidence `android-home.png`, `android-home-uiautomator.xml`, `android-usage.png`, `android-usage-uiautomator.xml`, `android-returned-home-uiautomator.xml`
- iOS local: PARTIAL/PASS compile. `xcodebuild ... Release ... build` 成功、`build-for-testing` 成功。`test` 実行は CoreSimulator version mismatch で未実施。
- iPhone physical: PARTIAL. UDID `00008101-00066D96340A001E`, bundle `com.mibu.codebridge.ios` installed/launched via `devicectl`; Appium/WDA source/screenshot failed: RemoteXPC tunnel missing, port 8100 refused, xcodebuild 65. UI operation proof is `NOT VERIFIED on physical iPhone`.
- Web admin: PASS `npm run typecheck && npm run build`.
- Supabase: BLOCKED_LOCAL `supabase db lint --local` failed because local Postgres `127.0.0.1:55422` refused connection. Linked/prod DB was not touched.
- Fix loop: コード上のロジスティカル/UXエラーは今回のローカル検証では未検出。デザインサイズ変更なし。修正なしのため再テスト対象なし。
