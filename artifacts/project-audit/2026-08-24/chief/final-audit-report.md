# りんばむ プロジェクト完全監査 v3.0 主任統合報告

- 監査日: 2026-08-24 (Asia/Tokyo)
- 主任監査責任者・代表質問者・統合責任者: Codex
- 実装・実走担当: Ox Alpha Free (`opencode/x-preview-f-free`)
- 独立監査担当: Nemotron 3 Ultra Free (`opencode/nemotron-3-ultra-free`)
- 対象ルート: `/Users/mimac/Desktop/りんばむ`
- ブランチ: `codex/修正`
- 基準 HEAD: `eda2f2678c16bfbbda56d398c613012376993e76`
- ネストした `web/usage-guide` HEAD: `dde36e2c253376c01843aa859fddb65d9602374a`
- 追跡済みファイル数: 910
- 基準追跡インデックス fingerprint: `a32b070a36a4a286ea8bde9f30cf75e0be44bc105eac04ab0cf877c6604b1d0f`
- ネスト側 fingerprint: `da2c3bd9ab2351e35bb28b53690c94d8af2c3da3efae72a49316f38e6a3c3f4f`

## 1. 統合判定

| 判定対象 | 結論 | 根拠 |
|---|---|---|
| 今回の局所実装 | **PASS (repo-local)** | Android Java 21 で unit 358/358、assemble、lint が成功。差分は1ソースファイルの状態保存だけ |
| 現行ソースの未解決 Confirmed P0/P1 | **0件** | 主任の二巡反証で、独立監査のP0/P1候補は誤検知・証拠不足・仕様候補へ再分類 |
| 完全監査の証拠充足 | **PARTIAL / INSUFFICIENT EVIDENCE** | 現行差分の実機回転、実サービス、Store、署名、公開revision、復旧訓練、負荷・アクセシビリティ実測が未実施 |
| 本番リリース | **NO_GO** | ローカル成功は本番同一性・外部サービス状態・実機操作を証明しない |
| REVIEW_STATUS | **INCOMPLETE_REVIEW** | 主要なE4実走証拠が監査スコープ上取得不能。監査を成功扱いにはしない |

今回の修正は完成しているが、プロジェクト全体の本番GOは証拠不足である。この二つを混同しない。

## 2. 実施した変更

