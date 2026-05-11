# URL Saver (Android, Phase 1a/1b)

共有された URL を「あとで開き直す」ために保存する Android ネイティブアプリです。

## Target User
SNS やメッセージで見つけた URL を後で見返したい人向けの、軽量な「あとで開く」保存アプリです。

## Core User Value
共有や手動入力で URL を迷わず保存し、保存後は Main / Archive / Detail で状態を理解しながら再オープンできます。

## Key Principles
- 保存主対象は URL
- 重複判定主キーは `normalizedUrl`
- `openUrl = normalizedUrl` (Phase 1a 固定)
- 保存時刻表示は `createdAt` を基準に統一
- `userTitle` は `fetchedTitle` より優先
- 一覧カードタップは詳細遷移のみ (直接外部起動しない)
- Main の active 一覧は右スワイプでアーカイブ、左スワイプで削除猶予 + Undo
- 検索機能は Phase 1a/1b の対象外
- `ACTION_SEND_MULTIPLE` は複数 URL を順次保存し、集計結果を通知する

## Metadata Content Contract (Current)
- 保存契約: `fetchedTitle` / `fetchedBody` / `bodySummary` / `thumbnailUrl` / `canonicalId` / `normalizedHost` / `rawSourceHost`
- `fetchedBodyKind` で本文の取得元種別（例: `WEB_DESCRIPTION` / `WEB_EXCERPT`）を保持し、Web の表示ラベル判定はこの種別を優先する
- `bodySummary` は取得時に rule-based 生成して DB 保存する（UI 側 truncate に依存しない）
- `title/body/thumbnail` のうち一部でも取得できれば `READY` の部分成功として扱う
- 本文が取得できない場合は `fetchedBody = null` のまま保持し、Detail で制限を明示する
- サービス別方針（無料・APIキーなし）:
  - X: 公式 oEmbed -> syndication fallback（FixupX/TwStalker/Nitter など第三者ビューアには依存しない）
  - YouTube: oEmbed title + 埋め込み script (`shortDescription` / `simpleText`) -> JSON-LD -> meta description
  - Instagram: `instagram.com/api/v1/oembed` の公開 oEmbed -> `embed/captioned` の公式埋め込み HTML -> Meta `instagram_oembed`（token 設定時補助） -> JSON-LD -> 埋め込み JSON -> OG description 引用抽出
  - Web: `og:title/<title>` + `meta description` 優先、空なら `article/main/p` 抽出
- 表示ラベル:
  - YouTube: `概要欄` / `概要欄の要点`
  - Instagram: `キャプション` / `キャプションの要点`
  - X: `投稿内容` / `投稿内容の要点`
  - Web: `概要` または `本文抜粋`（抽出内容に応じて切替）
- 制約:
  - Instagram は公開投稿でも、通常HTMLがログアウト向けエラーシェルを返すことがある。その場合は public oEmbed と `embed/captioned` の公式埋め込み HTML を順に試す
  - それでも caption が返らない投稿は、公開範囲・年齢制限・地域制限・Instagram 側レスポンス制約により、本文を常に保証できない
  - X の Wayback Machine は「投稿削除時の閲覧救済リンク」としてのみ扱い、主取得経路には使わない
  - API キー未設定でも動作し、取得できない場合は service-aware 文言で graceful fallback する

## Release Gate (Binary)
- `./gradlew assembleDebug` が成功する
- `./gradlew testDebugUnitTest` が成功する
- `./gradlew lintDebug` が成功する
- `docs/phase1b-ux-checklist.md` で P0 の確認状態が実測に基づいて記録されている
- 新規インストールの基本導線（起動 -> 保存 -> 詳細遷移）が silent failure なしで完了する
- Crash reporting は本リリースでは「未導入で公開可」とする運用判断を文書化済み

