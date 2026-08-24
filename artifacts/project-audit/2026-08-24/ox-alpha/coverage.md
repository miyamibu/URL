# Coverage — りんばむ 監査 2026-08-24 (ox-alpha)

対象: branch `codex/修正` @ `eda2f2678c16bfbbda56d398c613012376993e76`

| 領域 | 確認状態 | 証拠 | 未確認事項 | リスク |
| --- | --- | --- | --- | --- |
| Product | FULLY REVIEWED | AGENTS.md/DESIGN.md/README/launch-checklist照合 | ユーザー調査データなし | 低 |
| Requirements | FULLY REVIEWED | docs/phase1a-spec.md 契約とAGENTS不変条件をコード照合 | 受け入れ条件の正式版一覧なし | 低 |
| UI (Android) | SAMPLED | ShareReceiver精読+修正, 契約スクリプトPASS | 実機/Simulator操作証跡は本セッション禁止範囲 | 中 |
| UI (iOS) | NOT EXECUTED | xcodebuild未実行(ios無変更) | Simulator UI操作 | 中 |
| UX | PARTIALLY REVIEWED | コード上の状態表現(エラー/Undo/degradation通知)確認 | 実ユーザー導線の動的確認 | 中 |
| Accessibility | NOT PROVIDED | Compose/SwiftUI標準に委譲の静的所見のみ | TalkBack/VoiceOver実検査 | 中 |
| Content | PARTIALLY REVIEWED | ShareReceiver文言・エラーメッセージ精読 | 全画面文言網羅 | 低 |
| Frontend (web) | SAMPLED | admin auth/CSP静的確認 | runtime/ブラウザ検査 | 中 |
| Mobile (Android build/test/lint) | FULLY REVIEWED | E4: testDebugUnitTest(358/0)+assembleDebug+lintDebug PASS | connectedDebugAndroidTest(禁止) | 低 |
| Backend (functions) | PARTIALLY REVIEWED | store-notification-receiver署名検証経路, contact-support構造 | 残function全文/runtime | 中 |
| API (Supabase RPC/RLS) | PARTIALLY REVIEWED | RLS enable+policies一覧, apply_shared_tag_ops硬化migration存在確認 | ローカルstackでの実行検証(BLOCKED) | 中 |
| Database (Room/SQLite) | PARTIALLY REVIEWED | Room v22 migration連鎖網羅(1→22), iOS UNIQUE契約 | MIGRATION_18_20等本文全行, 実機DB user_version突合 | 低〜中 |
| Authentication | PARTIALLY REVIEWED | admin: サーバー側JWT検証+AAL2 step-up(E3), アプリ: Keystore暗号化session(E3) | prod Auth設定/実ログイン | 中 |
| Authorization | PARTIALLY REVIEWED | shared_tags系RLS active member限定(E3), admin capabilities(E3) | RLS実SQL実行検証 | 中 |
| Security | PARTIALLY REVIEWED | SSRF policy(E3), webhook署名検証(E3), CSP(E3) | ペネトレ相当の動的検査(範囲外) | 中 |
| Privacy | PARTIALLY REVIEWED | allowBackup=false, dataExtractionRules, session暗号化, AI同期除外契約 | Data Safety申請内容との突合 | 中 |
| Performance | NOT EXECUTED | 計測なし | p50/p95等計測 | 低(規模感より) |
| Reliability | PARTIALLY REVIEWED | metadata retry契約, SAVE_FAILED経路, outbox migrations存在 | 障害注入試験 | 中 |
| Testing | FULLY REVIEWED (repo-local) | 358 tests 0 failures E4 / 契約スクリプト E4 | instrumentation/E2E実機 | 低 |
| CI/CD | SAMPLED | README記載(GH Actions: assemble/test/lint) | 実CIログ今回取得なし | 低 |
| Infrastructure | NOT EXECUTED | prod Supabase/Vercel/Play Store設定は範囲外 | 本番env/シークレット運用 | 高(未検証) |
| Observability | SAMPLED | functions内console.error, outbox/dead-letter migrations存在 | メトリック/alert実装 | 中 |
| Operations/Admin | PARTIALLY REVIEWED | admin web機能群・runbook docs存在確認 | 運用リハーサル | 中 |
| Support | SAMPLED | contact-support function/outbox/dead-letter構造 | 問い合わせフロー実動作 | 中 |
| Documentation | FULLY REVIEWED | README/.java-version/DESIGN/AGENTS/契約書の整合確認 | - | 低 |
| Dependencies | SAMPLED | robolectric 4.16, jose 5.9.6, apple library 3.1.0を利用箇所で確認 | SBOM/脆弱性スキャン | 中 |
| Licensing | NOT PROVIDED | ライセンスファイル目視せず | - | 低 |
| Internationalization | PARTIALLY REVIEWED | 日本語UI中心, ハードコード日本語文字列の方針一貫 | 英語展開時の翻訳基盤 | 低 |
| SEO | SAMPLED | invite-link静的ページ+CSP/vercel.json | OGP実配信確認 | 低 |
| Analytics | NOT APPLICABLE | 分析SDK導入なしをManifest/grepで確認(広告はAdsManagerのみ) | - | 低 |
| Cost | NOT EXECUTED | 従量課金(API/Functions呼出)の上限設計は未確認 | rate limit実効性 | 中 |

## 主要な「最も壊れてはいけないもの」の確認結果

| 不変条件 | 状態 | 証拠 |
| --- | --- | --- |
| 重複主キー = normalizedUrl | 維持確認済み | UrlRules.kt:210-252 / AppDatabase entities / iOS `normalized_url TEXT NOT NULL UNIQUE` (URLRepository.swift:1029) |
| openUrl = normalizedUrl | 維持確認済み | UrlRules.kt:202 (parseUrl), ExternalActions.kt:14-16 |
| metadata更新でupdatedAtを触らない | 維持確認済み | FetchMetadataWorker → dao.updateMetadata 専用経路 |
| ShareReceiver→MainActivityはextrasのみ | 維持確認済み | ShareReceiverEntrypointRouter.kt:50-55 |
| WorkManager unique KEEP/CONNECTED/backoff | 構造確認済み | MetadataWorkScheduler(契約どおりのkey命名) ※詳細値の再突合は部分 |
| 実機データ保護(破壊的migration無し) | 維持確認済み | fallbackToDestructive不使用(AppDatabase grep:0件) + forward migration連鎖 |
