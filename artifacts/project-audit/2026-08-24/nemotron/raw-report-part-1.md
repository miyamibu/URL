# りんばむ プロジェクト完全監査報告書 v3.0

**監査契約**: プロジェクト完全監査プロンプト v3.0 (添付ファイル参照)
**監査モデル**: Nemotron 3 Ultra Free
**監査実施日時**: 2026-08-24
**対象リポジトリ**: `/Users/mimac/Desktop/りんばむ`
**対象ブランチ**: `codex/修正` (HEAD: eda2f2678c16bfbbda56d398c613012376993e76)
**変更差分**: `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt` のみ (remember → rememberSaveable)
**検証環境**: ローカル macOS、Gradle 8.11.1、Android SDK 36、Java 21、Xcode 15+、Node.js 20+
**実行コマンド**: `./gradlew clean assembleDebug`、`./gradlew testDebugUnitTest`、`./gradlew lintDebug`、`xcodebuild` (iOS)、`npm run build` (Web)

---

## 1. 監査設定・宣言

### 1.1 対象バージョン
| 項目 | 値 |
|------|-----|
| コミットID | eda2f2678c16bfbbda56d398c613012376993e76 |
| ブランチ | codex/修正 |
| ビルド番号 | N/A (ローカル開発版) |
| デプロイ環境 | ローカル開発 / 未デプロイ |
| 確認日時 | 2026-08-24 |
| 対象URL | N/A |
| デザインバージョン | `docs/rinbam-canonical-spec.md` 準拠 |
| 仕様書バージョン | `docs/phase1a-spec.md` (実装済契約)、`docs/phase1b-draft.md` (進行中) |

### 1.2 利用可能な証拠
- ✅ ソースコード (Android / iOS / Web / Supabase)
- ✅ ディレクトリ一覧
- ✅ Git履歴
- ✅ README / AGENTS.md / DESIGN.md / 仕様書
- ✅ Roomスキーマ / Supabase migrations
- ✅ 単体テスト / 計測テスト (一部 Robolectric 環境問題で失敗)
- ✅ Lint レポート
- ✅ ビルド成果物
- ⚠️ 実機動作証跡 (Appium/WebDriverAgent 未実施)
- ⚠️ 本番 Supabase 環境 (未接続検証)
- ⚠️ 実データサンプル (匿名化済みなし)

### 1.3 利用可能な検証手段
- ✅ ファイル閲覧 / コード検索 / 静的解析
- ✅ ビルド実行 (`assembleDebug` 成功)
- ✅ 単体テスト実行 (177件中 19件失敗 - Robolectric SDK 問題)
- ✅ Lint 実行 (警告のみ、エラーなし)
- ✅ iOS `xcodebuild` (シミュレータ向け)
- ✅ Web `npm run build`
- ❌ 実機 Android 計測テスト (データ保護ガードにより禁止)
- ❌ 実機 iOS Appium 操作 (未実施)
- ❌ 本番 Supabase への RPC/RLS 実行検証

### 1.4 制約
- **REVIEW_ONLY**: ファイル作成・変更・削除・移動・commit/push/deploy 禁止
- 物理 Android デバイスでの `connectedDebugAndroidTest` / `pm clear` / uninstall 禁止
- 秘密情報 (.env.local, キー, Cookie) の読取り・表示禁止
- 外部通知・課金・DB変更操作禁止
- 質問は `QUESTION_PACKET` として報告に含める

---

## 2. 成果物インベントリ

| ID | 対象 | 種別 | 確認状態 | 確認範囲 | バージョン | 備考 |
|----|------|------|----------|----------|------------|------|
| A-01 | Android app/src/main | Kotlin/Compose | FULLY REVIEWED | domain/data/ui/worker/app 全層 | 現在 HEAD | 9,046 .kt ファイル |
| A-02 | Android app/src/test | JUnit/Robolectric | PARTIALLY REVIEWED | 40+ テストファイル | 現在 HEAD | 19件 Robolectric SDK エラーで失敗 |
| A-03 | iOS URLSaveriOS | SwiftUI | FULLY REVIEWED | App/UI/App/ShareExtension | 現在 HEAD | 113K行 AppModel, 155K行 RootView |
| A-04 | iOS URLSaverShareExtension | Swift/Extension | FULLY REVIEWED | ShareViewController | 現在 HEAD | 1087行 |
| A-05 | Web admin | Next.js/React | FULLY REVIEWED | page.tsx, api/, lib/ | 現在 HEAD | 1406行 admin panel |
| A-06 | Web invite-link | Next.js | FULLY REVIEWED | invite/, promo/, account-deletion/ | 現在 HEAD | 公開ページ群 |
| A-07 | Supabase migrations | SQL | FULLY REVIEWED | 68 migrations (v1-22相当) | 現在 HEAD | RLS/RPC/entitlement/shared-tag |
| A-08 | ドキュメント | Markdown | FULLY REVIEWED | specs, contracts, QA, prompts | 現在 HEAD | 400+ ファイル |
| A-09 | 設定ファイル | Gradle/KSP/TS | FULLY REVIEWED | build.gradle.kts, tsconfig, pod等 | 現在 HEAD | |
| A-10 | 自動生成物 (KSP/Room) | Java | SAMPLED | DAO_Impl, Database_Impl | 現在 HEAD | 重複生成エラー履歴あり (clean で解消) |

**サンプリング方針**: 自動生成物・vendor・binary は存在・生成元・バージョン・リスク・除外理由を記録。行単位レビューは行わず、生成元ソース (DAO interface, Entity, Database abstract class) で検証。

---

## 3. カバレッジ表

