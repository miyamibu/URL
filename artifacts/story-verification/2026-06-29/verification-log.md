# りんばむ 正準ストーリー検証ログ

- 作成日時: 2026-06-29 00:39:51 +0900
- 正準スプレッドシート: `docs/qa/rinbam_canonical_story_status.xlsx`
- CSVミラー: `docs/qa/rinbam_canonical_story_status.csv`
- 今回の焦点: 2026-06-29時点の追加ストーリーと品質管理行の更新

## 台帳更新

- 既存の単一正準台帳を継続利用。
- ストーリー数: 50
- 追加済み:
  - `US-031` ローカルタグ共有リンク / JSON export/import
  - `US-032` 未ログイン招待の保留 / サインイン後再開
  - `US-033` Standard / Pro ストア購入検証
  - `AS-007` ストア購入検証履歴 / entitlement grant監査
  - `US-034` 共有保存時のタグ選択 / 作成
  - `US-035` 受信箱 / 自作コレクション作成・移動・並び替え
  - `US-036` ChatGPT personal link sync
  - `AS-008` 問い合わせ送信 / Resend配信追跡
  - `ES-006` Privacy・Account deletion・App Links / Universal Links公開運用

## 実行結果

- Android debug/local: PASS `./gradlew assembleDebug testDebugUnitTest lintDebug`
- Android targeted: PASS `./gradlew testDebugUnitTest --tests 'jp.mimac.urlsaver.SharedTagAuthViewModelTest' --tests 'jp.mimac.urlsaver.TagRepositoryTest'`
- Android share/collection targeted: PASS `./gradlew testDebugUnitTest --tests 'jp.mimac.urlsaver.ShareReceiverActivityEntrypointTest' --tests 'jp.mimac.urlsaver.RepositoryBehaviorTest' --tests 'jp.mimac.urlsaver.MainListViewModelTest' --tests 'jp.mimac.urlsaver.ListFilterStateTest' --tests 'jp.mimac.urlsaver.TopFilterOrderResolutionTest'`
- Android contact targeted: PASS `./gradlew testDebugUnitTest --tests 'jp.mimac.urlsaver.ContactSupportClientTest'`
- Android release: PASS `./gradlew assembleRelease`
- iOS targeted: PASS `URLRepositoryTests`, `PendingInviteStoreTests`
- iOS build: PASS generic iOS Simulator build
- iOS full XCTest: PARTIAL/FAIL. `SharedTagCloudLiveSyncTests` 3件が `Invalid API key` で失敗。ローカル対象テストは成功。
- Web admin: PASS `npm run typecheck && npm run build`
- Supabase Edge Function: PASS `deno check supabase/functions/verify-store-purchase/index.ts`
- Supabase contact Functions: PASS `deno check supabase/functions/contact-support/index.ts` and `deno check supabase/functions/contact-support-resend-webhook/index.ts`
- Supabase local lint: BLOCKED_LOCAL `supabase db lint --local` failed because local Postgres `127.0.0.1:55422` refused connection.

## 未検証ゲート

- Android physical: タグJSON共有、招待保留再開、Google Play Billing sandbox購入は未検証。
- iPhone physical: Share Extensionタグimport、保留招待再開、StoreKit sandbox購入は未検証。
- Supabase live/local DB: migration適用、RLS、RPC、store purchase verificationのDB write/read契約は未検証。
- External store: Google Play / App Store Connect側のproduct、価格、sandbox、通知連携は未検証。

## エラー記録

- iOS full XCTest: live Supabase shared-tag tests failed with `Invalid API key`.
- Supabase local lint: local Postgres connection refused on `127.0.0.1:55422`.
- Android release build warnings:
  - release manifest removal markers reported no matching declarations for ad/adservices entries.
  - `Icons.Outlined.MenuBook` deprecated warning.

## 次のループ

- 物理Androidで非破壊のタグ共有/招待再開フローを確認する。
- 物理iPhoneはAppium/WebDriverAgent復旧後にShare Extension/招待再開を確認する。
- Supabase local DBを起動するか、承認済みlinked環境でmigration/RLS検証を実施する。
- ストアsandbox購入は外部ストア設定完了後に実施する。