## Tech Stack
- Kotlin
- Jetpack Compose + Material3
- Navigation Compose
- Room
- ViewModel + Coroutines/Flow
- WorkManager
- Supabase / Postgres / Auth（shared tag sync v1 の内部実装。初回の契約前 release build では無効化できる）

## Shared Tag Invite Sync MVP
- 共有対象は `共有タグ` に入っている URL 一覧だけです。通常の保存 URL やローカル専用タグは自動共有されません。
- 新規タグはデフォルトでローカル作成です。クラウド共有したいときだけ、明示的に共有タグをクラウドへ移行します。
- 同一端末向けのローカルリンクは従来どおり `urlsaver://tag/{tagId}` を使います。これは cross-device 共有には使いません。
- 別ユーザー・別端末の参加は `urlsaver://invite/{token}` の招待リンクで行います。招待リンクはオーナーだけが発行できます。
- 招待参加やクラウド共有への移行には Supabase Auth でのサインインが必要です。
- 公開向け `release` では、運用用 Supabase 設定と web deletion route をそろえたときだけ shared tag cloud / Supabase Auth を有効化してください。未設定のままなら cloud 機能は自動で無効のままです。
- repo には shared-tag cloud 向けの account deletion RPC と Android / iOS の in-app 削除導線を追加済みです。公開面へ戻す前に、public web deletion route の配信と本番設定の投入をそろえてください。
- 設定済みの internal/debug ビルドでは shared tag invite 参加と Supabase Auth を検証できます。
- MVP で「同じ内容」とみなすのは URL 一覧のみです。`title`、`memo`、metadata、サムネイル取得状況は端末ごとに少し差が出ることがあります。
- 最新化は WorkManager ベースです。共有タグの変更時に加え、アプリの起動/復帰時にも軽いスロットル付きで同期を enqueue します。リアルタイム push は必須にしていません。

## AI-friendly Export
- Android では保存済み URL を AI-friendly ZIP としてエクスポートできます。
- エクスポート範囲:
  - 全件
  - 単一タグ
  - 複数タグ
  - 共有タグのみ
- 追加フィルター:
  - `ACTIVE / ARCHIVED / BOTH`
  - サービス別
  - メモありのみ
  - 作成日範囲
- ZIP の最低構成:
  - `manifest.json`
  - `entries.jsonl`
  - `entries/*.md`
- 復元用バックアップよりも、PC / Codex が読みやすい知識スナップショットを優先した形式です。

## Build Prerequisites
- Java 17（`./gradlew --version` で JVM 17 系を確認）
- Android SDK Platform 35
- Android SDK Build-Tools / Platform-Tools / Command-line Tools
- `local.properties` の `sdk.dir` が有効であること
- Instagram oEmbed 補助を使う場合のみ、`local.properties` に `instagram.oembed.access.token=<Meta access token>` を設定する
  - 前提: Meta app + access token + **Meta oEmbed Read** が有効であること
  - 未設定時でも `instagram.com/api/v1/oembed` と `embed/captioned` による公開投稿の caption / thumbnail / 投稿者アイコン取得を先に試す