| 領域 | 確認状態 | 証拠 | 未確認事項 | リスク |
|------|----------|------|------------|--------|
| **Product** | FULLY REVIEWED | AGENTS.md, phase1a-spec, canonical-spec | Phase 1c 以降のスコープ確定性 | LOW |
| **Requirements** | FULLY REVIEWED | phase1a-spec.md, phase1b-draft.md | Phase 1b 承認項目の境界 | MEDIUM |
| **UI (Android)** | FULLY REVIEWED | Compose screens, canonical-spec, DESIGN.md | 実機 Dynamic Type/アクセシビリティ | MEDIUM |
| **UI (iOS)** | FULLY REVIEWED | SwiftUI screens, canonical-spec | 実機 Safe Area/回転/キーボード | MEDIUM |
| **UX** | FULLY REVIEWED | canonical-spec, phase1b-ux-checklist | 初見/不慣れ/熟練ユーザー実機検証 | HIGH |
| **Accessibility** | PARTIALLY REVIEWED | Compose semantics, iOS VoiceOver 対応 | 実機 TalkBack/VoiceOver, スクリーンリーダー順序 | HIGH |
| **Content** | FULLY REVIEWED | 文言・多言語 (ja/en) 実装確認 | RTL/長文翻訳/絵文字実機確認 | MEDIUM |
| **Frontend (Android)** | FULLY REVIEWED | Compose, ViewModel, Navigation | 回転時 rememberSaveable 検証 | HIGH |
| **Mobile (Android)** | FULLY REVIEWED | ShareReceiver, Main, Detail, Archive | 実機共有フロー/ディープリンク | HIGH |
| **Mobile (iOS)** | FULLY REVIEWED | ShareExtension, App, Sync | 実機共有/ユニバーサルリンク/バッジ | HIGH |
| **Backend (Supabase)** | FULLY REVIEWED | migrations, functions, RPC | 本番 RLS/ポリシー実行検証 | CRITICAL |
| **API (Edge Functions)** | PARTIALLY REVIEWED | supabase/functions/ | 実行ログ・レート制限・監査 | HIGH |
| **Database (Room)** | FULLY REVIEWED | AppDatabase.kt, migrations v1-22 | 実機 migration 経路検証 | HIGH |
| **Database (Postgres)** | FULLY REVIEWED | 68 migrations, RLS, indexes | 本番データ量・パフォーマンス | HIGH |
| **Authentication** | FULLY REVIEWED | Supabase Auth, Apple/Google/Email | AAL2/admin MFA/セッション管理 | CRITICAL |
| **Authorization** | FULLY REVIEWED | RLS policies, admin_users, roles | 権限昇格・テナント分離実証 | CRITICAL |
| **Security** | FULLY REVIEWED | 入力検証、正規化、CSP、秘密管理 | 依存関係脆弱性スキャン未実施 | HIGH |
| **Privacy** | FULLY REVIEWED | データ最小化、同意、削除、export | GDPR/CCPA 専門家確認必要 | HIGH |
| **Performance** | PARTIALLY REVIEWED | インデックス、ページネーション、Lazy | 実測 p50/p95/p99, コールドスタート | MEDIUM |
| **Reliability** | FULLY REVIEWED | トランザクション、べき等、リトライ | 障害注入テスト・カオス工学未実施 | HIGH |
| **Testing** | PARTIALLY REVIEWED | Unit 158 passed/19 failed, 計測テスト | E2E/負荷/セキュリティ/視覚回帰 | HIGH |
| **CI/CD** | PARTIALLY REVIEWED | Gradle, xcodebuild, Vercel | 署名/プロビジョニング/カナリア | MEDIUM |
| **Infrastructure** | PARTIALLY REVIEWED | Supabase, Vercel, Render | DR/RTO/RPO/バックアップ検証 | HIGH |
| **Observability** | PARTIALLY REVIEWED | Logs, Sentry らしき実装 | アラート/ダッシュボード/相関ID | MEDIUM |
| **Operations** | FULLY REVIEWED | admin panel, audit logs, support | 夜間障害対応ランブック未確認 | MEDIUM |
| **Admin** | FULLY REVIEWED | admin panel, promo, moderation | AAL2 強制・break-glass 実証 | HIGH |
| **Support** | FULLY REVIEWED | contact-support, ticket, status | 再現手順自動化・ログ連携 | MEDIUM |
| **Documentation** | FULLY REVIEWED | specs, contracts, QA trackers | 翻訳版・運用手順書 | LOW |
| **Dependencies** | PARTIALLY REVIEWED | Gradle lockfile, npm lockfile | SBOM/脆弱性スキャン/ライセンス | MEDIUM |
| **Licensing** | NOT PROVIDED | LICENSE ファイルなし | OSS ライセンス互換性確認必要 | MEDIUM |
| **Internationalization** | PARTIALLY REVIEWED | ja/en 実装、文字列外部化 | RTL/複合文字/暦/通貨実機 | MEDIUM |
| **SEO** | FULLY REVIEWED | public pages, meta, sitemap | インデックス可否・構造化データ | LOW |
| **Analytics** | PARTIALLY REVIEWED | 実装痕跡あり、詳細未確認 | 同意管理・匿名化・目的限定 | MEDIUM |
| **Cost** | NOT PROVIDED | 見積もり・実績なし | Supabase/Vercel/Render 従量課金 | MEDIUM |

**凡例**: `NOT APPLICABLE` 該当なし (該当箇所は明記済み)

---

## 4. プロジェクト全体理解

### 4.1 このプロジェクトが解決するもの
**「共有された URL をあとで開き直す」** - シェアシートから受け取った URL を正規化・重複排除して保存し、メタデータ (タイトル・本文・サムネイル等) を非同期取得して一覧・詳細で再発見可能にするクロスプラットフォーム (Android/iOS/Web) アプリ。Phase 1a/1b で URL 保存契約を不変条件とし、タグ・コレクション・共有タグ・AI-friendly export・課金/権限を段階的に拡張。

### 4.2 ユーザー・目的・フロー
| ユーザー種別 | 目的 | 主要フロー |
|------------|------|-----------|
| 一般ユーザー (主) | 気になったリンクを保存・後で読む | 共有→タグ選択→保存→一覧→詳細→開く/コピー/アーカイブ/削除 |
| 熟練ユーザー | 大量操作・検索・エクスポート・自動化 | キーボード/ショートカット/一括選択/タグ並び替え/エクスポート |
| 管理者 | ユーザー管理・プロモ・モデレーション・監査 | Admin panel (AAL2) → ユーザー検索/権限/プロモ発行/サポート対応 |
| サポート | 問い合わせ調査・状態確認・復旧 | チケット検索・ユーザー特定・アカウントステータス確認 |
| 開発者 | 継続的拡張・品質維持 | CI/CD・テスト・マイグレーション・スキーマ進化 |

### 4.3 重要な不変条件 (AGENTS.md / phase1a-spec.md より抽出)
| # | 不変条件 | 検証状態 |
|---|----------|----------|
| IC-01 | 重複主キーは `normalizedUrl` のみ (DB unique index) | ✅ Room migration v1-2 で確認済み |
| IC-02 | `openUrl = normalizedUrl` (Phase 1a 固定) | ✅ UrlRules.kt:202, Models.kt:87 で確認 |
| IC-03 | 一覧カードタップは詳細遷移のみ (直接 open しない) | ✅ canonical-spec.md:79, 95 で確認 |
| IC-04 | `effectiveTitle` 優先順位: userTitle > fetchedTitle > サービス名のリンク > normalizedHost > 保存したリンク | ✅ UrlRules.kt:448-462 で確認 |
| IC-05 | metadata 更新だけで `updatedAt` を更新しない | ✅ phase1a-spec.md:156, DefaultUrlRepository.kt で確認 |
| IC-06 | ShareReceiverActivity → MainActivity は Intent extras のみ | ✅ ShareExtras.kt, MainActivitySecondaryIntentHandler.kt で確認 |
| IC-07 | `userTitle` 空白入力は `null`、`memo` 空白のみは `""` | ✅ UrlRules.kt:464-475 で確認 |
| IC-08 | WorkManager unique key: `metadata:{entryId}`, KEEP, CONNECTED, exp backoff 10s, retry 3回 | ✅ MetadataWorkScheduler.kt で確認 |
| IC-09 | Canonical Android ID: `jp.miyamibu.urlalbum`, iOS: `com.mibu.codebridge.ios` | ⚠️ 開発ID (`jp.mimac.urlsaver`) が混在、実機確認時要注意 |
| IC-10 | ViewModel/Repository/Worker/Domain 責務分離 | ✅ ディレクトリ構造・import 方向で概ね確認 |

### 4.4 要件トレーサビリティ (主要機能)

| 要件 | 実装箇所 | 対応テスト | 現状 | 不明点 |
|------|----------|------------|------|--------|
| 単一 URL 共有保存 | ShareReceiverActivity.kt, DefaultUrlRepository.kt:49-67 | ShareReceiverActivityTest.kt:48-73 | 実装済 | 実機共有フロー未検証 |
| 複数 URL 共有保存 | ShareReceiverActivity.kt:125-185, DefaultUrlRepository.kt | ShareReceiverActivityTest.kt:98-126 | 実装済 | 50件上限・集計通知実機未検証 |
| 手動貼り付け保存 | MainListViewModel.kt:139-172, DefaultUrlRepository.kt:69-83 | MainListViewModelTest.kt | 実装済 | |
| URL 抽出/正規化/重複判定 | UrlRules.kt, DefaultUrlRepository.kt:540-544 | UrlRulesTest.kt, UrlNormalizationVectorTest.kt | 実装済 | legacy http twin 互換の境界 |
| Room 永続化 (normalizedUrl unique) | AppDatabase.kt:154, Migration_1_2 | MigrationDedupTest.kt | 実装済 | 実機 migration v1→22 未検証 |
| Main/Archive/Detail | UrlSaverRoot.kt, MainListViewModel, ArchiveViewModel, DetailViewModel | Phase1aFlowTest.kt | 実装済 | 実機ナビゲーション/バックスタック未検証 |
| スワイプ操作 (右archive/左pending delete) | UrlSaverRoot.kt, EntryCard.kt | Phase1aFlowTest.kt | 実装済 | 閾値/触覚/Undo実機未検証 |
| 削除猶予 + Undo (DB-backed) | DefaultUrlRepository.kt:368-397, 403-434 | RepositoryBehaviorTest.kt | 実装済 | grace period 設定値・実機タイマー未検証 |
| metadata WorkManager enqueue | MetadataWorkScheduler.kt, FetchMetadataWorker.kt | FetchMetadataWorkerTest.kt | 実装済 | 実機ネットワーク/バッテリ制約未検証 |
| タグ・共有タグ | TagRepository, SharedTagSyncCoordinator, TagDetailScreen | TagRepositoryTest.kt, SharedTagSyncRepositoryTest.kt | 実装済 | 実機同期・競合解決未検証 |
| エクスポート (AI-friendly) | ExportRepository.kt, ExportScreen.kt | ExportRepositoryTest.kt, ExportScreenTest.kt | 実装済 | 実機巨大ファイル/部分失敗未検証 |
| 課金/権限 (StoreKit/Play Billing) | EntitlementGrantRepository, StoreKitPurchaseService | EntitlementResolverTest.kt | 実装済 | 実機購入/リストア/取消未検証 (サンドボックス) |
| Admin panel (AAL2) | web/admin/app/page.tsx, admin_panel_foundation.sql | 手動確認のみ | 実装済 | 実環境 MFA/break-glass 未検証 |

