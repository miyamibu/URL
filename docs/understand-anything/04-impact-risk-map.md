# Impact Risk Map

| 対象 | 種別 | 呼び出し元 | 呼び出し先 | 関連UI | 関連API | 関連DB | 関連Worker | 関連テスト | 壊れ方の例 | リスク | 確認方法 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| URL正規化 | domain rule | 共有受信/手動保存/同期 | `UrlRules.kt`, `URLRules.swift` | 全保存UI | なし | `normalizedUrl`, `normalized_url` | なし | `UrlRulesTest.kt`, `URLRulesTests.swift`, `UrlNormalizationVectorTest.kt` | 同じURLが別カードになる、既存カードが見つからない | P0 | 正規化ベクトルと既存DB重複テストを実行 |
| 重複判定 | repository | `saveFromUrl` | DAO/SQLite query | 保存結果表示 | なし | unique index | なし | `RepositoryBehaviorTest.kt`, `URLRepositoryTests.swift` | duplicateが作られる、保存できない | P0 | 同一URLを複数形式で保存 |
| `normalizedUrl` | domain key | URL保存/共有タグsync | local DB, Supabase unique | Main/Detail/Tag | Supabase `shared_tag_urls.normalized_url` | Room/SQLite/Supabase | Sync worker | migration/normalization tests | 共有タグ内でカード欠落、同期重複 | P0 | Android/iOS/Supabaseの同一入力結果を比較 |
| `openUrl = normalizedUrl` | domain invariant | Detail open/metadata fetch | external browser/fetcher | Detail/カード | Web/oEmbed | `openUrl`, `open_url` | Metadata worker | `UrlRulesTest.kt`, metadata tests | 開くURLと取得URLがズレる | P0 | 保存後DBのopenUrlを確認しDetailから開く |
| `updatedAt` 更新ルール | domain invariant | metadata更新 | DAO/SQLite update | 一覧ソート/Detail | なし | `updatedAt`, `updated_at` | Metadata worker/BGTask | `FetchMetadataWorkerTest.kt`, `URLRepositoryTests.swift` | metadata更新だけで一覧順が変わる | P1 | metadata再取得前後のupdatedAt不変を確認 |
| Shared Tag Sync outbox | sync queue | tag add/remove/create | `SharedTagSyncDao`, iOS sync state | Tag detail | `apply_shared_tag_ops` | `shared_tag_sync_outbox` | `SharedTagSyncWorker`, `SharedTagSyncExecutor` | `SharedTagSyncRepositoryTest.kt` | 操作が消える/二重送信 | P0 | オフライン操作後に再同期 |
| Shared Tag Sync apply | remote write | worker/executor | Supabase RPC | sync status | `apply_shared_tag_ops` | remote `shared_tag_*` | Sync worker | SQL validation test | 権限誤り、terminal failure放置 | P0 | RPC結果status missing/unknown時の扱い確認 |
| Shared Tag Sync pull | remote read | worker/executor | snapshot reconcile | shared tag list/detail | `pull_shared_tag_snapshot` | local synced projection | Sync worker | live sync tests | 他端末追加が見えない | P0 | 2端末で追加後pullしタグ詳細確認 |
| Shared Tag Sync conflict | consistency | concurrent clients | apply/pull | Tag detail | Supabase unique(tag_id, normalized_url) | local refs | Sync worker | repository/live sync tests | 同時追加で片方欠落/重複 | P0 | 同一URLを両端末から同時追加 |
| Metadata fetch | external integration | worker/coordinator | fetcher | Card/Detail | Web/oEmbed/HTML | metadata列 | WorkManager/BGTask | metadata tests | サムネ/アイコン/本文が出ない | P1 | TikTok/X/YouTube/Instagramのfixtureと実URL確認 |
| Metadata retry | user action | Detail button | repository mark PENDING | Detail | Web | metadata state | Worker/BGTask | worker/status tests | 再取得できませんが固定化 | P1 | FAILED/UNAVAILABLE/READY不足状態から再取得 |
| Metadata unavailable | user messaging | fetcher outcome | UI text | Main/Detail | Web | `metadataError` | Worker/BGTask | `MetadataUiTextTest.kt` | 内部エラー露出、誤解を招く文言 | P2 | 各error codeの表示確認 |
| Android intent入口 | platform intake | OS share sheet | `ShareReceiverActivity` | result snackbar | Intent | DB保存 | metadata enqueue | `ShareReceiverActivityTest.kt` | 共有しても保存されない | P0 | ACTION_SEND/SEND_MULTIPLE/VIEWをテスト |
| iOS share extension | platform intake | iOS share sheet | `ShareViewController` | host app handoff | Extension context | shared SQLite/handoff | app backlog | iOS tests | Extensionだけ保存失敗 | P0 | 実機共有とhandoff fallback確認 |
| 認証 / 認可 | security | cloud screens/sync | Supabase Auth/RLS | Shared Tag Cloud | `/auth/v1/*`, RLS | session/sync state | sync executor | live sync tests | 他人タグ表示、同期不能 | P0 | authUserId切替、logout、RLS確認 |
| config / env | configuration | AppContainer/Info.plist | Supabase config | cloud enabled表示 | Supabase URL/key | なし | sync no-op | config checks | 設定なしで同期ボタンだけ出る | P1 | enabled/url/keyの組み合わせ確認 |
| Room schema | persistence | repository/worker | DAO | Android全UI | なし | `AppDatabase` v11 | WorkManager | migration tests | 起動クラッシュ、データ欠落 | P0 | Room migration tests/schema diff |
| SQLite schema | persistence | app/share extension | SQLiteDatabase | iOS全UI | なし | SQLite tables | BGTask | SQLite/URLRepository tests | 起動遅延、lock、保存失敗 | P0 | WAL/extension同時アクセス確認 |
| Worker / background job | background | save/retry/sync | WorkManager/BGTask | status UI | Web/Supabase | DB | workers | worker tests | 起動時遅い、同期されない | P1 | worker queueと実機起動直後を確認 |
| network client | external | fetcher/sync data source | URLSession/HttpURLConnection | metadata/cloud | Web/Supabase | metadata/sync | workers | metadata/live sync tests | timeout/redirect/HTML制限で失敗 | P1 | timeout、redirect、4xx/5xx fixture確認 |
| tests | quality gate | CI/manual | unit/integration | なし | なし | test DB | test worker | all tests | 回帰に気づけない | P3 | 変更ごとに対象テスト一覧を更新 |
