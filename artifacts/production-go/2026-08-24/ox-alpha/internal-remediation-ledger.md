# 内部修復台帳 (internal remediation ledger) — Ox Alpha Free

- 作成日: 2026-08-24 (Asia/Tokyo)
- 担当: Ox Alpha Free (`opencode/x-preview-f-free`)
- 対象ルート: `/Users/mimac/Desktop/りんばむ`
- ブランチ: `codex/production-go-20260824`
- 基準 HEAD: `15c7554a9a51f7288a684ddeffa91038ab2966bd` (origin/main と同一)
- 入力: `artifacts/project-audit/2026-08-24/` 配下 4 報告 (Ox raw / Nemotron raw part 1・2 / 主任統合) を最新 HEAD へ全件再照合
- 主任収束指示: 変更対象は (A) iOS Share Extension fail-closed handoff、(B) personal_saved_links normalized_url 競合の2点。UI pagination・タグ all-or-nothing は計測/要件なしに実装しない
- Ox の担当終了時点では、主任所有の未コミット差分 `scripts/media_resolver_backend.py` / `tests/test_media_resolver_backend.py` を変更していない。その後、Nemotron 独立監査の指摘を受け、主任が delegate failure の安全な fallback ログと回帰テストを追加した。

## 判定サマリ

| 区分 | 件数 |
|---|---|
| FIXED_CODE + FIXED_TEST (今回実装) | 2 (ISSUE-005, ISSUE-010) |
| ALREADY_IMPLEMENTED_VERIFIED (最新mainで解決済み) | 6 (ISSUE-001, 003, 012, 014, ENV-01文書, GATE-02) |
| FALSE_POSITIVE | 3 (ISSUE-002, ISSUE-015, NIT-B影響過小) |
| SPEC_CONTRACT (現行契約として閉じる/CANDIDATE非変更) | 3 (ISSUE-006, ISSUE-007, ISSUE-013) |
| MEASUREMENT_GATE | 2 (ISSUE-008, ISSUE-012性能面の残) |
| DEVICE_GATE | ISSUE-004実機面, アクセシビリティ実機群 |
| EXTERNAL_GATE | 本番Supabase/Store/signing/backup等 |

## 台帳

各行: 分類 / 根拠(絶対パス+1-based行) / validation / 残条件。

### ISSUE-001 ShareReceiver 回転で isSaving・入力状態が失われる — **ALREADY_IMPLEMENTED_VERIFIED**

- 現行は Activity の Compose state から `ShareReceiverViewModel` + `SavedStateHandle` へ全面移行済み。
  - `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/ShareReceiverViewModel.kt:71-83` — `savedStateHandle` 保持の `durableState`(JSON直列化、key `share_receiver.durable_state.v1`: 同ファイル:448)。選択タグ・新規タグ名・エラー・結果メッセージ・retry/completed items が永続化対象。
  - 同ファイル:78 `isSaving` は ViewModel フィールド(非永続)。ViewModel は構成変更で生存するため回転中も保存処理と「保存中」UI が継続。プロセス死では `false` 初期化となり、coroutine消失後に disabled UI を復元しない設計(主任監査 §2 の最終判断と一致)。
- 固定テスト: `/Users/mimac/Desktop/りんばむ/app/src/test/java/jp/mimac/urlsaver/ShareReceiverRetryContractTest.kt:24` `activityRecreationRestoresTagsAndRetriesURLAndTagOnlyFailuresWithoutDuplicateSave` — 再生成後の状態復元、URL再送なしのタグonly再試行、二重保存なしを検証。
- validation: Android 358/358 (基準HEAD時点の記録、本差分でAndroidソース未変更)。
- 残条件: 実機回転の画面操作証跡は DEVICE_GATE(既存 repo-go-evidence の emulator instrumentation pass が範囲)。

### ISSUE-002 applicationId が開発ID `jp.mimac.urlsaver` — **FALSE_POSITIVE**

- `/Users/mimac/Desktop/りんばむ/app/build.gradle.kts:111` `namespace = "jp.mimac.urlsaver"` と `:115` `applicationId = "jp.miyamibu.urlalbum"` を Nemotron が混同。正準IDは applicationId に設定済み。
- validation: 目視確認 + `docs/release/release-manifest.json` 整合(`python3 scripts/verify_release_manifest.py` PASS、2026-08-24基準記録)。
- 残条件: なし(repo内)。