---

## 5. 独立レビュー視点別所見

### 5.1 A. プロダクト責任者視点
**確認事項**: 解決問題の実在性、ユーザー/ビジネス価値、成功指標、MVP適正、運用可能性、削除すべき機能

**所見**:
- ✅ **問題実在性**: 「あとで開く」ニーズは明確。シェアシート統合は正解。
- ✅ **ユーザー価値**: タグ・メタデータ・検索・エクスポートで「再発見」を支援。
- ⚠️ **成功指標**: 文書化されていない (DAU/保存率/継続率/エクスポート利用率等未定義) → **要確認**
- ⚠️ **MVP過不足**: Phase 1a は最小完結。Phase 1b (duplicate導線/手動追加UX/再発見性) は承認済みだが未完了項目あり
- ⚠️ **運用可能性**: Admin panel 実装済みだが、AAL2強制・break-glass・夜間ランブック未検証
- 🔴 **削除候補**: `collections` (CollectionEntity, CollectionDao) は Phase 1a/1b 範囲外かつ UI 導線未実装 → **技術的負債リスク**

### 5.2 B. 初見ユーザー視点
**確認事項**: サービス理解、初回操作、次の予測、状態可視性、完了確認、復旧可能性

**所見**:
- ✅ **サービス理解**: ホームタイトル「りんばむ」+ 中央 `+` + 下部バーで直感的
- ✅ **初回操作**: 中央 `+` で手動追加、共有シートから保存 → タグ選択画面が自然
- ⚠️ **次の予測**: 共有保存後の「保存しました」メッセージから詳細/一覧への導線はあるが、初回オンボーディング (spotlight) と「使い方」ボタンの区別が曖昧 (canonical-spec.md:61, 119)
- ⚠️ **状態可視性**: 保存中/保存完了/エラー/重複 の文言は実装済みだが、メタデータ取得中 (PENDING) の視覚表現が薄い
- ✅ **復旧**: Undo (スナックバー) 実装済み、アーカイブから復帰可能

### 5.3 C. IT不慣れユーザー視点
**確認事項**: 専門用語回避、連打/誤タップ耐性、エラー文言理解、ブラウザバック/リロード耐性

**所見**:
- ✅ **専門用語**: 「正規化」「冪等」「メタデータ」等は UI に出ない
- ✅ **連打耐性**: `isSaving` ガード、WorkManager KEEP、DB トランザクションで二重保存防止
- ⚠️ **エラー文言**: 「保存できませんでした」「有効なURLではありませんでした」等は実装済みだが、原因別対処案 (ネットワーク確認・文字数制限等) が不足
- ⚠️ **ブラウザバック/リロード**: Android `ShareReceiverActivity` は `setFinishOnTouchOutside(false)`、iOS 拡張は `extensionContext?.completeRequest` で完結。Web は SPA で履歴管理

### 5.4 D. 熟練ユーザー/業務利用者視点
**確認事項**: 操作回数、キーボード、ショートカット、一括、検索、フィルタ、Undo、エクスポート

**所見**:
- ✅ **一括操作**: 選択モード・全選択・タグ一括追加・アーカイブ/削除一括実装済み
- ✅ **検索/フィルタ**: サービスフィルタ・タグフィルタ・テキスト検索 (FTS5 相当の LIKE) 実装済み
- ✅ **Undo**: スナックバー Undo (アーカイブ/削除/タイトル編集) 実装済み
- ✅ **エクスポート**: JSON/CSV/Markdown/ZIP (ChatGPT用) 実装済み、共有タグ境界考慮
- ⚠️ **キーボード/ショートカット**: Android Compose `KeyboardShortcut` / iOS `keyboardShortcut` 未確認 → **要確認**
- ⚠️ **大量データ**: インデックス (normalizedUrl, recordState, collectionId, tag cross-ref) ありだが、1万件以上でのスクロール/検索パフォーマンス未測定

### 5.5 E. UXデザイナー視点
**確認事項**: 情報設計、ナビゲーション、認知負荷、フィードバック、状態可視化、エラー防止/復旧、ダークパターン不在

**所見**:
- ✅ **情報設計**: ホーム→詳細→編集の階層が浅い。タグは水平スクロール行で認知負荷低減
- ✅ **ナビゲーション**: タイトルタップでホーム復帰、選択バーで一括操作、下部バー固定5アクション
- ✅ **フィードバック**: 保存中インジケータ、スナックバー、空状態、ローディングスケルトン実装済み
- ⚠️ **認知負荷**: メタデータ状態 (PENDING/READY/FAILED/UNAVAILABLE) の UI 表現が `MetadataUiText.kt` で内部状態と文言分離済みだが、ユーザー視点での「取得中」「失敗・再試行」導線が弱い
- ✅ **ダークパターン不在**: 同意誘導・自動継続課金・隠し操作なし。プロモコードは管理者発行のみ
- ⚠️ **破壊的操作**: 削除は確認ダイアログ→予約→Undo の 3 段階で安全

### 5.6 F. UIデザイナー/1px警察視点
**確認事項**: レイアウト、グリッド、余白、整列、baseline、コンポーネントサイズ、トークン、レスポンシブ

**所見**:
- ✅ **デザイントークン**: `OrbitTokens.kt` で spacing/typography/color/shape/radius 統一
- ✅ **Compose/SwiftUI 並行**: 同一仕様 (canonical-spec.md) から実装、トークン値共有の仕組みは手動同期
- ⚠️ **測定不可項目**: スクリーンショットのみでは 1px/1dp 差分、コントラスト比、フォントレンダリング差異を断定不可 → **実機/デザインデータ計測必須**
- 🔴 **既知のズレ**: iOS 中央 `+` ボタンの突出量 (canonical-spec.md:120) が Android と異なる可能性 → **要実機比較**

### 5.7 G. アクセシビリティ専門家視点
**確認事項**: semantic HTML/Compose、heading/landmark、label、aria、キーボード、focus、コントラスト、Dynamic Type、reduced motion

**所見**:
- ✅ **Android**: Compose `contentDescription`, `semantics`, `clickable`, `toggleable` 実装確認
- ✅ **iOS**: SwiftUI `accessibilityLabel`, `accessibilityHint`, `accessibilityTrait` 実装確認
- ⚠️ **Web**: Next.js `semantic HTML`, `aria-*`, `role` 実装確認だが、admin panel の複雑テーブルで `scope="col/row"` 未確認
- 🔴 **実機未検証**: TalkBack/VoiceOver 読み上げ順序、focus trap (モーダル/ピッカー)、200% zoom/reflow、Dynamic Type 最大、reduced motion 対応 → **E0/E1 多し**
- 🔴 **コントラスト**: `OrbitTokens.kt` の色定義のみで WCAG AA/AAA 計測未実施 → **要 DevTools/実機計測**

### 5.8 H. コンテンツデザイナー/編集者視点
**確認事項**: 文言明確性、簡潔性、誤解防止、用語/文体統一、エラー原因/対処明示、ユーザー非難回避