- shared tag sync を internal/debug ビルドで有効にする場合のみ、`local.properties` に以下を設定する
  - `supabase.url=https://<project-ref>.supabase.co`
  - `supabase.anon.key=<supabase publishable or legacy anon key>`
  - 未設定時は shared tag sync を開始せず、既存のローカル共有タグ / JSON export-import をそのまま使う
  - 設定済み build variant では shared tag invite 参加時に Supabase Auth を使う
  - Android の `release` build は、`release.shared.tag.cloud.enabled=true` のときだけクローズドβ/本番向け Supabase 設定を必須にしている。`local.properties` または環境変数に以下を設定する
    - `release.supabase.url=https://<project-ref>.supabase.co`
    - `release.supabase.anon.key=<supabase publishable or legacy anon key>`
    - `release.shared.tag.cloud.enabled=true`
    - または `URLSAVER_RELEASE_SUPABASE_URL` / `URLSAVER_RELEASE_SUPABASE_ANON_KEY` / `URLSAVER_RELEASE_SHARED_TAG_CLOUD_ENABLED=true`
    - `service_role` / `sb_secret` などの secret key は mobile app に入れない
    - 契約前の local-only release build では `release.shared.tag.cloud.enabled` を未設定または `false` にし、shared tag cloud / Supabase Auth を公開面へ出さない
  - βの招待リンクの正本は `https://miyamibu.xyz/invite/{token}`。変更が必要な場合のみ `invite.link.base.url` または `URLSAVER_INVITE_LINK_BASE_URL` を設定する
  - iOS では `ruby ios/generate_xcodeproj.rb` 実行前に以下の環境変数を渡す
    - `URLSAVER_SHARED_TAG_CLOUD_ENABLED=true`
    - `URLSAVER_SUPABASE_URL=https://<project-ref>.supabase.co`
    - `URLSAVER_SUPABASE_ANON_KEY=<supabase publishable or legacy anon key>`
    - `URLSAVER_INVITE_LINK_BASE_URL=https://miyamibu.xyz`
    - CLI Archive/TestFlight で cloud-sharing build を作る場合だけ、`ios/Config/URLSaverSecrets.xcconfig.example` を `ios/Config/URLSaverSecrets.xcconfig` にコピーし、実値を入れて `xcodebuild ... -xcconfig ios/Config/URLSaverSecrets.xcconfig archive` のように渡す。実値入り xcconfig は gitignore 済み。
    - local-only v1.0 の App Store / Release 検証では、dev Supabase 向け `ios/Config/URLSaverSecrets.xcconfig` を `-xcconfig` で渡さない。必要なら `ios/Config/URLSaverSecrets.local-only.xcconfig` を渡し、`URLSAVER_SHARED_TAG_CLOUD_ENABLED=false` と空の Supabase URL/key を確認する。
- `connectedDebugAndroidTest` 実行時は接続済み端末または起動済み AVD が必要

## Ads (AdMob / Debug-safe)
- 実装は Google Mobile Ads SDK（`com.google.android.gms:play-services-ads`）を使用
- debug ビルドは Google 公式 test ad unit を固定使用（本番 ID 不要）
  - App ID: `ca-app-pub-3940256099942544~3347511713`
  - Banner: `ca-app-pub-3940256099942544/9214589741`
  - Interstitial: `ca-app-pub-3940256099942544/1033173712`
- 公開向け `release` ビルドは ads を常時無効化する
  - `ADS_ENABLED=false`
  - Google Mobile Ads SDK は `compileOnly` + `debugImplementation` に限定し、release APK へ同梱しない
  - release manifest から AdMob App ID / ad permissions / ad provider 宣言を除去する
  - 本番広告を再開する場合は、別パスで App ID・privacy・審査要件をそろえてから有効化する
- internal/debug で広告動作を試す場合だけ test ad unit を使う
- `MobileAds.initialize()` はアプリ起動時に1回のみ呼び、初期化後に広告ロードする

## AdMob Mediation Notes
- AdMob 管理画面で行う設定（アプリコード外）
  1. Android アプリに Banner / Interstitial の標準 ad unit を作成する
  2. Banner 用と Interstitial 用で mediation group を分ける
  3. Platform は Android、配信地域は Japan を設定する
  4. AdMob Network / Unity / AppLovin / Ad Generation を用途に応じて group に追加する
- Ad Generation は AdMob 側の bidding source なので、通常はアプリ側 SDK 追加は不要
- AppLovin の Banner 可否は時期で変わるため、実運用前に AdMob UI と公式資料で再確認する
- 現在のリポジトリ（Kotlin 1.9 / AGP 8.4）では最新 mediation adapter の一部が Kotlin 2.x metadata を要求するため、adapter 依存は保留にしている

