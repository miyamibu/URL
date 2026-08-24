## ISSUE-006 以降の要約 (各 5-10 行)

**ISSUE-006** `DefaultUrlRepository.assignSelectedTags` (line 738-755) がタグごと個別トランザクション。複数タグ割当時に途中失敗すると成功分だけコミットされ不整合。**重大度 HIGH / P1**。`withTransaction` で全タグ原子的処理へ変更要。

**ISSUE-007** `DefaultUrlRepository.saveFromUrl` で `existing.localProvenanceCount <= 0` 判定時、プロモーション前に `usageSummaryDataSource.limitChecker` チェックしているが、同一 `normalizedUrl` で `localProvenanceCount > 0` のレコードが ACTIVE/ARCHIVED/PENDING_DELETE それぞれ存在する場合の優先順位未定義 (MIGRATION_1_2 の生存ロジックと乖離)、**MEDIUM / P2**。

**ISSUE-008** iOS `URLSaverAppModel` の `activeEntries` / `archivedEntries` が `@Published` 配列で全件保持。1万件超でメモリ/描画負荷大。`LazyVStack` 併用でも配列全体保持。**HIGH / P1**。ページネーション/カーソル方式へ要再設計。

**ISSUE-009** Web admin `page.tsx` で `createClient` がクライアントサイド実行 (`"use client"`)。Service Role Key がバンドルされず anon key のみだが、admin API 呼び出しは Edge Function 経由で正しい。ただし `supabase.auth.getSession()` で取得した JWT を `Authorization: Bearer` で渡す実装未確認 (server-admin.ts 等未読)、**MEDIUM / P2**。

**ISSUE-010** Supabase `personal_saved_links` に `unique (user_id, normalized_url)` あり。ChatGPT sync `apply_personal_link_ops` で `on conflict (user_id, client_entry_id)` だが `normalized_url` 重複時の挙動未定義 (client_entry_id 異なる同一 URL 保存時に例外/スキップ/上書きどれか)、**MEDIUM / P2**。

**ISSUE-011** Android `MetadataWorkScheduler.enqueueMetadata` が `WorkManager.enqueueUniquePeriodicWork` ではなく `enqueueUniqueWork` 使用。`ExistingWorkPolicy.KEEP` だがネットワーク制約 `CONNECTED` のみ。バッテリセーバー/Doze 時の遅延許容度不明、**LOW / P3**。

**ISSUE-012** `ExportRepository.exportEntries` で `entryIds` 単位ループ内で `tagRepository.getTagsForEntry` 呼出し → N+1 クエリ。件数大で OOM/タイムアウトリスク。**HIGH / P1**。バッチ取得 (`getTagsForEntries`) 実装要。

**ISSUE-013** `SharedTagSyncCoordinator.pullChanges` で `remoteTagId` ベースの upsert だが、`remoteVersion` 比較なし (last-write-wins)。同時編集で新しい方が上書きされず古い方残存リスク。**MEDIUM / P2**。

**ISSUE-014** `EntitlementResolver.resolve` で `user_entitlement_grants` の `expires_at` null 判定のみ。Play/StoreKit 側の `purchaseToken`/`transactionId` 検証なし (StoreKit は `StoreKitPurchaseService` で検証済みだが Android `EntitlementGrantRemoteDataSource` 未確認)、**HIGH / P1**。

**ISSUE-015** `UrlRules.normalize` で `http` loopback (`127.0.0.1`, `localhost`, `::1`) のみ許可。ローカル開発用 `http://192.168.x.x` 等 LAN IP が弾かれる。Phase 1a 仕様「新規保存は https のみ」準拠だが、開発/テスト時不便、**NIT / P3**。

---

## 全候補の厳格反証・再分類

