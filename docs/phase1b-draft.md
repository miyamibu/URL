# Phase 1b Draft: 保存後の状態理解と再発見性の改善

Status: Implemented (P0 Verified)  
Updated: 2026-04-11  
Scope type: Phase 1b 実装仕様（Phase 1a 不変条件を維持）

## 1. 目的
Phase 1b は「保存した URL が今どの状態かを理解しやすくする」「後で見つけ直しやすくする」ことに限定する。  
Phase 1a の保存思想（`normalizedUrl` 主軸、`openUrl = normalizedUrl`）は維持し、UI と文言を中心に改善する。

## 1.1 対象ユーザー
SNS やメッセージで見かけた URL をあとで見返したいユーザーを主対象とする。

## 1.2 コア価値
共有や手動追加で URL を確実に保存し、保存後の状態を Main / Archive / Detail で誤解なく辿れることを価値とする。

## 1.3 リリースゲート（二値）
- `./gradlew assembleDebug` が成功する
- `./gradlew testDebugUnitTest` が成功する
- `./gradlew lintDebug` が成功する
- `docs/phase1b-ux-checklist.md` の P0 が実確認状況付きで記録されている
- fresh install の基本導線（起動 -> 保存 -> 詳細遷移）に silent failure がない
- Crash reporting は本リリースでは未導入運用（OS ログ + 再現手順）で公開可とする

## 2. 参照した現状（repo reality）
- `AGENTS.md`
- `README.md`
- `docs/phase1a-spec.md`
- `app/src/main/java/jp/mimac/urlsaver/ui/MainActivityViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/components/ServiceFilterRow.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/UrlEntryDao.kt`
- `app/src/main/java/jp/mimac/urlsaver/domain/UrlRules.kt`
- `app/src/test/java/jp/mimac/urlsaver/MainActivityViewModelTest.kt`
- `app/src/androidTest/java/jp/mimac/urlsaver/Phase1aFlowTest.kt`

## 3. Phase 1a から維持する不変条件
- 主対象は URL。重複判定主キーは `normalizedUrl`。
- `openUrl = normalizedUrl` を維持する。
- 一覧カードタップは詳細遷移のみ。直接外部起動しない。
- `duplicate archived` は Archive へ移動する導線を使う。
- 手動追加の `INVALID_URL` / `NO_URL_FOUND` はボトムシート内エラー表示で扱う（Snackbar で扱わない）。
- 並び順大変更、検索、タグ、canonical 強化は 1b の主対象にしない。

## 4. 対象（Phase 1b）

### 4.1 保存結果の理解を明確化（P0）
- `DUPLICATE_ARCHIVED` の標準文言を明確化する。
  - 文言: `このURLはアーカイブ済みです`
  - action: `見る`（ラベル固定）
  - action 挙動: 既存の archived 項目を開いて確認する導線
    - entry が特定できる場合は当該詳細を開く
    - entry を特定できない場合は Archive 画面へ遷移
  - Archive 画面上では action を出さない
- `復元して見る` は 1b 主導線にしない。
  - 自動復元や復元を伴う action は標準導線にしない。
- `DUPLICATE_ACTIVE` でも、既存項目を確認できる導線を出す。
  - 文言: `このURLはすでに保存済みです`
  - action: `見る`
  - action 挙動: 既存 active 項目を確認できる場所へ遷移

### 4.2 metadata の内部状態と UI 文言の分離（P0）
- `MetadataState` / `MetadataError` は内部実装用の状態として維持する。
- ユーザー向け文言では、内部 enum 名をそのまま主表示しない。
- 一覧カードの表示文言は以下の方針で統一する（短文優先）。
  - `PENDING` -> `取得中`
  - `FAILED` -> `更新できませんでした`
  - `UNAVAILABLE` -> `更新できませんでした`
  - `READY` -> 状態行なし
- 詳細画面は「ユーザー向け説明」と「詳細情報」を分ける。
  - 上位表示: 意味の分かる説明文
  - 折りたたみ詳細: 技術情報（必要なら enum 値）を確認できる領域
- `PARSE_FAILED` は引き続き `UNAVAILABLE` 系として扱う（1a 契約維持）。
- 失敗状態では詳細画面から再取得できる。
  - ボタンラベル: `再取得`
  - 再取得中: disabled + `再取得中…` 表示（必要に応じて小型進捗表示を併置）

#### metadata 文言対応表（1b）
| 内部状態 | 一覧主文言 | 詳細主文言 | 補助表示 |
|---|---|---|---|
| `PENDING` | `取得中` | `情報を更新中です` | なし |
| `FAILED` | `更新できませんでした` | `情報を更新できませんでした` | 原因を短文補足可 |
| `UNAVAILABLE` | `更新できませんでした` | `情報を更新できませんでした` | 対象外/形式不一致を短文補足可 |
| `READY` | 表示なし | 表示なし | なし |

#### metadataError の表示方針（詳細折りたたみ）
- `metadataError` は削除しない。
- 表示は `表示名 + raw code` を許容する（例: `ページ形式を解析できませんでした (PARSE_FAILED)`）。
- raw code を主見出しには使わない。

### 4.3 手動追加 UX 改善（P0）
- 手動追加は Phase 1b の対象に含める。
- 1a の入力ルールを維持したまま、理解しやすさを上げる。
- 改善の優先順:
  1. エラー文言改善
  2. duplicate 時導線改善
  3. 入力補助
- 入力補助の最小定義:
  - `KeyboardType.Uri` を使う
  - クリップボード貼り付け導線を維持または改善する
  - `https://` 自動挿入はしない
  - trim と既存 validation を維持する