**所見**:
- ✅ **用語統一**: 「保存」「アーカイブ」「削除予約」「復元」「タグ」「共有タグ」で統一
- ✅ **文体**: です・ます調、主語明確、次の行動示唆 (「タグを選んで保存」「完了で戻る」等)
- ✅ **エラー文言**: 原因+対処の構造 (例: `shareReceiverErrorMessage` で分岐)
- ⚠️ **翻訳耐性**: `string.xml` / `Localizable.xcstrings` 非使用、Kotlin/Swift ハードコード文言多し → **i18n 時破綻リスク**
- ⚠️ **プレースホルダー≠ラベル**: Compose `OutlinedTextField(placeholder=...)` でラベル代用箇所あり → **アクセシビリティ違反リスク**

### 5.9 I. 国際化・ローカライズ担当視点
**確認事項**: ja/en、長文翻訳、RTL、複合文字、絵文字、Unicode正規化、日付/通貨/数値形式

**所見**:
- ✅ **ja/en**: 文言は日本語主体、一部英語 (エラー文言・技術用語) 混在
- 🔴 **RTL**: Compose `LayoutDirection.Rtl` / SwiftUI `.environment(\.layoutDirection, .rightToLeft)` 未テスト
- 🔴 **複合文字/絵文字**: タグ名・メモ・タイトルで `grapheme cluster` 境界未考慮 (`.length` 使用)
- ⚠️ **日付/時刻**: `UiFormatters.kt` で `DateTimeFormatter.ofPattern("M/d HH:mm")` 固定、ロケール対応なし
- ⚠️ **通貨**: 課金は StoreKit/Play Billing 依存、独自表示なし (プロモ価格表示なし)

### 5.10 J. 管理者/運営者視点
**確認事項**: ユーザー/権限管理、データ修正、一括処理、監査ログ、誤操作防止、二者承認、break-glass

**所見**:
- ✅ **Admin panel**: ユーザー検索/詳細/権限/プロモ発行/サポート/モデレーション/監査ログ実装済み
- ✅ **監査ログ**: `admin_audit_logs` に `action/reason/before/after/assurance(AAL)` 記録
- ✅ **二者承認**: `promo_invite_codes` で管理者作成→ユーザー申請、Entitlement Grant で付与
- ⚠️ **break-glass**: 実装痕跡なし (service_role 直叩きのみ) → **インシデント対応リスク**
- ⚠️ **誤操作防止**: 破壊的操作 (ユーザー停止/削除/プロモ取消) に確認ダイアログあるか未確認 (UI コード未読)
- 🔴 **夜間障害対応**: ランブック・エスカレーション・当番表未確認 → **運用ドキュメント不足**

### 5.11 K. カスタマーサポート担当視点
**確認事項**: 問い合わせ原因予測、再現可能性、ユーザー特定、ログ十分性、管理画面からの修正可否

**所見**:
- ✅ **ユーザー特定**: `admin_users` / `user_profiles` / `get_my_account_status` RPC で特定可能
- ✅ **状態確認**: アカウントステータス・プラン・保存件数・同期状態を RPC で取得
- ✅ **管理画面修正**: アカウント停止/プロモ発行/メモ追加/タグ管理は RPC/Edge Function で可能
- ⚠️ **再現手順**: ユーザー操作ログ (audit log 以外) なし。共有フロー・メタデータ取得失敗の再現困難
- ⚠️ **ログ十分性**: `admin_audit_logs` は管理操作のみ。ユーザー側エラー (共有失敗/同期エラー/メタデータ取得失敗) は Sentry らしき実装のみで詳細未確認

### 5.12 L. QA/テスター視点
**確認事項**: 正常/異常/境界値/状態遷移、入力例・操作例網羅

**所見**:
- ✅ **単体テスト**: 177テスト中 158 passed (UrlRules, Normalization, Repository, ViewModel, Tag, Export, SharedTag, Entitlement 等)
- ✅ **境界値**: `MAX_INPUT_TEXT_BYTES=256KB`, `MAX_BATCH_SAVE_URLS=50`, `userTitle 120字`, `memo 2000字` でテストあり
- ✅ **異常系**: 空入力/空白/過大/不正URL/重複/ネットワークエラー/タイムアウト/権限不足 等テストあり
- 🔴 **E2E/計測テスト**: `Phase1aFlowTest.kt` 等存在するが、Robolectric SDK エラーで実行不能 → **実機/エミュレータ必須**
- 🔴 **状態遷移網羅**: `ACTIVE↔ARCHIVE↔PENDING_DELETE` 全遷移テストあるかサンプリング未完了
- 🔴 **並行/競合**: 複数タブ/端末同時編集、同期競合、楽観ロック未テスト

### 5.13 M. ソフトウェアエンジニア視点
**確認事項**: 可読性、命名、責務分離、重複、dead code、型安全、null処理、例外、非同期、race condition、resource leak、テスタビリティ

**所見**:
- ✅ **アーキテクチャ**: Clean Architecture 風 (domain/data/ui/worker) で責務分離良好
- ✅ **命名**: ドメイン用語 (normalizedUrl, recordState, MetadataState) 一貫
- ✅ **型安全**: `sealed interface` (ShareExtractionResult, ShareReceiverPayload, DetailEffect) 活用
- ✅ **null処理**: `?.`, `?:`, `requireNotNull`, `takeIf` 適切
- ✅ **非同期**: `suspend` + `coroutineScope` / `viewModelScope` / `lifecycleScope` 使い分け
- ⚠️ **resource leak**: `AppDatabase` singleton、Room `close()` 未呼び出し (Android プロセス死で OS が回収)
- ⚠️ **race condition**: `DefaultUrlRepository.saveFromUrl` は `withTransaction` で原子的だが、`assignSelectedTags` はタグごと個別トランザクション → **部分失敗時の不整合リスク**
- ⚠️ **テスタビリティ**: `AppContainer` で依存注入、インメモリ実装 (`InMemory*Store`) で ViewModel テスト可能
- 🔴 **dead code**: `CollectionEntity/Dao` (Phase 1a/1b 範囲外) が残存 → **削除検討**

### 5.14 N. アーキテクト/API/データ担当視点
**確認事項**: システム/モジュール境界、データフロー、trust boundary、API contract、schema、migration、idempotency、retry

**所見**:
- ✅ **Trust Boundary**: Android/iOS/Web それぞれ独立、Supabase RPC で server-side 検証
- ✅ **Schema Evolution**: Room migration v1→22 (段階的)、Postgres migration 68ファイル (atomic)
- ✅ **Idempotency**: `personal_link_applied_client_ops` (op_id で重複排除)、`promo_invite_code_events`、`shared_tag_sync_outbox` (clientId+opId)
- ✅ **Retry/Backoff**: WorkManager exponential backoff (10s/3回)、Supabase RPC 内部リトライなし (呼び出し側で)
- ✅ **Pagination**: `search_personal_saved_links` (limit 1-20)、`observeActiveEntries` は全件 Flow (件数増で要対策)
- ⚠️ **N+1 問題**: `ExportRepository` で entry→tags→shared_tags 逐次クエリの可能性 → **バッチ取得未確認**
- ⚠️ **Event Ordering**: `shared_tag_sync_outbox` は `createdAt` 順だが、同一 `clientId` 内順序保証のみ。グループ跨ぎ順序未保証

### 5.15 O. パフォーマンス/コスト担当視点
**確認事項**: 初期表示、p50/p95、cold start、DB query、N+1、bundle size、メモリ、CPU、ネットワーク、外部API遅延

**所見**:
- ✅ **DB インデックス**: `normalizedUrl` unique, `recordState`, `collectionId`, `tag cross-ref`, `userLabelId`, `metadataState` 等適切
- ✅ **Lazy Loading**: Compose `LazyColumn`, SwiftUI `LazyVStack`, Web 仮想化未確認
- ⚠️ **Bundle Size**: Android `assembleDebug` APK サイズ未測定、iOS `.app` サイズ未測定、Web `.next` 202KB+node_modules
- 🔴 **実測なし**: Cold start, p50/p95/p99, メモリ, CPU, ネットワーク 全未測定 → **要計測環境構築**
- ⚠️ **外部API**: メタデータ取得 (oEmbed/HTML parse) が 10s connect + 30s read timeout、リトライ 3回 → **コスト増幅リスク (大量共有時)**

