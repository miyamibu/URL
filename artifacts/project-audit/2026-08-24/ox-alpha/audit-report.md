# 監査報告書 — りんばむ プロジェクト完全監査 v3.0 適用

実施: Ox Alpha Free (実装・実走担当) / 2026-08-24
主任監査責任者への統合を前提とした成果物。監査基準は添付「プロジェクト完全監査プロンプト v3.0」全文。

---

## 1. 監査ステータス

**PARTIAL REVIEW — COMPLETE WITH STATED LIMITATIONS (repo-local scope)**

- リポジトリ内コード・テスト・ビルド・lint・契約スクリプトは証拠駆動で確認。
- 実機/Simulator操作、本番環境、外部サービス、Store提出物は許可範囲外のため未検証。
- 単一エージェントによる擬似独立レビューであり、真に独立した多専門家レビューとは表現しない(§6準拠)。

## 2. 緊急警告

P0/BLOCKER/CRITICAL は**検出されませんでした**(確認済み範囲内)。

## 3. エグゼクティブサマリー

- **目的**: 共有URLの保存と再発見(Android/iOS/共有タグ/AI-friendly export)。Phase1a/1b不変条件がコードレベルで維持されていることを主要経路で確認。
- **品質状態**: repo-local品質ゲート(test 358/0, lint, build, UI契約)は全てPASS。テスト失敗19件はJDK17環境由来で、リポジトリは `.java-version=21` + README L99/L191で文書化済み。
- **最大のリスク**: 本監査がカバーできない領域=実機UI動作、本番Supabase設定、webhook実配信、Store審査プロセス。これらはlaunch-go-checklistの外部ゲートとして管理されている。
- **最大の強み**: normalizedUrl重複主キー・openUrl=normalizedUrl等の中核不変条件がAndroid/iOS双方で機械的かつ一貫して守られている点。SSRF対策(NetworkUrlPolicy)、Keystore暗号化セッション、サーバー側admin認可+AAL2 step-up、厳格CSPなど防御設計が実装に落ちている。
- **公開判断**: §8参照(2層で判定)。
- **最優先の行動**: launch-go-checklistの外部ゲート(STAGING_GO→INTERNAL_TEST_GO→LAUNCH_GO)を証跡付きで進行すること。ENV-01対策としてJDK21での検証を実施者全員に徹底。

## 4. 対象と制約

- **対象バージョン**: branch `codex/修正` / HEAD `eda2f2678c16bfbbda56d398c613012376993e76` / 確認日時 2026-08-24 / Android versionCode 21 (1.0.17)
- **確認した成果物**: inventory.tsv 参照(18項目、確認状態付き)
- **利用した検証手段**: ファイル閲覧、コード検索(grep)、git履歴、単体テスト実行(E4)、lint/build実行(E4)、静的契約スクリプト(E4)
- **未確認事項**: validation-log.md の NOT EXECUTED/BLOCKED 表
- **仮定**: (a) ローカルgradle実行結果はCIと同等の意味を持つ (b) 未読migration/functionsも既存QA文書により過去検証済み (c) `.java-version` を正準とする運用が実行者に周知済み
- **制約**: サブエージェント起動不可、実機操作・本番操作・依存追加・commit/push禁止、コンテキスト上限あり

## 5. 成果物インベントリ

inventory.tsv を参照。

## 6. カバレッジ表

coverage.md を参照。

## 7. システム理解