対象: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`

共有受信画面で、構成変更により失われるとユーザー操作が巻き戻る次の状態を `remember` から `rememberSaveable` へ変更した。

- 選択済みタグID (`selectedTagIds`)
- 新規タグ名入力 (`newTagName`)
- タグ作成エラー (`tagCreateError`)
- 保存結果メッセージ (`resultMessage`)

`isSaving` は意図的に `remember` のままにした。`rememberCoroutineScope` で開始した処理は、そのCompositionが破棄される構成変更時にキャンセルされる。ここで `isSaving=true` だけを復元すると、処理主体が消えた後も操作不能状態を復元し続ける可能性がある。新しいCompositionでは一時処理状態を `false` へ戻し、Repositoryの `normalizedUrl` 一意性・冪等結果と組み合わせて安全に再試行可能にする方が一貫する。

Ox は当初 `isSaving` も保存対象にしたが、主任反証で上記のライフサイクル不整合を発見し、最終差分では戻した。Ox のraw成果物は履歴証拠として訂正せず保存した。

## 3. モデル割当と実行証拠

### Ox Alpha Free

- 表示名: Ox Alpha Free
- Model ID: `opencode/x-preview-f-free`
- 公式session ID: `ses_fcc2cc5a0ffesmcrEjHuF4t3XT`
- セッション名: `Rinbam complete audit Ox Alpha implementation 2026-08-24`
- 実行時刻: 2026-08-24 21:51:16–22:21:41 JST
- 役割: inventory、baseline、局所実装、Java 21検証、raw監査成果物作成
- 成果物: `artifacts/project-audit/2026-08-24/ox-alpha/` の6ファイル
- 制限: raw報告は `isSaving` 保存を安全と評価しており、最終主任判断とは異なる

### Nemotron 3 Ultra Free

- 表示名: Nemotron 3 Ultra Free
- Model ID: `opencode/nemotron-3-ultra-free`
- 公式session ID: `ses_fcc0b54dbffe4GNnyNeBHFEMa0`
- セッション名: `Rinbam independent audit Nemotron 3 Ultra Free 2026-08-24`
- 実行時刻: 2026-08-24 22:27:47–23:00:39 JST
- 役割: REVIEW_ONLY独立反証、レッドチーム、二巡自己監査
- 原文part 1: `artifacts/project-audit/2026-08-24/nemotron/raw-report-part-1.md`
- 原文part 2: `artifacts/project-audit/2026-08-24/nemotron/raw-report-part-2.md`
- 独立性: Oxの所見・結論・本文は共有していない。広い `find` によりOx成果物のファイル名だけは見えており、結論本文は読んでいない。このため「内容上の独立」は維持したが「存在の完全盲検」ではない
- 完成性: part 1は出力上限で ISSUE-006 の途中に切れた。同じモデル・同じsessionを再開し、part 2で候補、第二巡、判定を完了した
- 品質制限: 現行コードと反する重大誤検知と内部件数矛盾があるため、主任による採否判定なしには使用不可

## 4. 独立監査候補の主任反証

| Nemotron候補 | 主任判定 | 現行証拠 |
|---|---|---|
| ISSUE-001 `isSaving` 非保存をP0 | **MODEL_FINDING_REJECTED** | 構成変更で処理coroutine自体がcancelされる。Booleanだけsaveableにすると永久disabled化し得る。現行の一時状態非保存が妥当 |
| ISSUE-002 applicationIdが `jp.mimac.urlsaver` でP0 | **FALSE POSITIVE** | `app/build.gradle.kts:115` は `applicationId = "jp.miyamibu.urlalbum"`。`namespace` と `applicationId` を混同 |
| ISSUE-003 AAL2未実装でP0 | **FALSE POSITIVE** | `web/admin/lib/auth.ts:184-210` がAAL2/TOTP、弱いACR、期限をfail-closed。高リスクrouteは例として `users/[id]/route.ts:99-102` で強制 |
| ISSUE-004 migration testがv1→2だけでP0 | **RELEASE EVIDENCE GAP** | 2→3、3→4、6→7、7→8、8→9、12→13、18/19→20、20→21、21→22の直接テストあり。ただし全起点・実データ更新の現行実走は未証明 |
| ISSUE-005 iOS fallback URL平文でP1 | **CANDIDATE P2 / E1-E3混在** | query itemにURLを含むコードはE3。OSログ・クラッシュ報告へ実際に記録されるという主張は今回未実走。App Group不通時の可用性とプライバシーの設計判断が必要 |
| ISSUE-006 タグ割当が非原子的でP1 | **SPEC CANDIDATE P2** | 部分成功はコードで検出しユーザーへ「一部のタグ付けに失敗」と表示。all-or-nothing要件がなく、共有タグごとのlimitを考えると欠陥確定不可 |
| ISSUE-008 iOS全件保持でP1 | **PERFORMANCE CANDIDATE P2** | 全件配列は確認できるが、1万件でのOOM/時間超過は未測定。負荷試験の対象 |
| ISSUE-012 export N+1/OOMでP1 | **PERFORMANCE CANDIDATE P2** | `loadAllEntries` は確認。Nemotronが示した `getTagsForEntry` の直接根拠は現行検索で確認できず、実測もない |
| ISSUE-014 Android購入検証なしでP1 | **FALSE POSITIVE** | `verify-store-purchase`、reconciliation、notification receiverにpurchase token/transaction binding・hash・store照会経路あり。候補自身が対象を未読と明記 |

独立監査原文は証拠として保持するが、上表の主任判定を最終採否とする。

## 5. 多視点レビュー統合

| 視点 | 確認結果 | 敵対・失敗側の重点 |
|---|---|---|
| プロダクト責任者 | URL保存・再発見という価値とPhase契約は整合 | KPI、利用継続、費用対効果は外部実績なし |
| 初見ユーザー | 共有→タグ→保存結果の主フローは明確 | 回転で入力・選択が消える問題を今回修正 |
| IT不慣れユーザー | 日本語フィードバックと部分失敗通知あり | 保存中中断、重複、エラー後の復旧を実機で未確認 |
| 熟練・業務利用者 | 複数URL、タグ、exportを提供 | 大量件数・キーボード・高速反復の実測なし |
| UXデザイナー | ユーザー入力状態を構成変更から保護 | `isSaving`を永続化して処理とUIを分離する案を棄却 |
| UIデザイナー | 今回は寸法・色・タイポ・構造変更なし | 回転、Dynamic Type、キーボード、Safe Areaの画面証拠なし |
| アクセシビリティ | 静的契約・contrast検査は合格 | TalkBack/VoiceOver、focus順、最大文字サイズは未実走 |
| コンテンツ | 保存結果・部分タグ失敗の文言あり | 原因別回復導線の理解度調査なし |
| 国際化 | URL/Unicodeテスト資産を確認 | RTL、複合文字、長文翻訳の実UIなし |
| 管理者 | role/capability/AAL2/operation ID/audit経路あり | 実環境MFA、break-glass、監査ログ改ざん耐性は未実証 |
| サポート | 問い合わせ・監査情報の実装資産あり | 本番問い合わせから復旧までの演習なし |
| QA | Android 358、Web 19、Deno 44が合格 | current-source E2E/visual/physical deviceなし |
| ソフトウェア実装 | 最終差分は局所的で責務境界を変更しない | `Set<Long>` Bundle復元のcurrent-device実証なし |
| アーキテクト/API/DB | normalizedUrl不変条件、Room/Supabase資産を確認 | live RLS/RPC、全migration path、rollbackを未実走 |
| 性能・コスト | ビルド・ローカルテスト範囲で退行なし | 大量データ、p95/p99、cold start、従量課金の測定なし |
| SRE/DevOps | release manifestとtracker整合 | 監視、alert、PITR、RTO/RPO、restore drill未実証 |
| プライバシー | 秘密値を開かず監査 | iOS fallbackのURL引継ぎは設計再評価候補 |
| レッドチーム | Intent/deep link/admin/RLS/store候補を反証 | 公開host bindingと本番権限はローカル静的証拠だけでは断定不可 |
| 悪意・雑・焦りユーザー | 連打・重複はDB一意性と状態表示で緩和 | 回転・中断・部分失敗のcurrent-device証拠なし |
| 重箱の隅 | namespace/applicationId、P0/P1、Eレベルを分離 | モデル報告の誤読・件数矛盾・未読断定を検出 |

## 6. 実行した検証

### 最終成功

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain`
  - `BUILD SUCCESSFUL in 1m 44s`
  - JUnit XML: **358 tests / 0 failures / 0 errors / 0 skipped**