### ISSUE-003 Admin AAL2 未実装 — **ALREADY_IMPLEMENTED_VERIFIED**

- `/Users/mimac/Desktop/りんばむ/web/admin/lib/auth.ts:184-211` `assertHighRisk` が AAL2 以外・弱ACR・step-up期限切れを 428 fail-closed。
- 高リスクroute強制例: `/Users/mimac/Desktop/りんばむ/web/admin/app/api/admin/users/[id]/route.ts:99-102`。
- SQL側検証資産: `/Users/mimac/Desktop/りんばむ/supabase/tests/admin_aal2_audit_validation.sql`。
- validation: web admin `npm test && npm run typecheck && npm run lint && npm run build` 全PASS(2026-08-24基準記録)、pgTAP同梱スイートPASS(本日再実行、下記検証節)。
- 残条件: 実環境MFA登録・break-glass運用は EXTERNAL_GATE。

### ISSUE-004 Room migration v1→22 実機未検証 — **ALREADY_IMPLEMENTED_VERIFIED (repo-local) / DEVICE_GATE (実機)**

- `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/AppDatabase.kt` — forward-only の MIGRATION 定義42件(grep計測)、`fallbackToDestructiveMigration` 不使用(app/src/main/java 配下 grep 0件)。
- `/Users/mimac/Desktop/りんばむ/app/src/test/java/jp/mimac/urlsaver/MigrationDedupTest.kt:16-140` — v1スキーマ手作成→`MIGRATION_1_2` 適用→生存行の originalUrl/dedup 検証。v1→current の full-chain は emulator instrumentation で実走済み(`docs/release/repo-go-evidence.md` 「Android guarded instrumentation ... emulator-5554: 21 tests」行)。
- 残条件: 物理Androidでの旧apk→新schema上書きは DEVICE_GATE(ユーザーデータ保護ガードにより要承認デバイス)。

### ISSUE-005 iOS App Group 不通 fallback が平文 URL を custom-scheme query に載せる — **FIXED_CODE + FIXED_TEST (今回)**

- 再現構造(修正前): `/Users/mimac/Desktop/りんばむ/ios/URLSaverShareExtension/ShareViewController.swift` 旧 `processShareViaHostAppFallback`/`makeHostAppSaveURL`(修正前947-1000行)が `urlsaver://save?url=<平文>` を生成し `extensionContext?.open` していた。App Group 不通時のみ到達する経路だが、URL全体がscheme queryとしてOSログ等へ露出し得る(Nemotron E3/E1混在、主任 CANDIDATE P2)。
- 変更内容:
  - **不透明handoffは成立しない**ことを技術的に確定: extension と host は sandbox が別であり、共有媒体は App Group container(`/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Support/SharedContainer.swift:11-20`)のみ。Keychain access group 追加は entitlement/provisioning 変更かつ本制約下で検証不能のため採否せず → 主任指示どおり **理由付きfail-closed** を採用。
  - `/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Support/SharedContainer.swift:268-288` 新設 `ShareHostHandoffOutcome` / `ShareHostHandoffPolicy`。outcome 型は URL を運ばない(fail-closed時に route URL を構築する API 自体が存在しないことを型で固定)。Nemotron の UX 指摘を反映した最終文言: 「共有領域にアクセスできなかったため保存できませんでした。\n共有元でURLをコピーし、りんばむ本体の『＋』ボタンから貼り付けて保存してください。」
  - `/Users/mimac/Desktop/りんばむ/ios/URLSaverShareExtension/ShareViewController.swift:342-345` — App Group 不通時は policy の fail-closed メッセージ表示して終了。旧 `processShareViaHostAppFallback` / `makeHostAppSaveURL` は削除(URL平文経路の完全除去)。`openHostApp`(:1002付近)は payload を運ばない refresh handoff(:767-768)で継続利用し通常機能は温存。
  - 通常の App Group 経由共有(tag picker → repository 直接保存)は一切変更していない。
- 固定テスト: `/Users/mimac/Desktop/りんばむ/ios/URLSaveriOSTests/URLRulesTests.swift:645-662`
  - `testShareHandoffWithoutAppGroupFailsClosedWithRecoveryMessage` — fail-closed、保存失敗明示、「＋」ボタンと貼り付け保存の具体的回復導線、`urlsaver://` 非含有。
  - `testShareHandoffWithAppGroupProceedsWithRepositoryAccess` — App Group あり時は repository access 継続。