## Commands
- `./gradlew assembleDebug`
- `./gradlew testDebugUnitTest`
- `./gradlew lintDebug`
- `./gradlew connectedDebugAndroidTest`（手動検証。CI hard gate ではない）
- `psql -h <socket-or-host> -d postgres -f supabase/tests/shared_tag_sync_validation.sql`（Supabase SQL migration / RPC / validation のローカル確認）
- 契約前 release dry run: `./gradlew assembleRelease`（`release.shared.tag.cloud.enabled=false` または未設定）

## 実機インストール（USB / LAN）
- 端末へ debug APK を入れて起動する標準コマンド:
  - `./scripts/install_on_device.sh`
- 複数デバイス接続時に対象を固定:
  - `./scripts/install_on_device.sh --serial <adb-serial>`
- 無線デバッグ（LAN）時に connect まで一緒に実行:
  - `./scripts/install_on_device.sh --connect <ip:debug-port>`
- エミュレータを明示的に対象にする場合のみ:
  - `./scripts/install_on_device.sh --allow-emulator`
- 署名不一致や version downgrade で失敗した場合に自動復旧:
  - `./scripts/install_on_device.sh --serial <adb-serial> --force-reinstall`

無線デバッグ初回ペアリング:
1. 端末側で「開発者向けオプション > ワイヤレスデバッグ > ペア設定コードでペア設定」を開く
2. PC 側で `adb pair <ip:pairing-port>` を実行し、表示コードを入力
3. `adb connect <ip:debug-port>` を実行
4. `adb devices -l` に `device` 状態で表示されることを確認
5. `./scripts/install_on_device.sh --serial <adb-serial>` でインストール・起動

## CI / Instrumentation Policy
- GitHub Actions CI は `assembleDebug` / `testDebugUnitTest` / `lintDebug` のみ実行する
- `connectedDebugAndroidTest` はローカル手動検証として実行し、結果を記録する