- **ユーザー**: URLを保存/再発見する個人ユーザー、共有タグメンバー、管理者(web/admin)
- **主要フロー**: ACTION_SEND受信→タグ選択→保存(Room, normalizedUrl重複判定)/metadata取得(WorkManager)/アーカイブ・削除猶予+Undo/共有タグ同期(Supabase RLS+RPC)/課金 entitlement(verify-before-acknowledge)
- **重要データ**: url_entries(normalizedUrl UNIQUE), tags, shared_tag_*(RLS), entitlement/store通知(outbox冪等)
- **信頼境界**: OS共有Intent(未信頼入力→UrlRules抽出/256KB上限/50件上限)、Supabase(authenticated RLS)、store webhook(署名検義)、deep link(urlsaver://, miyamibu.xyz autoVerify)
- **重要な不変条件**: coverage.md 下部表の通り主要6件をE3で維持確認
- **失敗条件**: 正規化契約破壊、実機DB破壊(migration missing)、RLS越え、二重課金

## 8. リリース判定

| 層 | 判定 | 根拠 |
| --- | --- | --- |
| repo-local コード品質 | ✅ RELEASE相当 | test/lint/build/契約全PASS、確認済みBLOCKER/CRITICAL/HIGH無し、実装済み修正SR-01検証済み |
| 本番 go-live 総合 | ⛔ INSUFFICIENT EVIDENCE | 実機UI証跡・本番env/webhook・Store審査・instrumentationが本監査では検証不能(§8「重大領域の証拠が不足」適用) |

理由: 監査プロンプト§8原則に従い、「確認済み欠陥が無い」と「安全である」を混同しない。本番判断には launch-go-checklist の外部ゲート証跡が必要。

## 9. 最重要TOP10

指摘水増し禁止(§16)のため、実質ある指摘のみ記載(5件)。10件へのパディングは意図的に拒否した。

| 順位 | ID | 問題 | 影響 | なぜ今 | 修正案 | 工数 | 優先度 | 所有者 | 依存 |
| -- | -- | ---- | ---- | ------ | ------ | ---- | ------ | ------ | ---- |
| 1 | ENV-01 | JDK17でRobolectric 19クラス失敗 | 検証不能→誤った失敗報告の恐れ | release gateの第一歩のため | README/.java-version遵守を周知(文書済み)。CI実行者へJDK21強制 | XS | P1(運用) | Release owner | 無し |
| 2 | SR-01 | ShareReceiver回転で状態消失 | 入力中のタグ選択喪失 | 修正済み(P2) | rememberSaveable導入(**実装・検証済み**) | XS | P2 | Frontend | 無し |
| 3 | GATE-01 | URL正規化契約スクリプト要local PG | 契約劣化検知が手動化 | 検証自動化のため | supabase local起動手順のrunbook化 | S | P2 | Backend | local stack |
| 4 | GATE-02 | iOS build未検証(本監査) | Swift側回帰の検知なし | ios変更時必須 | xcodebuild契約コマンド実行(次回ios変更時) | S | P2 | iOS owner | 無し |
| 5 | NIT-A | mimeType無しSEND filterで非テキスト共有がエラー画面表示 | 限定的UX | 公開後でも可 | filter絞り込み or 受け口文言調整 | S | NIT | Android | 要仕様確認 |

## 10. P0/P1一覧

- P0: 無し(確認済み範囲)
- P1: ENV-01(運用ゲート/文書対応済み・環境遵守が必要)

## 11. 全指摘一覧

| ID | 分類 | 重大度 | 優先度 | 証拠 | 状態 |
| -- | ---- | ------ | ------ | ---- | ---- |
| ENV-01 | OPERABILITY ISSUE | MEDIUM(検証阻害) | P1 | E4(V02/V03/V04で再現・分離) | 対応方針確定(文書済み/環境遵守) |
| SR-01 | DEFECT | LOW〜MEDIUM | P2 | E3(コード)+E4(修正後検証) | FIXED(未コミット差分) |
| GATE-01 | OPERABILITY ISSUE | MEDIUM | P2 | E4(psql接続失敗) | 残存ゲート |
| GATE-02 | OPERABILITY ISSUE | MEDIUM | P2 | NOT EXECUTED明示 | 残存ゲート |
| GATE-03 | REQUIREMENT GAP(検証範囲) | MEDIUM | P2 | 範囲外明示 | 残存ゲート |
| NIT-A | CONTENT ISSUE | NIT | NIT | E3(Manifest L56-63) | 記録のみ |
| NIT-B | IMPROVEMENT | NIT | NIT | E3(NetworkUrlPolicy DNS解決後のTOCTOU) | 記録のみ(クライアント用途では影響小) |

### SR-01 完全形式(代表例)

- **場所**: app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt L251-255(修正前L250-254)
- **状態**: Confirmed (E3) → 修正後 E4でtest/build/lint/契約PASS
- **観測事実**: plain `remember` による状態保持で、Activity再生成(回転)時に selectedTagIds/newTagName/tagCreateError/isSaving/resultMessage が初期化される
- **期待**: Compose saved-instance-state契約では入力途中状態が保持されるべき
- **影響**: 共有保存フロー中の回転でユーザー入力が失われる(データ破壊ではなく再入力コスト)
- **根本原因**: 状態保持手段の指定漏れ(rememberSaveableとの取り違え)
- **反証条件**: 回転しても選択が残る仕様が意図なら誤検知(その場合も保存フローの連打耐性は重複判定により保証済み)
- **理想的/最小修正案**: 同一(rememberSaveable置換)
- **受け入れ条件**: test/lint/build/契約PASS+回転保持(実機確認は次回実機検証時に含める)
- **副作用**: 特に無し(Bundle互換型のみ)
- **ロールアウト**: 通常ビルドに含めて即時。ロールバックはhunk revert

## 12. 視点間の対立と最終判断

| 論点 | 賛成 | 反対 | トレードオフ | 最終判断 |
| ---- | ---- | ---- | ------------ | -------- |
| SR-01を今直すか | QA: 入力喪失は欠陥 | 熟練: 回転は稀・重複保存は既に安全 | 変更リスクXS vs 体験改善 | 実装(最小・契約非関与) |
| jvmToolchain固定 | SRE: 環境差異の排除 | 制約: 依存追加不可・検出失敗でbuild全体が壊れる | 自動化 vs 可搬性 | 差し戻し(文書遵守運用) |
| TOP10の水増し | 形式充足 | §16の水増し禁止 | - | 5件+正直な拒否記載 |

## 13. 根本原因

- 環境前提(JDK21)が文書化されていてもランタイム強制がない → ENV-01のような「文書と実行者の間の落差」
- Activity状態保持の標準パターン適用漏れ → SR-01
- 外部インフラ依存の検証(local PG)がrunbook化しきっていない → GATE-01

## 14. Quick Wins

1. **JDK21徹底**: 作業内容=実行者のJAVA_HOME/.java-version遵守 / 工数=XS / 効果=test gate復活 / リスク=無し / 検証=V04,V07
2. **supabase local起動の手順書1枚化**: XS / 契約スクリプト自動化 / 低リスク / runbookレビュー
3. **NIT-A filter絞り込み検討**: S / エラー画面減 / 仕様確認要 / 手動共有テスト

## 15. 修正ロードマップ

- **今すぐ**: ENV-01周知(完了扱い: 文書済), SR-01差分のコミット判断(ユーザー承認待ち)
- **リリース前**: GATE-02(ios変更時xcodebuild), launch-go-checklist外部ゲート進行
- **リリース直後**: NIT-A
- **中期**: instrumentation/E2EのAVD常設化, Observability整備
- **長期**: i18n基盤

## 16. 検証・回帰テスト計画

| 対象 | テスト | 環境 | 合格条件 | 実施 |
| ---- | ------ | ---- | -------- | ---- |
| SR-01 | testDebugUnitTest + 契約script | JDK21 ローカル | 358/0 PASS + contract PASS | 済(V07-V09) |
| SR-01 runtime回帰 | 回転操作(ShareReceiverでタグ選択→回転→選択維持) | Emulator/実機 | 選択保持 | 未実施(次回デバイス検証時に組込) |
| ENV-01 | `./gradlew --version` JVM確認 | 全実行者 | JVM 21系表示 | 運用で周知 |

## 17. 重大事故シナリオ(上位10件のうち検討済み要点)

1. **migration missing クラッシュ**: 古いapk→新schema実機で起動時クラッシュ → 防御: forward migration連鎖(v1→22)とfallbackToDestructive不使用を確認済み。検知: Crashlytics等は未導入(NOT EXECUTED領域)
2. **JDK不一致による偽陰性テスト報告**: ENV-01の通り発生済み → V03で分離、文書で防御
3. **webhook偽造課金**: store-notification-receiverはApple署名検証/JWKS検証を必須化(E3) → 未署名は401系
4. **RLS越え閲覧**: shared_tag_*はactive member限定SELECT(E3)、書込はRPC硬化migration経由 → SQL実行検証はGATE-01
5. **SSRF的metadata取得**: NetworkUrlPolicyがprivate/link-local/CGNAT/metadata端点を遮断(E3) → 残留: 解決後接続のTOCTOU(NIT-B)
6. **二重保存競合**: DB UNIQUE(normalizedUrl)+トランザクション+E4テスト → 競合時はDUPLICATE_*に収束
7. **削除猶予中の誤復元漏れ**: restore/pendingDelete状態機械をテスト網羅(RepositoryBehaviorTest群)
8. **admin権限昇格**: bootstrap_first_adminはfirst-admin限定migration+status active確認+AAL2 step-up(E3)
9. **CSP回避によるXSS**: invite-linkはhash pinning CSP(E3) → admin側はNext.js既定+auth server-side
10. **共有Intent巨大入力**: 256KB上限+50件truncate+degradation通知(E3) → OOM/ANR耐性は静的確認まで

## 18. 削除できるもの

- 明確な削除候補は今回検出せず(退役Collection/UserLabelは既にDB互換殻として管理済み = 削除ではなく保持が正解)。無理な削除提案は行わない。

## 19. 維持すべき強みと不変条件

coverage.md の不変条件表を参照。加えて:
- UrlRules の抽出/上限/degradation設計(テスト豊富)
- SharedTagSyncAuth のKeystore移行パターン(平文legacyからの自動昇格+除去)
- admin auth.ts の多層検証(issuer/aud/sub/email突合/capability/step-up)

## 20. 分野別評価

| 分野 | 点数(5刻み) | 根拠 | 確信度 | 未確認 |
| ---- | ---- | ---- | ------ | ------ |
| Product | 85 | 不変条件明確・scope統制 | 高 | ユーザー調査 |
| Requirements | 80 | spec docs充実 | 中 | 受け入れ条件形式 |
| UI (Android) | 75 | 契約script+精読 | 中 | 実機操作 |
| UI (iOS) | N/A | 未実行 | - | xcodebuild |
| UX | 75 | 状態表現設計良 | 中 | 動的確認 |
| Accessibility | N/A | 検査未実施 | - | 全般 |
| Content | 80 | 文言丁寧・誤解少 | 中 | 全画面 |
| i18n | 70 | 日本語特化は意図的 | 中 | 英語展開 |
| Code Quality | 85 | 責務分離・命名・テスト | 高 | 一部ファイル |
| Architecture | 85 | domain/data/ui分離徹底 | 高 | - |
| API | 75 | RLS/RPC硬化 | 中 | SQL実行 |
| Data | 80 | migration連鎖健全 | 中 | 本文全行 |
| Security | 75 | 多層防御確認 | 中 | 動的検査 |
| Privacy | 80 | backup無効/暗号化 | 中 | Data Safety突合 |
| Performance | N/A | 計測無し | - | - |
| Reliability | 75 | retry/outbox/undo | 中 | 障害注入 |
| Testing | 90 | 358tests+契約script | 高 | instrumentation |
| Maintainability | 85 | docs/Skill整備 | 高 | - |
| Scalability | N/A | 規模情報不足 | - | - |
| Operations | 70 | runbook多数・未 rehearsal | 中 | 障害訓練 |
| Observability | 60 | console中心・metrics弱 | 中 | 本番計測 |
| Admin | 80 | 機能・監査log設計 | 中 | 実運用 |
| Support | 75 | contact-support outbox | 中 | 実flow |
| Cost | N/A | 上限設計未確認 | - | rate limit実効 |

総合平均は算出しない(重み付け根拠が不足するため、§20の指示どおり)。

## 21. 自己監査結果

self-audit.md を参照(7ループ、収束状況)。

## 22. 残存リスク

- 実機/SimulatorでのUI動作・アクセシビリティは本監査で未検証。「問題が見つからなかった」≠「問題が存在しない」。
- 未読のmigration/functions本体(66件中大部分)に個別欠陥がある可能性。既存QA文書とCIを信用した範囲限定。
- 本番シークレット運用・rate limit実効性・Store審査は外部権限が必要なため恒常的に範囲外。
- SR-01修正はruntime回転試験を未実施(E3ベース+静的検証)。次回デバイス検証で回転ケースを含めること。
