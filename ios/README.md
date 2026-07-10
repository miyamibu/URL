# URLSaver iOS

Goal
- Android `URL共有アプリ_UrlSaver` の Phase 1a/1b 契約を、iOS ネイティブ実装でできるだけ同じ意味に揃える。

Context
- iOS 実装は `ios/` 配下に独立配置。
- 主契約は `normalizedUrl` unique、`openUrl = normalizedUrl`、Main / Archive / Detail、Share Extension、DB-backed pending delete + Undo。

Constraints
- UI は SwiftUI / iPhone-first。
- 永続化は SQLite で `normalized_url UNIQUE` を直接維持。
- Share Extension は薄く保ち、保存ルールは shared repository に集約。
- metadata は app-active 時の即時処理 + `BGTaskScheduler` による best-effort 再実行。

Done when
- `xcodebuild` で app / tests が通る。
- 手動保存、Main / Archive / Detail、swipe archive/delete、Undo、Share Extension、metadata state 表示が現実の build / test / release compile で成立している。
- release 相当 compile、plist / entitlements / App Group / privacy manifest の整合が取れている。

## Structure

- `URLSaverShared/`
  - `Domain/`: URL ルール、状態モデル、表示文言
  - `Data/`: SQLite repository、metadata fetcher、share handoff
  - `Support/`: shared container paths
- `URLSaveriOS/`
  - `App/`: app services、background scheduler、app model
  - `UI/`: SwiftUI screens
- `URLSaverShareExtension/`
  - Share Extension entry point
- `URLSaveriOSTests/`
  - URL rules / repository / metadata text tests

## Build

- Project regenerate: `ruby ios/generate_xcodeproj.rb`
- Discover available simulator destinations:
  - `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -showdestinations`
- Verified simulator on 2026-04-23:
  - `iPhone 17 Pro Max`
  - `platform=iOS Simulator,id=2021719A-3EE0-4D14-8EFF-39758C799300`
- Simulator build:
  - `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -destination 'platform=iOS Simulator,id=2021719A-3EE0-4D14-8EFF-39758C799300' build`
- Test:
  - `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -destination 'platform=iOS Simulator,id=2021719A-3EE0-4D14-8EFF-39758C799300' test`
- Release-style compile:
  - `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`

## Shared Tag Cloud (minimal additive UI)

- iOS は既存の Main / Archive / Detail を保ったまま、Main ヘッダーの `person.2` ボタンから shared-tag cloud シートを開けます。
- シートでは次を扱います。
  - メール/パスワードでの sign in / sign up
  - `urlsaver://invite/{token}` で受け取った pending invite の参加/取り消し
  - 共有タグの作成
  - 共有タグ一覧の確認
  - 共有タグ詳細、名前変更、招待リンク共有、URL追加/除外、削除
  - 即時 sync
  - in-app account deletion
- cloud 設定は次の順で読みます。
  - 環境変数 `URLSAVER_SHARED_TAG_CLOUD_ENABLED`
  - 環境変数 `URLSAVER_SUPABASE_URL`
  - 環境変数 `URLSAVER_SUPABASE_ANON_KEY`
  - 環境変数 `URLSAVER_INVITE_LINK_BASE_URL`
  - `ios/URLSaveriOS/Info.plist` の `SharedTagCloudEnabled` / `SupabaseURL` / `SupabaseAnonKey` / `InviteLinkBaseURL`
- 未設定時は cloud シート内で未設定メッセージを出し、既存の local saver 契約はそのまま保ちます。
- `URLSaveriOS.xcodeproj` には dev LAN URL や dev key を固定しません。project の標準 base configuration は secret を含まない `ios/Config/URLSaverSecrets.local-only.xcconfig` です。cloud-sharing の Archive/TestFlight では `ios/Config/URLSaverSecrets.xcconfig.example` を `ios/Config/URLSaverSecrets.xcconfig` にコピーし、実値を入れて `xcodebuild ... -xcconfig ios/Config/URLSaverSecrets.xcconfig archive` のように明示的に渡してください。実値入り xcconfig は gitignore 済みです。
- local-only v1.0 の App Store / Release 検証では、dev Supabase 向け `ios/Config/URLSaverSecrets.xcconfig` を `-xcconfig` で渡さないでください。標準の `ios/Config/URLSaverSecrets.local-only.xcconfig` で `URLSAVER_SHARED_TAG_CLOUD_ENABLED=false` と空の Supabase URL/key を確認してください。
- Supabase key は publishable key または legacy anon key のみを使い、`service_role` / `sb_secret` は app binary に入れません。
- βの招待リンクの正本は `https://miyamibu.xyz/invite/{token}` です。`urlsaver://invite/{token}` は fallback deep link として残します。

## AI-friendly Export

- iOS の Export は ZIP / JSON を出力します。
- ZIP には `manifest.json`, `entries.jsonl`, `entries/*.md`, `schema.json`, `README_FOR_AI.md`, `redaction_report.json` を含めます。
- AI-safe 出力では raw `fetchedBody` を出さず、`bodyExcerpt` / `memoExcerpt`、`publicSafeId`、`aiEligible`、`redactionApplied` を含めます。
- 共有タグ付きentryはデフォルトで `aiEligible=false` とし、参加者情報は含めません。
- production MCP/OpenAI提出、UI統合済みAI receipt/draft/diff、メディア保存状態の明示出力は未完了ゲートとして扱います。

## Known iOS-specific notes

- Share Extension は Android の `ACTION_SEND` / `ACTION_SEND_MULTIPLE` を直接持たないため、iOS 入力項目数と provider 数から single-share / multi-share を近似する。
- iOS では Share Extension から host app を自動で前面表示する保証がないため、保存結果は shared handoff report にも書き出し、次回 app 復帰時に同じ意味の通知を再表示する。
- metadata の background 実行は `BGTaskScheduler` の best-effort で、Android WorkManager の時刻精度そのものは再現しない。
- shared-tag cloud は additive sheet に閉じ込め、Main / Archive / Detail の見た目と操作体系は変えていない。
- Detail 画面にも shared tag 編集シートを加算しており、既存カードや metadata セクションの構造は崩していない。
- App Group は `group.jp.mimac.urlsaver` を app / share extension で共有し、利用不能な環境では app 側が Application Support へ fallback する。

## Validation method

- Unit tests で URL normalization / dedupe / duplicate / pending delete / metadata-only `updatedAt` 不変を固定。
- `xcodebuild` の build/test 実行結果を root の作業ログに記録する。
- App Store review / privacy の source of truth は `ios/APP_STORE_REVIEW.md` にまとめる。