## Failure Triage
- `JVM` が 17 以外: Java 17 を選択し、`./gradlew --version` で再確認する
- SDK 関連エラー: `local.properties` の `sdk.dir` と SDK Platform 35 / Build-Tools を確認する
- `connectedDebugAndroidTest` が失敗: 端末/AVD 接続状態を確認し、`adb devices` で認識を確認する
- `No connected devices!`: 端末未接続またはエミュレータ未起動。接続後に再実行する
- `assembleDebug` は成功するのに実機へ入らない:
  - `adb devices -l` で実機が `device` になっているか確認（`offline`/`unauthorized` は不可）
  - まず `./scripts/install_on_device.sh --serial <adb-serial>` を実行し、失敗理由を表示させる
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE` は旧版アンインストール後に再実行（`adb uninstall jp.mimac.urlsaver`）
- `NoSuchMethodException: android.hardware.input.InputManager.getInstance`: 既知の instrumentation blocker。テストコード変更とは別問題として記録し、pass 扱いしない

## Release Build Policy
- Phase 1a/1b は `release` の `isMinifyEnabled=false` を維持する
- 機能整合修正と shrinker/R8 調整を分離するため、minify 有効化は別ハードニングパスで扱う

## Verification Status (2026-04-23)
- `./gradlew assembleDebug`: success
- `./gradlew assembleRelease`: success
- `./gradlew testDebugUnitTest`: success
- `./gradlew lintDebug`: success
- release artifact inspection:
  - release `BuildConfig` で `ADS_ENABLED=false` / `ADMOB_APP_ID=""` を確認し、cloud を出す場合は `SHARED_TAG_CLOUD_ENABLED=true` と production Supabase 値が入っていることを確認する
  - merged release manifest から AdMob App ID / ad permissions / ad provider 宣言を除去
- connected instrumentation:
  - targeted runtime checks の一部は emulator で再確認済み（Main swipe、metadata retry/delay、privacy dialog）
  - `Phase1aFlowTest` 全体実行は emulator 上で Compose timing / ActivityScenario teardown の flake が残るため、CI/release hard gate には含めない
- iOS:
  - Android launch readiness とは分離して compile state を別途確認する

## Pre-contract Launch Prep
- 外部契約前に完了できる作業は `docs/release/pre-contract-launch-plan.md` に集約する。
- ストア文言・privacy / Data safety の下書きは `docs/release/store-listing-draft.md` と `docs/release/privacy-data-safety-draft.md` を正本にする。
- Apple Team ID、Google Play App Signing SHA-256、production Supabase URL/key、公開削除URLは契約後に差し込む値として扱う。

## Project Structure
- `app/src/main/java/jp/mimac/urlsaver/domain`: URL ルール/モデル
- `app/src/main/java/jp/mimac/urlsaver/data`: DB/Repository
- `app/src/main/java/jp/mimac/urlsaver/worker`: metadata worker
- `app/src/main/java/jp/mimac/urlsaver/ui`: 画面/Navigation/ViewModel
- `docs/phase1a-spec.md`: Phase 1a 詳細仕様
- `docs/phase1b-draft.md`: Phase 1b 実装仕様（状態理解と再発見性改善）
- `docs/phase1b-ux-checklist.md`: Phase 1b 人手 UX 確認表

## Codex Prompt Index
- [`CODEX_INSTRUCTIONS.md`](CODEX_INSTRUCTIONS.md): Codex 実行時の基本方針（最初に参照）
- [`docs/prompts/README.md`](docs/prompts/README.md): Codex prompt docs の統合インデックス（最初に参照）
- [`docs/codex-cross-platform-review-prompt.md`](docs/codex-cross-platform-review-prompt.md): Android/iOS の差分と回帰を守る防御的レビュー用
- [`docs/codex-dark-ui-implementation-prompt.md`](docs/codex-dark-ui-implementation-prompt.md): HTML モック由来の Dark UI 実装用
- [`docs/codex-ios-port-prompt.md`](docs/codex-ios-port-prompt.md): Android Phase 1a/1b を iOS へ移植するための実装用
- [`docs/codex-shared-tag-invite-sync-prompt.md`](docs/codex-shared-tag-invite-sync-prompt.md): shared tag invite sync MVP 実装用
- [`docs/codex-swipe-list-actions-prompt.md`](docs/codex-swipe-list-actions-prompt.md): Main 一覧 swipe archive/delete 実装用

## Privacy / Local Storage Policy
- 保存データは端末内 Room DB に保持する（URL、タイトル、メモ、metadata 状態）
- 端末バックアップは無効化している（`allowBackup=false`, `fullBackupContent=false`）
- API 31+ は `dataExtractionRules` で cloud backup / device transfer の抽出を除外している
- 復元機能を提供している前提ではない
- Crash reporting は本リリースでは未導入（OS ログ + 手動再現手順で運用）
- アプリ内の Main 画面から「プライバシー情報」を確認できる
- shared-tag cloud を公開面に出す場合は、`docs/account-deletion.md` と `docs/account-deletion-request.html` を release checklist に含める
- Google Play 向け web deletion route は `.github/workflows/account-deletion-page.yml` で GitHub Pages 配信できる
  - Pages を有効化したら `https://<owner>.github.io/<repo>/account-deletion/` を Play Console の削除 URL に設定する

## Share Intake Degradation Policy
- `ACTION_SEND_MULTIPLE` は対応済み（有効 URL を重複除外して順次保存）
- `ACTION_SEND_MULTIPLE` は「総件数・新規・既存・復元・失敗」の集計を通知する
- `ACTION_SEND` で1つの共有テキストに複数 URL が含まれる場合は、先頭 1 件のみ保存し、UI で明示通知する
- 共有 Intent が mime 未指定でも、`ACTION_SEND` / `ACTION_SEND_MULTIPLE` の最小フォールバック受信を行う
- URL が見つからない / 無効な場合は明示エラーを表示し、無言ドロップしない