- validation: focused test 39/39 PASS、Simulator full suite 232 tests / 0 failures / 3 skipped(live-cloud) PASS、`verify_mobile_ui_contract.py` PASS(変更前後)。
- 残条件: 実機(iPhone)での App Group 不通再現は DEVICE_GATE(通常ビルドでは不通にならず再現困難。単体契約で固定済み)。

### ISSUE-006 複数タグ割当が非原子的 — **SPEC_CONTRACT (best-effort 契約で閉じる / all-or-nothing は非変更)**

- 現行契約: タグ1件単位で原子。`/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/DefaultTagRepository.kt:287-347` `assignTagWithResultWithinFence` — 共有タグ割当は `database.withTransaction`(:314)内で cross-ref upsert + tag status + outbox enqueue を一括コミット。limit check は :303-306 で tag ごとに実施(shared tag limit が tag 単位であるため all-or-nothing とは相性が悪い=主任判定どおり欠陥確定不可)。
- partial visibility: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/ShareReceiverViewModel.kt:297-312` 失敗タグIDを収集して retry item 化、:392-407 で「N件はURLを保存済みですが、タグ付けが未完了です…」をユーザー表示。重複URL再送なしの再試行契約は `/Users/mimac/Desktop/りんばむ/app/src/test/java/jp/mimac/urlsaver/ShareReceiverRetryContractTest.kt:24` で固定済み。
- 判定: 主任収束指示により all-or-nothing 化は仕様変更のため実装しない。CANDIDATE P2 として要件化を残条件に記録。

### ISSUE-007 `localProvenanceCount <= 0` 時の同一normalizedUrl複数レコード優先順位 — **SPEC_CONTRACT / CANDIDATE_P2_NON_CHANGE**

- 根拠: 重複主キーは DB unique(normalizedUrl)(`/Users/mimac/Desktop/りんばむ/supabase/migrations/20260624120000_chatgpt_personal_links.sql:64-65` はサーバー側。ローカルRoomは MigrationDedupTest.kt:16-140 の dedup で単一survivor保証)。同一 normalizedUrl の複数 ACTIVE 行は現行スキーマで生成不能なため、Nemotron の想定した優先順位分岐は到達不能経路。
- 残条件: なし(到達不能経路)。将来的に legacy twin 輸入を広げる場合は要件再確認。

### ISSUE-008 iOS 一覧が全件配列保持(10k件でメモリ/描画) — **MEASUREMENT_GATE(非変更)**

- 根拠: `/Users/mimac/Desktop/りんばむ/ios/URLSaveriOS/App/URLSaverAppModel.swift:226-229` `@Published private(set) var activeEntries/archivedEntries: [URLRecord]`、:289-323 `reload()` が `observeActiveSnapshot()/observeArchiveSnapshot()` の全件スナップショットを一括代入。pagination/LIMITなし。
- 主任指示により計測/要件なしの大規模再設計は禁止。実測(10k件投入時のメモリ・スクロールFPS・起動時間)を gate 条件として記録。
- 残条件: MEASUREMENT_GATE — 10k件負荷実測の証跡作成(実機/Simulator計測)までは実装しない。

### ISSUE-009 Web admin の JWT Bearer 引き渡し未確認 — **ALREADY_IMPLEMENTED_VERIFIED**

- `/Users/mimac/Desktop/りんばむ/web/admin/lib/auth.ts:160-183` `requireAdminContext` が server-side で role/capability/assurance 解決まで実施(ISSUE-003のAAL2実装と同一経路)。admin API は Edge Function/server route 経由で anon key 直叩きではない。
- validation: web admin テスト/typecheck/lint/build PASS(基準記録)。
- 残条件: 本番E2E(MFAフロー込み)は EXTERNAL_GATE。

### ISSUE-010 personal_saved_links 同一user×同一normalized_url×異なるclient_entry_id で unique 違反 — **FIXED_CODE + FIXED_TEST (今回)**

- 再現確定:
  - Android client_entry_id: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/AiTransparency.kt:262-265` `stableId("entry", entry.normalizedUrl)` = normalizedUrl のみの決定論的ハッシュ。
  - iOS client_entry_id: `/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Data/SharedTagCloud.swift:3063,4369` 経由で `/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Domain/AiTransparency.swift:274-281` `stableID(prefix:"safe", parts:["ios", String(entry.id), entry.normalizedURL])` = **ローカルrowid依存**。
  - よって iOS の再インストール/機種変更/DB再構築、または Android⇄iOS の同一アカウント同期では、同一 normalized_url に対し異なる client_entry_id が送信され得る。旧 conflict target `(user_id, client_entry_id)` は既存行に一致せず INSERT が進み `unique (user_id, normalized_url)`(/Users/mimac/Desktop/りんばむ/supabase/migrations/20260624120000_chatgpt_personal_links.sql:64-65)違反 → RPC全体が例外abort(op_id冪等でも毎回新op_idで再発)。