- `cd web/admin && npm test && npm run typecheck && npm run lint && npm run build`
  - **19 tests PASS**、typecheck/lint/build PASS
- `bash scripts/run_deno_tests.sh`
  - **44 tests PASS / 0 FAIL** (6 files)
- `xcodebuild ... -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO ... build`
  - **BUILD SUCCEEDED** (unsigned generic iOS device build)
- `xcodebuild ... -configuration Debug -destination 'generic/platform=iOS Simulator' ... build-for-testing`
  - exit 0
- `python3 scripts/verify_mobile_ui_contract.py`
  - PASS (変更前・変更後)
- `python3 scripts/verify_mcp_contract.py`
  - PASS
- `python3 scripts/verify_canonical_story_tracker.py`
  - PASS
- `python3 scripts/verify_ui_contrast.py`
  - PASS (報告された全比率が基準以上)
- `python3 scripts/verify_release_manifest.py`
  - PASS
- `git diff --check`
  - PASS

### 失敗・再分類した実行

- 既定Java 17の `testDebugUnitTest`: 177件中19件失敗。Android SDK 36のRobolectricがJava 21を要求した環境不一致。Java 21再実行で358/358成功したため製品回帰ではない
- Nemotron初回Gradle: stale KSP生成物の重複で失敗。`./gradlew clean assembleDebug --no-daemon` により再生成後成功。`clean` は再生成可能なbuild成果物だけを対象とした
- `python3 scripts/verify_canonical_tracker.py`: スクリプト名を推測して実行し、file not found。正しい `verify_canonical_story_tracker.py` は成功。製品失敗ではない