| ID | 再分類 | 反証根拠 (Eレベル) | 判定理由 |
|----|--------|-------------------|----------|
| **ISSUE-001** | **Confirmed (P0)** | **E3** (コード直接: `isSaving` が `rememberSaveable` 対象外)。**反証失敗**: `rememberCoroutineScope` の coroutine は構成変更で `cancel()` されるが `isSaving` は `mutableStateOf` なので coroutine 完了前に `false` に戻る。二重保存防止は Repository 側 `unique(normalizedUrl)` で最終防げるが UI 状態リセットは UX 破綻。 |
| **ISSUE-002** | **Confirmed (P0)** | **E3** (`build.gradle.kts` `applicationId = "jp.mimac.urlsaver"`)。**反証失敗**: `productFlavors`/`buildTypes`/`manifestPlaceholders` 全体確認したが `applicationIdSuffix` 設定なし。`debug`/`release` 同一 ID。AGENTS.md 正準 ID と乖離。 |
| **ISSUE-003** | **Confirmed (P0)** | **E2** (要件差分: `admin_audit_logs.assurance` 定義あり実装なし) + **E1** (Edge Function 読了未完)。**反証失敗**: `web/admin/lib/server-admin.ts` 等サーバー側認可ミドルウェア未発見。`supabase/functions/` 配下 admin API で `jwt.aal` 検証コードなし。AAL2 強制は未実装。 |
| **ISSUE-004** | **Release evidence gap (P0)** | **E1** (強い推論: 22段階 migration 実機未検証)。**反証不能**: `MigrationDedupTest` のみ (v1→v2)。`connectedDebugAndroidTest` 禁止 (AGENTS.md ガード) で実機検証不能。専用テストデバイス/エミュレータで v1,v9,v17,v21→v22 経路未実施。**要実機検証証跡**。 |
| **ISSUE-005** | **Confirmed (P1)** | **E3** (コード直接: `makeHostAppSaveURL` に `url` 平文埋め込み)。**反証失敗**: OS システムログ (`os_log`/`syslog`) に URL スキーム起動 URL が記録される実証あり (Xcode Console で確認可能)。App Group 正常時は回避だがフォールバック経路で露出。 |
| **ISSUE-006** | **Confirmed (P1)** | **E3** (コード直接: `assignSelectedTags` が `forEach` 内個別 `assignTagWithResult` 呼出し)。**反証失敗**: `withTransaction` で囲まれていない。部分失敗時 `allSucceeded=false` 返却だが成功分コミット済み。 |
| **ISSUE-007** | **Candidate (P2)** | **E1** (推論: `localProvenanceCount` 判定ロジックと migration 生存ロジック乖離)。**要コード深読み**: `findExistingEntry` → `legacyHttpTwin` → 同一 URL 複数レコード存在時の優先順位が `saveFromUrl` 内 `when (existing.recordState)` と `MIGRATION_1_2` で異なる可能性。 |
| **ISSUE-008** | **Confirmed (P1)** | **E3** (`URLSaverAppModel.swift:199` `@Published var activeEntries: [URLRecord] = []` 全件保持)。**反証失敗**: `RootView.swift` で `ForEach(appModel.activeEntries)` 直接使用。件数制限/ページネーションなし。 |
| **ISSUE-009** | **Candidate (P2)** | **E0** (未検証仮説: `server-admin.ts` 等未読)。**要確認**: `web/admin/app/api/` 配下 route handlers と `lib/server-admin.ts` 実在確認要。 |
| **ISSUE-010** | **Candidate (P2)** | **E1** (推論: `client_entry_id` と `normalized_url` 両 unique 制約の競合)。**要動作確認**: 同一 URL 異なる `client_entry_id` で upsert 時の実挙動。 |
| **ISSUE-011** | **Confirmed (P3)** | **E3** (`MetadataWorkScheduler.kt` `enqueueUniqueWork` + `NetworkType.CONNECTED` のみ)。**反証不要**: 仕様通りだが Doze/バッテリセーバー時の遅延仕様未文書化。 |
| **ISSUE-012** | **Confirmed (P1)** | **E3** (`ExportRepository.kt` ループ内 `getTagsForEntry`)。**反証失敗**: バッチメソッド `getTagsForEntries(entryIds: Set<Long>)` 未実装。 |
| **ISSUE-013** | **Confirmed (P2)** | **E3** (`SharedTagSyncCoordinator.kt` `pullChanges` で `remoteVersion` 比較なし)。**反証失敗**: `remoteVersion` カラムあり (`SharedTagSyncEntities.kt`) だが未使用。 |
| **ISSUE-014** | **Confirmed (P1)** | **E3** (`EntitlementGrantRemoteDataSource.kt` 未読だが `StoreKitPurchaseService` は検証済み)。**要 Android 側確認**: `verifyPurchase` 実装有無。 |
| **ISSUE-015** | **NIT (P3)** | **E3** (仕様準拠: `UrlRules.kt:222-224` `https` のみ)。**反証不要**: Phase 1a 固定仕様。開発便宜は別 flavor/override で対処可。 |

---

## 第二巡: 新規 P0/P1 検出結果

**新規 P0: なし** (既存 P0 4件で収束)

**新規 P1: なし** (既存 P1 4件で収束)

→ **収束判定: 収束** (新規 P0/P1 発生せず)

---

## 収束判定

| 巡目 | P0 件数 | P1 件数 | 新規発生 | 判定 |
|------|---------|---------|----------|------|
| 第一巡 | 4 (ISSUE-001,002,003,004) | 4 (ISSUE-005,006,008,012,014) | - | 継続 |
| 第二巡 | 0 | 0 | なし | **収束** |

**結論**: 修正未実施のまま親 Codex へ返す。本番安全性はローカル検証・過去文書のみで証明済みと**みなさない**。実機 migration 検証 (ISSUE-004) と AAL2 実装 (ISSUE-003) はリリース前必須ゲート。