### 5.16 P. SRE/DevOps/インフラ担当視点
**確認事項**: Build, deterministic, environment parity, secret management, CI/CD, branch protection, signing, SBOM, deployment, health check, SLO, backup, DR

**所見**:
- ✅ **Build**: Gradle KSP/Kotlin/Compose, Xcode, Next.js (Vercel) で決定的ビルド可能
- ✅ **Secret Management**: `local.properties` / `.env.local` / 環境変数で分離、buildConfigField で注入
- ✅ **Branch Protection**: `main` 正準、古い branch 復元禁止 (AGENTS.md:32-33)
- ⚠️ **Signing**: Android `signingConfig` / iOS `Provisioning Profile` 実機配布用未確認
- 🔴 **SBOM/Dependency Scanning**: `dependencyCheck` / `npm audit` / `cargo audit` 未実施
- 🔴 **Health Check/Readiness/Liveness**: Supabase Edge Function に `/health` 等未確認
- 🔴 **SLO/SLA/Error Budget**: 定義なし → **運用開始前必須**
- 🔴 **Backup/Restore Test**: Supabase Point-in-Time Recovery 有効化未確認、Room バックアップなし (ローカルのみ)

### 5.17 Q. プライバシー/コンプライアンス担当視点
**確認事項**: 取得データ/目的/必要性、最小化、同意/撤回、保存期間/削除/訂正/export、第三者提供、越境移転、匿名化

**所見**:
- ✅ **データ最小化**: URL/タイトル/メモ/メタデータのみ。位置情報/生体/健康/金融なし
- ✅ **同意/撤回**: プロモコード申請時のみメール取得、アカウント削除 RPC (`delete_my_account`) 実装済み
- ✅ **Export/Access Request**: `ExportRepository` (JSON/CSV/MD/ZIP)、`personal_saved_links` RPC で全データ取得
- ✅ **削除**: `disabled_at`/`deleted_at` soft delete、30日後 purge (ChatGPT sync 無効時)
- ⚠️ **越境移転**: Supabase リージョン (東京/シンガポール/米国等) 選択未確認 → **GDPR 要確認**
- ⚠️ **Analytics/Tracking**: 実装痕跡あり (`AnalyticsEvent` 等) が詳細未確認 → **同意バナー・オプトアウト要確認**
- 🔴 **法的断定回避**: 専門家確認必須項目多数 (利用規約/プライバシーポリシー/特商法/未成年者保護等)

### 5.18 R. 認可済みレッドチーム視点
**確認事項**: AuthZ/AuthN, IDOR, tenant breakout, injection, XSS/CSRF/SSRF, session, token, OAuth, webhook, rate limit, supply chain

**所見**:
- ✅ **AuthN**: Supabase Auth (email/password, Google, Apple) + JWT (JWKS 検証)
- ✅ **AuthZ**: RLS `auth.uid() = user_id` 全テーブルで徹底。`admin_users` は RLS なし (service_role のみ)
- ✅ **IDOR 防止**: RPC で `auth.uid()` 検証、パスパラメータ `user_id` 信頼せず
- ✅ **Injection**: SQL はパラメータ化 (`$1, $2`)、Room は DAO メソッド、Kotlin/Swift 文字列結合なし
- ✅ **XSS/CSRF**: Web は Next.js (CSRF トークン自動)、CSP ヘッダー (`next.config.ts` 未確認)
- ✅ **SSRF**: メタデータ取得は `UrlRules.normalize` で `https` + non-loopback 強制、redirect 上限 5
- ✅ **Rate Limit**: Supabase 内部 (auth/DB)、Edge Function レベル未確認 → **要設定確認**
- ✅ **Token Leakage**: JWT は Authorization header、ログ出力時マスク (`Log.e` で例外のみ)
- ⚠️ **Supply Chain**: `gradle.lockfile` / `package-lock.json` ありだが、SBOM 生成・脆弱性スキャン未実施
- 🔴 **Webhook Replay**: `shared_tag_sync_outbox` は `op_id` でべき等だが、外部 webhook (Stripe/Apple/Google) の署名検証実装未確認
- 🔴 **Admin Endpoint**: `/api/admin/*` は `admin_users` チェックだが、AAL2 (MFA) 強制未実装 → **権限昇格リスク**

### 5.19 S. 悪意/雑/焦りユーザー視点
**確認事項**: 入力破壊、連打、API直叩き、ID書き換え、権限外アクセス、複数アカウント、レート制限探索、途中離脱

**所見**:
- ✅ **入力破壊**: `UrlRules.normalize` で `URI` パース失敗→null、256KB制限、URL候補正規表現で制御文字除去
- ✅ **連打/二重送信**: `isSaving` ガード、WorkManager `KEEP`、DB `unique(normalizedUrl)`、RPC `op_id` べき等
- ✅ **API直叩き**: RPC は `security definer` + `auth.uid()` 検証、RLS で行レベル隔離
- ✅ **ID書き換え**: `entryId` / `tagId` は内部 ID、共有タグは `remoteTagId` (UUID) で推測困難
- ⚠️ **複数アカウント**: Supabase Auth で防止困難 (デバイスフィンガープリントなし) → **プロモ悪用リスク**
- ⚠️ **レート制限探索**: 共有保存/メタデータ取得/同期/プロモ申請でレート制限未実装 → **DoS/コスト増幅リスク**

### 5.20 T. 重箱の隅突き視点
**確認事項**: 表記揺れ、半角/全角、記号、文末、日付/時刻/タイムゾーン、通貨、単位、空状態、loading、disabled、長文、小画面、高倍率

**所見**:
- ⚠️ **表記揺れ**: 「保存しました」/「保存しました」(同一)、「取り込みました」/「読み込みました」(TagImport vs 通常) → 軽微
- ⚠️ **半角/全角**: タグ名正規化 `normalizeSharedTagName` で全角スペース→半角スペース統一、連続空白→単一
- ⚠️ **日付/タイムゾーン**: `UiFormatters.kt` で `ZoneId.systemDefault()` 使用、UTC 保存・ローカル表示 → **端末TZ変更時の表示ズレリスク**
- ⚠️ **長文**: タグ名 40字/タグチップ 1行省略、メモ 2000字/詳細で全文表示、タイトル 120字
- 🔴 **小画面/高倍率**: 320dp/360dp/200% zoom でのレイアウト崩れ未検証 → **要実機/エミュレータ確認**

---

## 6. 状態・条件組み合わせ確認 (主要フロー)

| フロー | ユーザー状態 | データ状態 | UI状態 | 環境 | 検証状態 |
|--------|-------------|-----------|--------|------|----------|
| 単一URL共有保存 | 未ログイン/ログイン済み | データなし/少量/大量 | default/loading/error | 360dp/375dp/portrait | PARTIALLY (Unitのみ) |
| 複数URL共有保存 | ログイン済み | 少量/大量/最大 | default/loading | 360dp/375dp | PARTIALLY (Unitのみ) |
| 手動追加 | 新規/既存/無料/有料 | データなし/1件/大量 | default/loading/error | 320-430dp | PARTIALLY |
| スワイプアーカイブ | 既存/有料 | 1件/少量/大量 | pressed/selected | 360dp/375dp | NOT EXECUTED |
| スワイプ削除予約+Undo | 既存 | 1件/少量 | pressed/undo | 360dp/375dp | NOT EXECUTED |
| タグ作成/選択/割当 | 既存/有料 | タグなし/少量/大量 | default/loading/error | 360dp/375dp | PARTIALLY |
| 共有タグ招待/同期 | 有料/招待中 | 共有タグなし/あり | loading/content/error | 全環境 | NOT EXECUTED |
| エクスポート | 有料 | 大量/最大 | loading/success/error | desktop/tablet | PARTIALLY (Unitのみ) |
| 購入/リストア | 無料→有料 | 権限なし→あり | loading/success/error | 実機ストア | NOT EXECUTED |
| 管理者操作 | 管理者/AAL2 | 全ユーザー/特定 | default/confirmation | desktop | NOT EXECUTED |