- エラー説明は「次に何を直せばよいか」で示す。
- `INVALID_URL` と `NO_URL_FOUND` の違いを UI 文言で区別する。
- `duplicate archived` が手動追加で発生した場合も、4.1 の文言・導線に統一する。

### 4.4 一覧の再発見性改善（軽量）（P1）
- 既存の一覧構造（カード/フィルタ/画面遷移）を大きく変えずに改善する。
- 変更候補は軽量に限定する。
  - 状態文言の明確化（4.2）
  - 空状態文言の明確化（保存後の次アクションを示す）
  - Archive 導線の認知向上（重複時 `見る`）
- 非対象:
  - ソート仕様の変更（`createdAt DESC` / `archivedAt DESC` は維持）
  - 新しい情報軸（タグ・検索・高度フィルタ）

## 5. 非対象（Phase 1b でやらない）
- 並び順ロジックの大変更
- 検索機能追加
- タグ機能追加
- canonical による同一視ロジック強化
- `復元して見る` を主導線にする仕様
- WorkManager の再試行戦略変更（Phase 1a 契約外）

## 6. 優先順位

| Priority | 内容 | 理由 |
|---|---|---|
| P0 | `duplicate archived` 文言/導線の明確化 | 保存結果理解の中心課題 |
| P0 | metadata 文言の内部状態分離 | ユーザー理解改善に直結 |
| P0 | 手動追加 UX 改善 | 主要入力導線の品質向上 |
| P1 | 一覧の軽量な再発見性改善 | 使い勝手向上（非破壊） |
| P2 | それ以外の改善候補 | 1b 完了後に再評価 |

## 7. 実装反映（2026-04-11）
- `DUPLICATE_ACTIVE` / `DUPLICATE_ARCHIVED` は `見る` 導線で統一した。
  - 文言はそれぞれ `このURLはすでに保存済みです` / `このURLはアーカイブ済みです`。
  - `見る` は既存項目確認導線として扱い、`復元して見る` は主導線にしていない。
  - entryId が取れる場合は詳細へ、取れない場合は対象 destination へ遷移する。
- metadata 文言は内部状態語から分離した。
  - 一覧は `取得中` / `更新できませんでした` の短文に統一。
  - 詳細は `情報を更新中です` / `情報を更新できませんでした` を主文言にした。
  - 詳細の失敗状態で `再取得` を提供し、再取得中は `再取得中…` + disabled で表現する。
- 折りたたみ詳細では `canonicalId` / `metadataError` を維持した。
  - `metadataError` は「表示名 + raw code」を確認できる表示にした。
- 手動追加 UX は 1b 優先順で改善した。
  - in-sheet エラー方針を維持したまま、エラー文言を具体化。
  - `KeyboardType.Uri` とクリップボード貼り付け導線を明示。
  - `https://` の自動補完は追加していない。
- 共有取り込みの劣化挙動を明示化した。
  - 1つの共有テキストに複数 URL が含まれる場合は 1件目のみ保存し、通知を表示する。
  - `ACTION_SEND_MULTIPLE` は複数 URL を順次処理し、件数集計を通知する。
- 一覧は軽量改善に留めた。
  - フィルターや遷移構造は維持し、補助的な件数表示を追加。
  - 検索 UI / ロジックは scope 整合のため削除し、仕様どおり非対象に戻した。
  - 並び順大変更 / タグ / canonical 強化は未着手のまま維持。

## 8. 完了条件（1b 実装）
- 対象・非対象が文書で明確化されている。
- `duplicate archived` の基本導線が「このURLはアーカイブ済みです + 見る」に固定されている。
- `復元して見る` が主導線でないことが明記されている。
- metadata の内部状態語と UI 文言の分離方針が定義されている。
- 手動追加 UX の改善範囲が定義されている。
- 一覧改善が軽量に制約されている。
- Phase 1a 契約（URL 主軸・遷移・保存ルール）と矛盾しない。

## 9. 保留事項（1b 後の検討）
- 検索（タイトル/URL 部分一致）
- タグ・分類
- canonical 起点の重複統合強化
- 並び順切替（作成日時/更新日時/手動固定）

## 11. リリース判断（確定）
- Crash reporting（Firebase Crashlytics / Sentry 等）は本リリースでは導入しない。
- 障害対応は OS ログ + 手動再現手順で運用し、将来リリースで再評価する。
- `ACTION_SEND_MULTIPLE` は対応済み（重複除外の順次保存 + 集計通知）。

## 12. 検証結果（2026-04-11）
- `./gradlew clean assembleDebug`: 成功
- `./gradlew testDebugUnitTest`: 成功
- `./gradlew lintDebug`: 成功
- `./gradlew connectedDebugAndroidTest`: 成功（`urlsaverApi35` AVD）
- `docs/phase1b-ux-checklist.md`: P0 全項目を `OK` 記録
- 検証時に発見した `BuildConfig` 未生成・WorkManager 初期化/Manifest lint 指摘は修正済み

## 10. 実装時のガードレール（1b）
- 既存責務分離を維持する（Compose に業務ロジックを寄せない）。
- `MainActivityViewModel` の `consumeShareResult(intent, currentRoute)` の設計を維持する。
- `DUPLICATE_ARCHIVED` 判定で `currentRoute == archive` の action 抑制を維持する。
- metadata 取得ロジック（分類・再試行・Work key）は 1b では変更しない。
- `canonicalId` と `metadataError` を折りたたみ詳細から削除しない。