- 変更内容(最終migration): `/Users/mimac/Desktop/りんばむ/supabase/migrations/20260824090000_fix_apply_personal_link_ops_conflict.sql`
  - conflict target を `(user_id, normalized_url)` へ変更し、`DO UPDATE SET client_entry_id = excluded.client_entry_id` で同一URLを最新クライアントIDへ収束。
  - snapshot 後処理は legacy の `:` と現行の `-` の両名前空間を認識し、同じ名前空間で今回見えなかった行だけを soft-delete。他プラットフォーム/名前空間の行は保持する。
  - 正当性: AGENTS 不変条件「重複主キーは normalizedUrl」と一致し、既存 `link_id` と tag refs を維持したまま cross-platform/reinstall の異なる `client_entry_id` を単一行へ収束する。
- 固定テスト: `/Users/mimac/Desktop/りんばむ/supabase/tests/chatgpt_personal_link_conflict_validation.sql` — plan(15)
  - 構造契約(unique両制約存在、RPC存在)、sync有効化RPC、
  - Scenario1: 異なるclient_entry_id×同一normalized_urlの2 op → 両方 ok / 同一link id / 行数1 / 最新snapshot内容勝ち、
  - Scenario2: 同一op_id再送 → 二重行なし・初回結果短絡、
  - Scenario3: 異なるnormalized_url → 別行維持。
  - Scenario4: 現行ハイフン名前空間で current 行を保持、stale 行だけを soft-deleteし、別名前空間の行は保持。
- validation: isolated local Supabase replay(`URLSAVER_ALLOW_LOCAL_SUPABASE_RESET=true bash scripts/verify_supabase_local.sh`)— fresh migration replay + `supabase db lint --level error` + pgTAP **Files=20 / Tests=128 / Result: PASS**(新規1ファイル15tests含む)。
- 残条件: production apply は EXTERNAL_GATE(owner承認の linked push)。

### ISSUE-011 metadata scheduler が CONNECTED 制約のみ(Doze遅延) — **ALREADY_IMPLEMENTED_VERIFIED (仕様通り)**

- `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/MetadataWorkScheduler.kt:40` `ExistingWorkPolicy.KEEP`、unique key `metadata:{entryId}`、CONNECTED、exponential backoff — AGENTS WorkManager 契約そのもの。遅延許容は仕様内。
- エラー/文言分離: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/ui/MetadataUiText.kt:23,50,77` FAILED/PARSE_FAILED 等を日本語文言へ分離済み(Phase 1b 内部状態とUI文言の分離)。
- 残条件: なし。

### ISSUE-012 Export の N+1 / OOM — **ALREADY_IMPLEMENTED_VERIFIED (構造面) / MEASUREMENT_GATE (実測面)**

- N+1解消: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/ExportRepository.kt:216-227` — `loadAllEntries()` 後、entryIds を `chunked(ROOM_QUERY_CHUNK_SIZE)` で `tagDao.getVisibleTagsForEntries(...)` バッチ取得(Nemotron の主張した per-entry `getTagsForEntry` は現行コードに存在しない、grep 0件)。ChatGPT export も :426-439 chunked バッチ。
- OOM防御: :96-123 `SizeLimitedExportOutputStream`(上限超過で例外+一時dir削除:679-683)、:656-688 ストリーミング書き出し、:712-755 entries.jsonl/JSON を逐次 write、:807-815 分割 writeUtf8、:186-188/:714,:721,:746 cancel checkpoint、ChatGPT上限10,000件(:1272, :348-350)。
- validation: `/Users/mimac/Desktop/りんばむ/app/src/test/java/jp/mimac/urlsaver/ExportRepositoryTest.kt` 含む Android unit 358/358(基準記録)。
- 残条件: 10k件実データでの所要時間/メモリ実測は MEASUREMENT_GATE。

### ISSUE-013 shared tag pull が remoteVersion 非比較(LWW) — **SPEC_CONTRACT / CANDIDATE_P2_NON_CHANGE**