## 7. 7巡自己監査と収束

1. **契約・scope巡**: 添付2,546行、AGENTS、DESIGN、mobile UI contract、関連Skillを確認。禁止操作を固定
2. **snapshot・inventory巡**: branch/HEAD/dirty/untracked/nested repo/fingerprintを固定。既存未追跡成果物を所有外として保護
3. **実装巡**: Ox差分を行単位レビューし、`isSaving`のライフサイクル不整合を主任修正
4. **実走巡**: Android/Web/Deno/iOS/contract/manifest/contrastを実行し、環境失敗と製品失敗を分離
5. **セキュリティ・レッドチーム巡**: deep link、Intent、AAL2、RLS/RPC、billing、migration、iOS handoffを確認
6. **独立反証巡**: Nemotron原文を回収し、全P0/P1を現行コードで反証。誤検知を採用しない
7. **最終差分・証拠巡**: Java 21を再実行し358/358、契約群、Git差分、不変HEADを再確認

主任巡6で新規Confirmed P0/P1は0件、主任巡7でも新規Confirmed P0/P1は0件だった。したがって **repo-local source reviewは2回連続で新規P0/P1なしとして収束**。ただしE4外部証拠が欠けるため、プロジェクト完全監査全体が収束したという意味ではない。

## 8. 未検証事項・本番GOゲート

`verify_canonical_story_tracker.py` が示した外部残ゲート:

- `supabase_auth`: 35 story
- `store_console`: 16 story
- `resend_live`: 4 story
- `public_web`: 8 story
- `design_required`: 2 story

tracker上の過去状態がPASSでも、今回差分のcurrent-source実機証拠へ自動昇格させない。今回未実施:

- 物理Androidでの共有・回転・タグ選択・保存・復旧
- 物理iPhoneでのAppium/WebDriverAgent操作
- Simulatorへのアプリinstall/launch/UI操作
- 現行差分のスクリーンショット・動画・visual regression
- 本番Supabase RLS/RPC/AAL2/監査ログ
- Play/App Store購入、取消、refund、restore、grant/revoke
- signing、upload、Store審査、公開revision同一性
- backup restore、PITR、rollback、RTO/RPO演習
- 大量データ、負荷、p95/p99、cold start、費用
- TalkBack/VoiceOver/Dynamic Type/RTL実機確認

## 9. Git・安全性

- commit、push、branch作成、deploy、release、Store操作、本番変更は未実施
- 物理デバイスには触れていない
- `.env` / `.env.local` の値は読んでいない。Web buildが環境ファイル名の存在だけを出力したが秘密値は表示していない
- 既存 `artifacts/ui-review/2026-08-11/` は変更していない
- 追跡済みソース差分は `ShareReceiverActivity.kt` のみ

## 10. Done when 判定

- 要求されたモデル割当・実走・独立監査・主任統合: 完了
- 局所実装修正とローカル検証: 完了
- 監査証拠の保存: 完了
- 本番安全性の証明: 未完了、外部証拠不足
- 最終結論: **実装 PASS / 完全監査 INCOMPLETE_REVIEW / RELEASE NO_GO**