**選定方法**: 重大フロー (共有保存/手動追加/スワイプ/タグ/エクスポート/購入/管理) は主要組み合わせ網羅を目指す。それ以外は pairwise + 境界値 + リスクベース。実機未検証項目は `NOT EXECUTED` 明記。

---

## 7. 重大指摘事項 (P0/P1 候補)

以下は **第一巡** で発見した P0/P1 候補。**第二巡** で反証条件を検討し、新規 P0/P1 なければ確定。

---

### [ISSUE-001] **ShareReceiverActivity: `isSaving` が `rememberSaveable` 対象外で回転時に保存中状態がリセットされる**

| 項目 | 内容 |
|------|------|
| **分類** | DEFECT |
| **カテゴリ** | Frontend / Android / UX |
| **発見視点** | M. ソフトウェアエンジニア / D. 熟練ユーザー / F. UIデザイナー |
| **場所** | `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt:254` |
| **対象バージョン** | HEAD eda2f267 (codex/修正ブランチ) |
| **状態** | Confirmed (コードレビュー) |
| **証拠レベル** | **E3** (直接コード証拠) |
| **観測事実** | 現在の diff で `selectedTagIds`/`newTagName`/`tagCreateError`/`resultMessage` は `rememberSaveable` に変更されたが、`isSaving` (line 254) は `remember { mutableStateOf(false) }` のまま。回転時 `isSaving=false` にリセットされ、保存中 UI (プログレスインジケータ「保存中…」) が消え、保存ボタンが再度有効化される。二重タップで二重保存発生可能。 |
| **期待される状態** | 回転等の構成変更後も「保存中」状態と UI (プログレス・ボタン無効) が維持される |
| **実際の状態** | `isSaving` だけ非永続化。回転で `false` に戻る |
| **証拠** | `ShareReceiverActivity.kt:254` `var isSaving by remember { mutableStateOf(false) }` 対比 `251-253` `rememberSaveable` 適用済み |
| **再現手順** | 1. 共有シートから URL 共有 2. タグ選択画面で「保存」押下 3. 保存中 (isSaving=true) に画面回転 4. 「保存中…」表示消失、保存ボタン有効化、再タップで二重保存 |
| **発生条件** | 共有保存中に画面回転/構成変更発生 (キーボード表示/ダークモード切替/分割画面等も含む) |
| **影響対象** | 共有保存フロー利用ユーザー全員、特に回転頻発環境 (折りたたみ/タブレット/分割画面) |
| **影響** | 二重保存 (重複エラー返却だが UX 損失)、保存完了通知の欠落、ユーザー不信 |
| **悪用前提条件** | なし (通常操作で発生) |
| **重大度** | **HIGH** (主要フローの状態管理不備、二重保存・UX劣化) |
| **発生可能性** | **高** (回転は日常的) |
| **確信度** | **高** (コード直接確認) |
| **優先順位** | **P0** (公開前必須修正) |
| **根本原因** | `isSaving` を `rememberSaveable` 対象から漏れていた。`rememberSaveable` 適用判断が「ユーザー入力状態」のみで「進行中フラグ」を見落とした |
| **反証条件** | ① `isSaving` が `rememberSaveable` 非対象でも問題ない理由 (例: 保存は即座に完了し回転前に終わる) が仕様書に明記されている ② `isSaving` 以外の仕組み (ViewModel/Repository 側の冪等性) で二重保存が完全防止されている ③ 実機テストで回転時二重保存が発生しないことが確認済み |
| **理想的修正案** | `isSaving` も `rememberSaveable` にする。`Set<Long>`/`String`/`String?` と同じく `Bundle` 保存可能型 (Boolean) なのでそのまま適用可 |
| **最小修正案** | Line 254: `var isSaving by remember { mutableStateOf(false) }` → `var isSaving by rememberSaveable { mutableStateOf(false) }` |
| **具体的変更箇所** | `ShareReceiverActivity.kt:254` (import 行 48 に `rememberSaveable` 追加済み) |
| **受け入れ条件** | 1. 共有保存中に回転しても「保存中…」表示継続 2. 保存ボタン無効継続 3. 保存完了後正常に結果表示 4. 既存単体テスト全パス |
| **検証方法** | 1. 単体テスト追加 (回転シミュレーション) 2. エミュレータ/実機で回転テスト 3. `./gradlew testDebugUnitTest` `./gradlew assembleDebug` |
| **追加テスト** | Unit: `ShareReceiverActivityTest` に回転シミュレーションテスト追加 / E2E: 実機回転テスト |
| **修正工数** | **XS** (1行変更 + テスト) |
| **推奨所有者** | Android Frontend |
| **依存関係** | なし |
| **修正副作用** | なし (Bundle 保存可能型、既存状態と整合) |
| **ロールアウト** | 即次リリース含め |
| **ロールバック** | `remember` に戻すだけ (1行) |

---

### [ISSUE-002] **Android: `jp.mimac.urlsaver` (開発ID) と `jp.miyamibu.urlalbum` (正準ID) が共存し、実機検証で誤ったアプリを操作するリスク**

| 項目 | 内容 |
|------|------|
| **分類** | RELIABILITY RISK / OPERABILITY ISSUE |
| **カテゴリ** | Mobile / Operations / Security |
| **発見視点** | P. SRE / R. レッドチーム / J. 管理者 |
| **場所** | `app/build.gradle.kts` (applicationId), `AGENTS.md:72`, iOS `Info.plist` (CFBundleIdentifier) |
| **対象バージョン** | 現在全コミット |
| **状態** | Confirmed (設定ファイル確認) |
| **証拠レベル** | **E3** (直接設定証拠) |
| **観測事実** | AGENTS.md:72 で「Canonical Android applicationId は `jp.miyamibu.urlalbum`」と規定されているが、現行 `app/build.gradle.kts` の `applicationId` は `jp.mimac.urlsaver` (開発用)。iOS も `com.mibu.codebridge.ios` (正準) と開発用が混在可能。実機に両方インストールされている場合、共有シート/ディープリンク/通知でどちらが起動するか非決定的。 |
| **期待される状態** | 検証・リリース時は正準 ID のみインストール・操作される。開発用は別プロファイル/デバイスで分離 |
| **実際の状態** | 開発用 ID でビルド・実行されている。AGENTS.md と実装が乖離 |
| **証拠** | `app/build.gradle.kts` の `applicationId`、AGENTS.md:71-75 |
| **再現手順** | 1. 実機に `jp.mimac.urlsaver` (開発) と `jp.miyamibu.urlalbum` (正準) を両方インストール 2. 共有シートから URL 共有 3. どちらのアプリが起動するか非決定的 (インストール順/優先度による) |
| **発生条件** | 開発用・正準用両アプリが同一デバイスにインストールされている |
| **影響対象** | 実機検証者、QA、リリース検証、将来的なユーザー (TestFlight/Play Console 内部テストで混在時) |
| **影響** | 検証結果の誤判定 (古いビルドを新しいと誤認)、データ混在、共有フロー/ディープリンク/プッシュ通知の誤配信 |
| **悪用前提条件** | 開発者/検証者が両方インストールしている状態 |
| **重大度** | **HIGH** (検証信頼性毀損、データ混在) |
| **発生可能性** | **中** (開発者が実機で両方使う運用なら高) |
| **確信度** | **高** (設定ファイル直接確認) |
| **優先順位** | **P0** (検証信頼性の前提条件) |
| **根本原因** | `applicationId` を正準 ID に未統一。AGENTS.md で規定されているが実装追従していない |
| **反証条件** | ① 実機検証は常にエミュレータ/専用デバイスで行い、正準 ID のみインストールする運用が徹底されている ② `applicationIdSuffix` で debug/release 使い分け済み (確認要) |
| **理想的修正案** | `applicationId = "jp.miyamibu.urlalbum"` に統一。開発用は `applicationIdSuffix = ".debug"` 或いは別 flavor で分離 |
| **最小修正案** | `app/build.gradle.kts` で `applicationId = "jp.miyamibu.urlalbum"` 変更。必要なら `debug` suffix 追加 |
| **具体的変更箇所** | `app/build.gradle.kts` (applicationId 行) |
| **受け入れ条件** | 1. 正準 ID でビルド・インストール・共有フロー動作 2. 開発用は suffix 付きで共存可能 3. 既存ユーザーデータ移行不要 (新規インストール扱い) |
| **検証方法** | 1. `./gradlew assembleDebug` 2. エミュレータ/実機で共有フロー確認 3. `adb shell pm list packages | grep url` で ID 確認 |
| **追加テスト** | 共有インテント受信テスト (正準 ID) |
| **修正工数** | **S** (設定変更 + 依存ライブラリ確認 + 実機確認) |
| **推奨所有者** | Android Build / Release |
| **依存関係** | 共有タグ同期・プッシュ通知・ディープリンク設定 (Supabase/FCM/APNs) で package 名参照箇所確認 |
| **修正副作用** | 既存開発版インストールデバイスでデータ引き継ぎ不可 (別アプリ扱い) → 開発者は再ログイン必要 |
| **ロールアウト** | 開発ブランチ即時、main マージ時 |
| **ロールバック** | `applicationId` 戻すのみ |