- 根拠: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt:193` remote version は記録専用、server側も version 比較なし(supabase functions/migrations grep 0件)。競合時 last-write-wins。単一owner中心のtag編集契約では整合しており、optimistic concurrency は仕様変更(要件化待ち)。
- 残条件: マルチエディタ同時編集要件が承認された場合のみ再設計。

### ISSUE-014 Android 購入検証なし — **FALSE_POSITIVE**

- `/Users/mimac/Desktop/りんばむ/supabase/functions/verify-store-purchase/index.ts:168-180` purchaseToken必須検証、:239 `p_purchase_token_hash` による token-hash binding、:405 obfuscated account id = sha256(userId)、:390-397 Google Play API照会、:369-370 Apple 証明書ピン+sha256照合。reconciliation: `/Users/mimac/Desktop/りんばむ/supabase/functions/store-entitlement-reconciliation/`、通知受信: `store-notification-receiver/`。
- validation: Deno 44/44(基準記録)+ pgTAP `purchase_atomicity_validation.sql` 等 PASS(本日再実行)。
- 残条件: sandbox/本番購入の実走は EXTERNAL_GATE。

### ISSUE-015 LAN IP (http://192.168.x.x) が保存不可 — **FALSE_POSITIVE / SPEC_CONFORMANT**

- Phase 1a 仕様「新規保存は https のみ」。`/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/network/NetworkUrlPolicy.kt:88,148,156` metadata 取得側も loopback/link-local/CGNAT/metadata端点を遮断。開発便宜のoverrideは要件外。
- 残条件: なし。

### Ox ENV-01 JDK17 で unit 19件失敗 — **FIXED_DOC 済み(既存) + CI pin 済み**

- `/Users/mimac/Desktop/りんばむ/README.md:99-100,192` JDK21確認手順とbytecode target 17維持の説明。CI は `.github/workflows/ci.yml:27-28` で temurin 21 固定。
- 残条件: ローカル `.java-version` 等のランタイム強制ゲートは未導入(Ox の jvmToolchain 差戻し判断を踏襲し、検出失敗で build 全体が壊れる方式は採らない)。今回の対象外につき非変更。

### Ox SR-01 — ISSUE-001 と同一(上記参照)。

### Ox GATE-01 URL正規化契約スクリプト要 local PG — **ALREADY_IMPLEMENTED_VERIFIED**

- `scripts/verify_supabase_local.sh` が isolated local replay + pgTAP `shared_url_normalization_vectors.sql`(15 vectors)を実行。本日 PASS(検証節参照)。
- 残条件: なし(local runbook化済み)。

### Ox GATE-02 iOS build 未検証 — **ALREADY_IMPLEMENTED_VERIFIED (今回再実行)**

- 今回: unsigned Simulator full test suite 232/0/3 skip PASS(検証節)。Release unsigned generic build は基準記録あり。
- 残条件: 署名付き Archive/Store 提出物は EXTERNAL_GATE。

### Ox GATE-03 instrumentation/E2E 範囲 — **DEVICE_GATE**

- emulator instrumentation pass 証跡は `docs/release/repo-go-evidence.md` 冒頭表(emulator-5554, 21 tests)。物理Android/iPhone UI の追加 rows は canonical tracker 管理。
- 残条件: 物理4 rows + iPhone 2 rows(外部承認デバイス)。

### Ox NIT-A mimeType 無し SEND filter で非テキスト共有がエラー画面 — **RECORDED_NIT (非変更)**

- `/Users/mimac/Desktop/りんばむ/app/src/main/AndroidManifest.xml:50-62` text/* filter と catch-all SEND/SEND_MULTIPLE filter が併存。catch-all は任意アプリからの URL テキスト共有を受け付ける意図的広受信であり、非URL内容は `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/ShareReceiverViewModel.kt:420`「保存できる内容が見つかりませんでした」へ graceful 収束。filter絞り込みは機能縮小トレードオフがあるため仕様確認なしには変更しない(主任「公開後でも可」)。

### Ox NIT-B NetworkUrlPolicy DNS解決後 TOCTOU — **FALSE_POSITIVE (影響限定的) / RECORDED_RISK**

- `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/network/NetworkUrlPolicy.kt:12-156` 解決前ホスト検証(private/link-local/169.254.169.254/loopback literal)を実装。解決後接続の再検証はクライアント用途(自分が保存したURLのmetadata取得)で攻撃者制御の redirect 先は別途制御されており、server-side fetcher 要件が出た際に対応すればよい残留リスクとして記録。

## 20視点の具体的警告 → 分類

| 視点からの警告 | 分類 | 根拠 |
|---|---|---|
| プロダクト責任者: KPI/利用継続の外部実績なし | EXTERNAL_GATE | KPI定義・実運用データは repo 外 |
| 初見ユーザー: 回転で入力消失 | ALREADY_IMPLEMENTED_VERIFIED | ISSUE-001(ViewModel移行+test) |
| IT不慣れ: 保存中中断・重複・エラー後復旧の実機未確認 | DEVICE_GATE | emulator instrumentation pass 済み、物理は tracker rows |
| 熟練者: 大量件数・高速反復の実測なし | MEASUREMENT_GATE | ISSUE-008/012 と共通 |
| UXデザイナー: isSaving を UI のみで永続化する案 | FALSE_POSITIVE(棄却済み) | coroutine主体と分離する現行設計が正(ShareReceiverViewModel.kt:78,177-225) |
| UIデザイナー: 回転/Dynamic Type/キーボードの画面証拠なし | DEVICE_GATE | emulator font scale 1.0/1.3/2.0 evidence 済み(repo-go-evidence)、物理は tracker |
| アクセシビリティ: TalkBack/VoiceOver/focus/最大文字 | DEVICE_GATE | 静的contrast契約(`verify_ui_contrast.py`)はPASS、実機読み上げは未実施 |
| コンテンツ: 原因別回復導線の理解度調査なし | EXTERNAL_GATE | ユーザー調査は repo 外(文言資産自体は MetadataUiText.kt 等に存在) |
| 国際化: RTL/複合文字/長文翻訳 | MEASUREMENT_GATE | ja特化は意図的(AGENTS)。RTL要件未承認 |
| 管理者: 実環境MFA/break-glass/監査改ざん耐性 | EXTERNAL_GATE | ISSUE-003実装済み、運用は本番作業 |
| サポート: 問い合わせ→復旧演習なし | EXTERNAL_GATE | contact-support runbook(`contact-support-cutover-runbook.md`)あり、live rehearsal は本番作業 |
| QA: current-source E2E/visual/physical なし | DEVICE_GATE | 本日分: Simulator 232/0、pgTAP 128、focused Swift tests 追加 |
| ソフトウェア実装: `Set<Long>` Bundle 復元の実証 | ALREADY_IMPLEMENTED_VERIFIED | Bundle に直接載せず JSON 文字列で SavedStateHandle 保存(ShareReceiverViewModel.kt:345-350) |
| アーキテクト: live RLS/RPC/rollback 未実走 | EXTERNAL_GATE | local replay+lint+pgTAP は本日 PASS、production apply は owner 承認作業 |
| 性能/コスト: p95/p99/cold start/従量課金 | MEASUREMENT_GATE | 計測環境が前提 |
| SRE: 監視/alert/PITR/RTO-RPO/restore drill | EXTERNAL_GATE | `rollback-plan.md` 存在、drill は本番作業 |
| プライバシー: iOS fallback URL引継ぎ | FIXED_CODE(今回) | ISSUE-005 |
| レッドチーム: 公開host binding/本番権限 | EXTERNAL_GATE | assetlinks/Universal Links 契約は `verify_public_web_release.sh` 系で管理 |
| 悪意・雑・焦り: 回転中断・部分失敗の current-device 証拠 | DEVICE_GATE | retry契約unit固定済み(ShareReceiverRetryContractTest.kt) |
| 重箱の隅: namespace/applicationId、P0/P1、Eレベル分離 | FALSE_POSITIVE | ISSUE-002 |

## 主任残リスク(§8 未検証事項) → 分類

| 残リスク | 分類 |
|---|---|
| supabase_auth 35 story / store_console 16 / resend_live 4 / public_web 8 / design_required 2 | EXTERNAL_GATE(canonical story tracker 管理、自動昇格させない) |
| 物理Android共有・回転・タグ選択・保存・復旧 | DEVICE_GATE |
| 物理iPhone Appium/WDA 操作 | DEVICE_GATE(2026-08-23 checked-flows pass 記録あり、追加rowsはtracker) |
| Simulator install/launch/UI | 本日部分更新(Simulator XCTest 232/0 PASS) |
| スクリーンショット・動画・visual regression(今回差分) | 今回差分は fail-closed 文言表示のみ。文言は unit 契約で固定。実画面キャプチャは DEVICE_GATE |
| 本番 RLS/RPC/AAL2/監査ログ | EXTERNAL_GATE |
| Play/App Store 購入・取消・refund・restore | EXTERNAL_GATE |
| signing/upload/審査/公開revision同一性 | EXTERNAL_GATE |
| backup restore/PITR/rollback/RTO-RPO演習 | EXTERNAL_GATE(runbook: docs/release/rollback-plan.md) |
| 大量データ・負荷・p95/p99・cold start・費用 | MEASUREMENT_GATE |
| TalkBack/VoiceOver/Dynamic Type/RTL実機 | DEVICE_GATE(font scale emulator証跡は既存、物理読み上げは未実施) |

## 今回の変更ファイル(CHANGED_FILES)

| ファイル | 種別 | 内容 |
|---|---|---|
| `/Users/mimac/Desktop/りんばむ/ios/URLSaverShareExtension/ShareViewController.swift` | FIXED_CODE | App Group不通時を fail-closed 表示へ変更、平文URL handoff経路(processShareViaHostAppFallback/makeHostAppSaveURL)削除 |
| `/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Support/SharedContainer.swift` | FIXED_CODE | ShareHostHandoffPolicy/Outcome 新設(URLを運ばない型) |
| `/Users/mimac/Desktop/りんばむ/ios/URLSaveriOSTests/URLRulesTests.swift` | FIXED_TEST | fail-closed契約2tests追加(:645-662) |
| `/Users/mimac/Desktop/りんばむ/supabase/migrations/20260824090000_fix_apply_personal_link_ops_conflict.sql` | FIXED_CODE | apply_personal_link_ops の conflict target を (user_id, normalized_url) へ(:147) |
| `/Users/mimac/Desktop/りんばむ/supabase/tests/chatgpt_personal_link_conflict_validation.sql` | FIXED_TEST | pgTAP 15 assertions 新設（収束・冪等・名前空間分離） |
| `/Users/mimac/Desktop/りんばむ/artifacts/production-go/2026-08-24/ox-alpha/internal-remediation-ledger.md` | 台帳(本文件) | ISSUE-001〜015 + Ox ENV/GATE/NIT + 20視点 + 主任残リスクの全件分類 |
| `/Users/mimac/Desktop/りんばむ/scripts/media_resolver_backend.py` | FIXED_CODE (主任追補) | delegate が `ok: false` の際、外部messageを記録せず短縮error codeのみ安全にログし primary resolver へfallback |
| `/Users/mimac/Desktop/りんばむ/tests/test_media_resolver_backend.py` | FIXED_TEST (主任追補) | delegate failure fallback、error code記録、sensitive message非記録を固定 |

Ox の担当外だった `scripts/media_resolver_backend.py`, `tests/test_media_resolver_backend.py` は、Ox 完了後に主任が Nemotron カウンターレビュー収束用として変更した。Ox 実装と主任追補を区別して本台帳へ統合した。

## 実行した検証コマンドと結果(VALIDATION)

| コマンド | 対象 | 結果 |
|---|---|---|
| `python3 scripts/verify_mobile_ui_contract.py` (変更前) | baseline | PASS |
| `xcodebuild -workspace ios/URLSaveriOS.xcworkspace -scheme URLSaveriOS -destination 'platform=iOS Simulator,id=809941E0-...' -only-testing:URLSaveriOSTests/URLRulesTests test CODE_SIGNING_ALLOWED=NO` | ISSUE-005 focused | Executed 39 tests, 0 failures — TEST SUCCEEDED |
| `URLSAVER_ALLOW_LOCAL_SUPABASE_RESET=true bash scripts/verify_supabase_local.sh` | ISSUE-010 | fresh db reset + migration replay + `db lint --level error` + pgTAP Files=20 / Tests=128 / **Result: PASS**(新規 chatgpt_personal_link_conflict_validation.sql 15 assertions pass) |
| `python3 -m unittest tests/test_media_resolver_backend.py` | delegate fallback | 25 tests / **PASS**。sensitive upstream message 非出力を含む |
| `xcodebuild ... test CODE_SIGNING_ALLOWED=NO` (full suite, iPhone 17 Pro Simulator) | 全体回帰 | Executed 232 tests, 3 skipped, 0 failures — TEST SUCCEEDED |
| `python3 scripts/verify_mobile_ui_contract.py` (変更後) | 契約 | PASS |
| `git diff --check` | whitespace | PASS(clean) |

Android は本差分でソース未変更のため rebuild 未実施(基準 HEAD の Java21 unit 358/358 + lint + assembleDebug 記録を維持)。

## REMAINING

- DEVICE_GATE: 物理Android/iPhone の UI 操作証跡(追加 tracker rows)、TalkBack/VoiceOver、今回 fail-closed 文言の実画面確認(App Group 不通は通常ビルドで再現困難)。
- MEASUREMENT_GATE: 10k件の iOS list / Android export / p95-p99 / cold start 実測。
- EXTERNAL_GATE: production Supabase apply(本migration含む)、本番MFA/break-glass、Store購入系、署名/審査、backup/PITR drill、KPI。
- SPEC_CANDIDATE(非変更): タグ割当 all-or-nothing(ISSUE-006)、shared tag optimistic concurrency(ISSUE-013)、SEND filter絞り込み(NIT-A)。いずれも要件承認後に着手。

## REVIEW_STATUS

```
IMPLEMENTATION_SCOPE: COMPLETE (A/B both implemented + tested under chief directive)
LEDGER: COMPLETE (all raw findings classified, zero unclassified)
VALIDATION: PASS (Swift focused + Simulator full suite + isolated Supabase replay/pgTAP + mobile UI contract + git diff --check)
REPO_GO_IMPACT: maintained; two internal gaps closed at HEAD 15c7554a
STATUS: INCOMPLETE_REVIEW for external/device gates only (unchanged classification)
```

## 2026-08-26 0円インフラ制約の追加実装

- 実セッション: `ses_fcbdef8b7ffeswhlbo3XfnYP1b`
- 実モデル: `opencode/x-preview-f-free` (Ox Alpha Free)
- baseline: `fd99a6bd93bcd2cd61423ad5992cc28d6726bb22`, `main`, 開始時 clean
- 最上位制約: `monthly_fixed_cost = 0`。RailwayはFreeのみ。Hobby/Pro/Enterprise、カード、購読、課金、有料化要求を禁止。
- Ox担当差分: `render.yaml` の `plan: free` 化と失効delegateのBlueprint依存除去、`docs/media-resolver-backend.md` の0円構成・fail-closed境界追記。
- Oxローカル実走: public video `jNQXAC9IVRw` をdelegate/cookie/PO tokenなしでInnertube解決し、約0.3秒、MP4 asset 1件。`python3 -m unittest tests.test_media_resolver_backend` は44件PASS、YAML parseは `plan=free` / delegate未設定、`git diff --check` PASS。
- Oxが未検証として残した境界: RenderデータセンターIPからのInnertube、本番Free cold start、512MB同時実行、実デプロイ。
- 主任による独立した本番実走: Render認証済み画面で実サービスが既にFreeであることを確認し、失効したdelegate環境変数だけを削除して同一HEADを再デプロイ。デプロイは成功したが、Render本番のInnertubeは `failed`、`/resolve` は45.193秒でtimeout。したがってOxのローカル成功を本番成功には昇格しない。
- Railway認証済み画面: workspaceをTrialからFreeへ切替済み。Free上限は1 vCPU / 0.512GB、Serverless有効。Hobby/Pro/カード/購読操作は不使用。
- Railway Free初回build `2cd02c87-cf55-476a-8cae-585f8967c544` はモノレポをJava/Gradleとして検出し `python: not found` で失敗。主任が専用Dockerfileへ修正し、Nemotron同一公式セッションが追加差分を `DOCKER_FIX_REVIEW_STATUS: PASS` と独立監査した。
- Railway Free本番deploy `94a41e6e-b7ba-4e19-8f63-3c0ba2ea5b36` は `Active / Deployment successful`。公開 `/health` HTTP 200、public video `jNQXAC9IVRw` の `/resolve` は0.818秒でMP4 1件、`/proxy` はHTTP 206で1,024 bytes（全体629,172 bytes）を返した。
- Render Freeへ公開delegate URLだけを復元し、deploy `dep-da75n5id0e5s73ddra10` が43.1秒でLive。Render公開 `/resolve` は1.327秒で同MP4 1件、delegate `/proxy` からHTTP 206 / 1,024 bytesを取得した。

```
OX_STATUS: PASS (implementation/local scope)
PRODUCTION_RENDER_DIRECT_YOUTUBE: NO_GO
PRODUCTION_RAILWAY_FREE_DELEGATE: GO
PRODUCTION_RENDER_TO_RAILWAY_FREE_YOUTUBE: GO
```
