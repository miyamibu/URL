# Validation Log — 2026-08-24 ox-alpha

実行環境: macOS (darwin), workdir `/Users/mimac/Desktop/りんばむ`
基準コミット: `eda2f2678c16bfbbda56d398c613012376993e76` (codex/修正)

| # | 区分 | コマンド | 結果 | 証跡 |
| --- | --- | --- | --- | --- |
| V01 | 変更前 契約 | `python3 scripts/verify_mobile_ui_contract.py` | PASS (exit 0) | 本ログ記載の通り端末出力確認済み |
| V02 | baseline test | `./gradlew testDebugUnitTest` (既定JDK=Homebrew openjdk 17.0.18) | **19クラス classMethod FAIL** — `Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)` at DefaultSdkProvider.java:170 | /tmp/gradle-test-baseline.log |
| V03 | 原因分離 | `java -version`, `/usr/libexec/java_home -V`, `.java-version` | 既定java=17.0.18 / `.java-version=21` / README L191に同現象の文書化あり → **環境由来。コード差分ゼロ時点で発生(今回差分由来ではない)** | - |
| V04 | baseline test(JDK21) | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest` | BUILD SUCCESSFUL (1m09s) | /tmp/gradle-test-java21.log |
| V05 | baseline lint/build | 同JAVA_HOMEで `lintDebug assembleDebug` | BUILD SUCCESSFUL (58s) | /tmp/gradle-lint-baseline.log |
| V06 | toolchain試行 | jvmToolchain(21)追加後 `env -u JAVA_HOME ./gradlew testDebugUnitTest` | FAIL: `No locally installed toolchains match ... languageVersion=21` → **差し戻し判断** | 端末出力 |
| V07 | 修正後 test | JAVA_HOME=21で `testDebugUnitTest assembleDebug lintDebug` | **BUILD SUCCESSFUL (1m46s)** | 端末出力 |
| V08 | テスト集計 | test-results XML解析 | **tests=358 failures+errors=0 skipped=0** | app/build/test-results/testDebugUnitTest/ |
| V09 | 変更後 契約 | `python3 scripts/verify_mobile_ui_contract.py` | PASS (exit 0) | 端末出力 |
| V10 | 差分確認 | `git status --porcelain` / `git diff --stat` | 変更は ShareReceiverActivity.kt のみ(+6/-5)。`artifacts/ui-review/2026-08-11/`(未追跡)は非接触を確認 | git出力 |

## NOT EXECUTED / BLOCKED (正直な限界)

| 項目 | 状態 | 理由 |
| --- | --- | --- |
| `connectedDebugAndroidTest` | NOT EXECUTED | 指示により禁止(実機データ保護) |
| iOS xcodebuild build/test | NOT EXECUTED | ios/ 配下を変更していないため契約上任意。静的parity確認のみ |
| 実機/Simulator UI操作証跡 | NOT EXECUTED | 本セッションの許可範囲外(SR-01の回転再現を含む) |
| `scripts/verify_shared_url_normalization_contract.py` | BLOCKED | ローカルPostgres未起動 (`psql: connection refused`) |
| supabase local stack検証 | NOT EXECUTED | 同上。migration適合のSQL実行検証は未実施 |
| web/admin runtime・prod設定・本番シークレット | NOT EXECUTED | 本番操作禁止。静的読査のみ |
| Store提出物/OAuth/deep link autoVerify実配信 | NOT EXECUTED | 外部権限が必要(残存ゲート) |