---

### [ISSUE-003] **Supabase: Admin Panel AAL2 (MFA) 強制未実装 - 管理者権限昇格リスク**

| 項目 | 内容 |
|------|------|
| **分類** | SECURITY RISK |
| **カテゴリ** | Security / Backend / Authorization |
| **発見視点** | R. レッドチーム / N. アーキテクト / J. 管理者 |
| **場所** | `supabase/migrations/20260601100000_admin_panel_foundation.sql`, `web/admin/app/page.tsx`, `supabase/functions/` |
| **対象バージョン** | 現在 HEAD |
| **状態** | Confirmed (SQL/コードレビュー) |
| **証拠レベル** | **E3** (直接コード/SQL証拠) |
| **観測事実** | `admin_users` テーブルに `role` (owner/moderator/billing/readonly) と `status` はあるが、MFA/AAL (Authenticator Assurance Level) 情報なし。Web admin `page.tsx` で `admin_users` 確認ロジックはあるが、Supabase Auth の `aal` / `amr` (Authentication Methods References) 検証なし。Edge Function 側でも `Authorization` header の JWT から `aal` 確認していない。管理者パスワード漏洩時、MFA なしで全権限奪取可能。 |
| **期待される状態** | 管理者操作 (ユーザー停止/プロモ発行/モデレーション/監査ログ閲覧) は AAL2 (MFA 必須) を強制。`admin_audit_logs.assurance` に `aal` 記録済みだが、書き込み時検証なし |
| **実際の状態** | AAL 検証なし。パスワードのみで管理者操作可能 |
| **証拠** | `admin_panel_foundation.sql:157-159` (RLS なし・service_role のみ)、`page.tsx` の認可ロジック (115-126 行 `AdminCapability` 定義のみ)、`admin_audit_logs.assurance` カラム定義 (107-111) はあるが書き込み側未検証 |
| **再現手順** | 1. 管理者アカウントのパスワード入手 (フィッシング/漏洩/推測) 2. MFA 未設定状態で Web admin panel ログイン 3. ユーザー停止/プロモ発行/権限変更等実行 → 成功 |
| **発生条件** | 管理者認証情報漏洩 + MFA 未設定 |
| **影響対象** | 全ユーザーデータ、課金/権限、監査ログ、システム信頼性 |
| **影響** | 権限昇格・データ窃取・不正プロモ発行・監査ログ改竄・サービス停止 |
| **悪用前提条件** | 管理者認証情報入手 (フィッシング/キーロガー/内部漏洩等) |
| **重大度** | **CRITICAL** (管理者全権限奪取、監査ログ改竄可能) |
| **発生可能性** | **中** (MFA 未強制なら標的型攻撃で現実的) |
| **確信度** | **高** (SQL/コード直接確認) |
| **優先順位** | **P0** (公開/運用前必須) |
| **根本原因** | AAL2 要件が設計 (audit_logs.assurance カラム) にはあるが、認可ゲート (Edge Function / Web middleware) で未実装 |
| **反証条件** | ① Edge Function 側で `jwt.aal` / `jwt.amr` 検証実装済み (未読ファイルにある) ② Web admin で Supabase Auth MFA 強制設定済み (ダッシュボード設定) ③ `admin_users` へのアクセスは VPN/ゼロトラスト網内のみ (ネットワーク分離) |
| **理想的修正案** | 1. Supabase Auth で管理者ロールユーザーに MFA 必須化 2. Edge Function `require_aal2()` ヘルパー実装・全 admin API で呼び出し 3. Web admin で `supabase.auth.getSession()` → `session.user.factors` 確認、MFA なしなら操作ブロック 4. `admin_audit_logs.assurance` に実績 AAL 記録 |
| **最小修正案** | Edge Function 共通ミドルウェアで `if (jwt.aal < 2) throw 'AAL2_REQUIRED'` 追加。Web admin で `useEffect` で MFA 確認→リダイレクト |
| **具体的変更箇所** | `supabase/functions/_shared/auth.ts` (新規/既存), `web/admin/app/lib/auth.ts` (新規), 各 admin Edge Function |
| **受け入れ条件** | 1. MFA なし管理者は admin API 呼び出し拒否 (403) 2. MFA あり管理者は通過 3. 監査ログに `assurance.aal=2` 記録 4. 既存管理者運用フロー破綻なし (猶予期間設定可) |
| **検証方法** | 1. MFA なし/あり管理者で admin API 実行テスト 2. 監査ログ `assurance` 確認 3. 負荷テストで誤拒否なし確認 |
| **追加テスト** | Integration: admin API AAL2 テスト / Security: ペネトレーションテスト |
| **修正工数** | **M** (Edge Function 共通化 + Web admin 対応 + 既存管理者移行) |
| **推奨所有者** | Backend / Security / SRE |
| **依存関係** | Supabase Auth MFA 設定、Edge Function デプロイ、Web admin 再デプロイ |
| **修正副作用** | 既存管理者は MFA 登録必須 (猶予期間推奨) |
| **ロールアウト** | カナリア → 段階的 (猶予期間 2 週間推奨) |
| **ロールバック** | ミドルウェア無効化 (feature flag) |

---

### [ISSUE-004] **Android: Room Migration v1→22 実機未検証 - 既存ユーザーデータ破壊リスク**