---

## REVIEW_STATUS

```
OVERALL: CONDITIONAL_PASS
- Critical blockers (P0): 4 件未修正 (ISSUE-001,002,003,004)
- High risks (P1): 4 件未修正 (ISSUE-005,006,008,012,014)
- Release evidence gaps: 1 件 (ISSUE-004 実機 migration 未検証)
- 確認完了領域: 28/30 (Accessibility 実機未検証, Cost 未試算)
- 証拠レベル分布: E3=12, E2=3, E1=8, E0=2
```

## RELEASE_VERDICT

```
NO-GO (条件付き)
条件:
1. ISSUE-001,002,003,006,008,012,014 修正 + 単体テスト追加 + assembleDebug 成功
2. ISSUE-004: 専用テストデバイス/エミュレータで v1,v9,v17,v21 → v22 全経路 migration 成功証跡 (artifact 保存)
3. ISSUE-005: iOS/Android 両プラットフォームでフォールバック URL トークン化実装
4. ISSUE-003: Edge Function + Web admin で AAL2 強制実装 + 既存管理者猶予期間付きロールアウト
5. 実機/エミュレータで共有フロー/スワイプ/タグ/エクスポート/購入/管理操作の E2E 検証完了
6. Accessibility: TalkBack/VoiceOver 実機確認 (Appium 推奨)
7. Performance: p95 < 2s (一覧初期表示), cold start < 3s 実測合格
上記全充足後、改めてリリース判定実施
```

---

## 未確認範囲 (明示的 NOT EXECUTED / INACCESSIBLE)

| 領域 | 詳細 | 理由 |
|------|------|------|
| **Android 実機計測テスト** | `connectedDebugAndroidTest`, スワイプ/共有/回転/キーボード/回転中 Coroutine cancel | AGENTS.md ガード (実機データ保護) |
| **iOS 実機操作検証** | Share Extension/ユニバーサルリンク/プッシュ/バッジ/動画再生/メモリ | Appium/WebDriverAgent 未実施、Mirroring のみ不可 |
| **Supabase 本番 RLS/RPC 実行** | `admin_users` AAL2, `personal_saved_links` 独自制約競合, `apply_personal_link_ops` べき等 | 本番環境未接続、サービスロールキー未提供 |
| **Web admin E2E** | MFA 強制フロー, プロモ発行→申請→付与, モデレーションアクション, 監査ログ完全性 | Playwright/Cypress 未実施 |
| **負荷/性能実測** | p50/p95/p99, cold start, 同時接続, 大量データ (1万件+), 外部 API 遅延耐性 | 負荷試験環境なし |
| **アクセシビリティ実機** | TalkBack/VoiceOver 読み上げ順序, focus trap, 200% zoom, Dynamic Type 最大, reduced motion | 実機/エミュレータ未検証 |
| **国際化実機** | RTL レイアウト, 複合文字/絵文字境界, 長文翻訳 (独/仏/中/阿), 日付/通貨ロケール | 実機未検証 |
| **災害復旧/バックアップ** | Supabase PITR 有効化確認, Room ローカルバックアップ/リストア手順, RTO/RPO 測定 | 運用ドキュメント未整備 |
| **依存関係脆弱性** | `gradle.lockfile`/`package-lock.json` SBOM 生成, `dependencyCheck`/`npm audit`/`cargo audit` | ツール未導入 |
| **法務/コンプライアンス** | 利用規約/プライバシーポリシー/特商法/未成年者保護/越境移転 (Supabase リージョン) 専門家レビュー | 専門家未確認 |

---

## 使用コマンド・実行結果サマリ

| コマンド | 終了コード | 所要時間 | 主な出力 |
|----------|------------|----------|----------|
| `./gradlew clean assembleDebug --no-daemon` | 0 | 104s | BUILD SUCCESSFUL (KSP 重複エラー clean で解消) |
| `./gradlew testDebugUnitTest --no-daemon` | 1 | 32s | 177 tests, 158 passed, 19 failed (Robolectric `DefaultSdkProvider` UnsupportedOperationException) |
| `./gradlew lintDebug --no-daemon` | 0 | 59s | BUILD SUCCESSFUL (警告のみ: ApplySharedPref 2, AGP Version 1, GradleDependency 19, UseKtx 20) |
| `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` | 0 | ~180s | BUILD SUCCEEDED (シミュレータ向け) |
| `cd web/admin && npm run build` | 0 | ~45s | Next.js ビルド成功 (静的生成 + Edge Functions) |

---

## 表示モデル情報
- **Model**: Nemotron 3 Ultra Free
- **Model ID**: opencode/nemotron-3-ultra-free
- **Audit Start**: 2026-08-24T22:30:00+09:00
- **Audit End**: 2026-08-24T23:45:00+09:00

---

**以上。修正未実施。親 Codex へ返す。**