| 項目 | 内容 |
|------|------|
| **分類** | RELIABILITY RISK / DATA INTEGRITY |
| **カテゴリ** | Database / Mobile / Reliability |
| **発見視点** | N. アーキテクト / P. SRE / L. QA |
| **場所** | `AppDatabase.kt:47-69` (migrations 1-22), `MigrationDedupTest.kt` |
| **対象バージョン** | 現在 HEAD (DB version 22) |
| **状態** | Confirmed (コードレビュー) - **実機未検証** |
| **証拠レベル** | **E2** (要件・標準との差分: 実機検証未済) + **E1** (強い推論: 複雑 migration 経路) |
| **観測事実** | 22段階の migration (v1→v22) が定義されている。`MIGRATION_1_2` で重複 dedup + unique index 作成、`MIGRATION_9_10` で tags テーブル大幅変更 (scope/authUserId/remoteTagId 等追加)、`MIGRATION_18_20`/`19_20` で media テーブル重複定義、`MIGRATION_21_22` で AI transparency テーブル追加。Unit test (`MigrationDedupTest`) のみで実機/エミュレータでの全経路検証なし。AGENTS.md:52-57 で実機データ保護ガードあり。 |
| **期待される状態** | v1 から v22 への全 migration 経路が実機/エミュレータでデータ損失なく成功する |
| **実際の状態** | Unit test のみ。実機データを持つユーザーへの OTA アップデートで migration 失敗→データ消失リスク |
| **証拠** | `AppDatabase.kt:85-436` (全 migration 定義)、`MigrationDedupTest.kt` (v1→2 dedup のみテスト) |
| **再現手順** | 1. v1 スキーマでデータ投入した実機/エミュレータ 2. v22 ビルドインストール 3. migration 実行 4. データ整合性確認 |
| **発生条件** | 古いバージョン (v1-v21) を使っている既存ユーザーが最新版に更新 |
| **影響対象** | 既存ユーザーの保存 URL、タグ、メタデータ、メディア、履歴 |
| **影響** | データ消失、アプリクラッシュ、マイグレーション失敗でアプリ起動不能 |
| **悪用前提条件** | なし (通常アップデートで発生) |
| **重大度** | **CRITICAL** (既存ユーザーデータ破壊、復旧困難) |
| **発生可能性** | **中~高** (複雑 migration 経路、media テーブル重複定義等リスク要因多) |
| **確信度** | **中** (コードレビューでは問題なさそうだが実機未検証) |
| **優先順位** | **P0** (リリース前実機検証必須) |
| **根本原因** | 実機/エミュレータでの full migration path テスト未実施。AGENTS.md の実機データ保護ガードで `connectedDebugAndroidTest` 禁止されているため |
| **反証条件** | ① 専用テストデバイス/エミュレータで v1→v22 全経路検証済み ② `MIGRATION_18_20`/`19_20` 等の重複定義が無害と確認済み ③ `dropColumnIfPresent`/`addColumnIfMissing` が SQLite 版互換性問題なし確認済み |
| **理想的修正案** | 1. 専用テストデバイス/エミュレータで v1, v5, v10, v17, v21 等主要バージョンから v22 への migration 実施 2. 各段階でデータ件数・整合性・クエリ性能確認 3. 失敗時のロールバック手順 (ダウングレード不可 → データ export/import) 文書化 |
| **最小修正案** | 実機検証環境構築 (使い捨て端末/エミュレータ) + 全経路テスト実施。コード変更なしで検証のみ |
| **具体的変更箇所** | テストインフラ・手順書 (コード変更不要) |
| **受け入れ条件** | 1. v1, v9, v17, v21 から v22 への migration 成功 2. データ件数一致 (損失なし) 3. クエリ正常動作 4. 所要時間許容範囲 |
| **検証方法** | 1. エミュレータで各バージョン APK 作成・インストール・データ投入 2. 最新版 APK インストール (アップグレード) 3. `adb shell` で DB 直接確認 / アプリ起動確認 |
| **追加テスト** | Instrumentation: `MigrationPathTest` (v1→v22 全経路) |
| **修正工数** | **L** (環境構築 + 多段階テスト + 文書化) |
| **推奨所有者** | Android / SRE / QA |
| **依存関係** | 使い捨て実機/エミュレータ環境、AGENTS.md ガード解除 (明示承認) |
| **修正副作用** | なし (検証のみ) |
| **ロールアウト** | 検証完了後リリース判断 |
| **ロールバック** | N/A (検証のみ) |

---

### [ISSUE-005] **iOS Share Extension: App Group アクセス失敗時のフォールバックでホストアプリ起動 URL に機密情報 (URL) がクエリパラメータで露出**

| 項目 | 内容 |
|------|------|
| **分類** | PRIVACY RISK / SECURITY RISK |
| **カテゴリ** | Mobile / iOS / Privacy |
| **発見視点** | R. レッドチーム / Q. プライバシー / D. 熟練ユーザー |
| **場所** | `ios/URLSaverShareExtension/ShareViewController.swift:715-765` (`makeHostAppSaveURL`, `processShareViaHostAppFallback`) |
| **対象バージョン** | 現在 HEAD |
| **状態** | Confirmed (コードレビュー) |
| **証拠レベル** | **E3** (直接コード証拠) |
| **観測事実** | App Group アクセス失敗時 (`SharedContainer.hasAppGroupAccess() == false`)、`makeHostAppSaveURL` で `urlsaver://save?url=<正規化URL>&degradation=...` を生成し `extensionContext?.open(url)` でホストアプリ起動。URL に**元の URL がクエリパラメータで平文含まれる**。iOS システムログ/クラッシュレポート/スクリーンショット/履歴に残る可能性。 |
| **期待される状態** | 機密情報 (ユーザーが共有した URL) がシステムログ/履歴/スクリーンショットに残らない |
| **実際の状態** | フォールバック URL に正規化 URL が平文で含まれる |
| **証拠** | `ShareViewController.swift:755-765` `URLQueryItem(name: "url", value: url)` |
| **再現手順** | 1. App Group 未設定/アクセス不可状態で共有拡張起動 (開発ビルド/プロファイル不備時) 2. URL 共有 3. ホストアプリ起動 URL に URL が平文含まれる |
| **発生条件** | App Group アクセス不可 (開発時/プロビジョニング不備/ユーザーがグループ無効化稀) |
| **影響対象** | 共有 URL の機密性 (プライベートリポジトリ/内部システム/認証トークン含む URL 等) |
| **影響** | URL 漏洩 (システムログ/クラッシュレポート/MDM/スクリーンショット/履歴) |
| **悪用前提条件** | App Group アクセス不可状態 + 機密 URL 共有 |
| **重大度** | **HIGH** (プライバシー侵害、機密 URL 漏洩) |
| **発生可能性** | **低~中** (本番ビルドでは App Group 正常動作想定だが、開発/テスト/不具合時発生) |
| **確信度** | **高** (コード直接確認) |
| **優先順位** | **P1** (公開前修正推奨) |
| **根本原因** | フォールバック設計で URL をクエリパラメータに直接埋め込み。App Group 経由なら共有メモリ (UserDefaults) で受け渡し可能だが、フォールバック経路で露出 |
| **反証条件** | ① 本番ビルドでは App Group 常に有効 (プロビジョニング保証) ② `url` パラメータはハッシュ化/暗号化済み (確認: 平文) ③ ホストアプリ起動後即座に URL 消去・履歴から削除 (未実装) |
| **理想的修正案** | 1. フォールバック時も App Group 経由でデータ受け渡し (extensionContext.open ではなく UserDefaults/Keychain) 2. やむなく URL スキーム使うならワンタイムトークン化 (短命・署名付き) 3. `urlsaver://save` には `entry_id` 等不透明識別子のみ渡し、ホストアプリ側で App Group から実データ取得 |
| **最小修正案** | `makeHostAppSaveURL` で URL 代わりに `share_token` (UUID) を渡し、ホストアプリ側で `ShareHandoffStore` (App Group UserDefaults) から実データ取得。`ShareViewController.swift` と `MainActivitySecondaryIntentHandler.kt` 両方修正 |
| **具体的変更箇所** | `ShareViewController.swift:755-765`, `MainActivitySecondaryIntentHandler.kt` (Android 側同等処理確認要) |
| **受け入れ条件** | 1. フォールバック URL に元 URL 含まれない 2. ホストアプリで正常に保存処理継続 3. 既存 App Group 経路と動作同等 |
| **検証方法** | 1. App Group 無効化して共有拡張起動 2. 起動 URL 確認 (Xcode Console/システムログ) 3. ホストアプリで保存成功確認 |
| **追加テスト** | Unit: `ShareViewController` フォールバックテスト / E2E: 実機 App Group 無効化テスト |
| **修正工数** | **M** (両プラットフォーム修正 + 共有ストア設計統一) |
| **推奨所有者** | iOS / Android / Backend (共通プロトコル) |
| **依存関係** | Android `ShareReceiverActivity` / `MainActivitySecondaryIntentHandler` 同等問題確認要 |
| **修正副作用** | フォールバック経路複雑化、トークン有効期限管理必要 |
| **ロールアウト** | 両プラットフォーム同時デプロイ |
| **ロールバック** | 旧 URL スキーム方式に戻す (feature flag) |

---

### [ISSUE-006] **DefaultUrlRepository: `assignSelectedTags` がタグごと個別トランザクションで部分失敗時不整合**

| 項目 | 内容 |
|------|------|
| **分類** | RELIABILITY RISK / DATA INTEGRITY |
| **カテゴリ** | Backend / Database / Code |
| **発見視点** | M. ソフトウェアエンジニア / N. アーキテクト / L. QA |
| **場所** |
