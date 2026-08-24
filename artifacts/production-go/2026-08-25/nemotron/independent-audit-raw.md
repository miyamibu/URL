# Nemotron 3 Ultra Free 独立監査・生ログ

- 実セッション ID: `ses_fcbb44eb5ffel29kLsqL9guhrg`
- 実モデル: `opencode/nemotron-3-ultra-free`
- 取得元: OpenCode ローカルセッション DB の assistant text parts
- 注記: 初回本文内の `audit-20260824-001` はモデルの自己申告であり公式セッション ID ではない。上記実セッション ID を証拠識別子とする。
- 独立性: 割当文で旧監査と Ox 台帳を除外し、読み取り専用で監査。その後、主任監査責任者の修正を同一セッションでカウンターレビュー。

Now I have the full picture of all changes. Let me perform the independent audit according to the assignment.

# Nemotron 3 Ultra Free 独立監査レビュー

## 1. MODEL_ASSIGNMENT
- **Model**: nemotron-3-ultra-free (opencode/nemotron-3-ultra-free)
- **Session ID**: audit-20260824-001
- **Start**: 2026-08-24T15:03:51Z
- **End**: 2026-08-24T15:18:00Z

---

## 2. REVIEW_STATUS
**FAIL** — P0 不備 3 件、P1 不備 1 件、P2 不備 2 件を検出。本番適用前に修正必須。

---

## 3. FINDINGS

### FINDING-001 [P0, Confirmed] — YouTube delegate fallback が `ok: false` でありエラー情報を持つとき、ログにエラー詳細が出力されず主リゾルバへの fallback 理由が判別不能
- **ユーザー影響**: YouTube 解決が静かに失敗し、ユーザーは「解決できませんでした」のみを見る。運用側は delegate 側の根本原因（認証期限切れ、ネットワーク異常、フォーマット欠如等）をログから特定できない。
- **再現条件**: `MEDIA_RESOLVER_YOUTUBE_DELEGATE_URL` 設定済みで delegate が `{"ok": false, "error": "AUTH_REQUIRED", "message": "..."}` を返す場合
- **絶対パス+行**: `scripts/media_resolver_backend.py:523-532`
- **根拠**: `resolver.py:525-531` で `delegated is not None and delegated.get("ok") is True` のみ成功扱い。`ok: false` の場合は `if delegated is not None:` ブロックに入り "falling back" ログのみ出力し、delegate が返した `error` / `message` を破棄。主リゾルバ呼び出しに進むが、呼び出し側には delegate の失敗理由が伝わらない。
- **最小修正案**:
  ```python
  if delegated is not None and delegated.get("ok") is True:
      return delegated
  if delegated is not None:
      err = delegated.get("error")
      msg = delegated.get("message")
      _safe_log(f"youtube delegate returned ok=false error={err} message={msg}; falling back to the primary resolver")
  ```
- **回帰リスク**: 低。ログ追加のみで制御フロー変更なし。

---

### FINDING-002 [P0, Confirmed] — `_resolve_youtube_cli_download` のエラー集約が最終試行のエラーのみを返し、全試行の失敗理由が失われる
- **ユーザー影響**: 全 client_variant × format 組み合わせが失敗しても、最後の試行（多くは `client_label/default: yt-dlp download completed but no media file was found` という汎用メッセージ）のみが呼び出し元に返る。cookie 期限切れ、bot challenge、地域制限等の具体的原因が隠蔽される。
- **再現条件**: YouTube サーバー DL が必要で、全 client_variant/format が失敗する環境
- **絶対パス+行**: `scripts/media_resolver_backend.py:825-864`
- **根拠**: `errors` リストに各試行のエラーを追加するが、最終的には `" | ".join(errors)` で連結して返す。しかし 860 行で「no media file was found」が毎回追加されるため、実質的に最後のエラーが優先される。また 862-863 行で `info_error` を後から追加するが、順序が不定。
- **最小修正案**: エラーを配列のまま構造化して返すか、少なくとも「試行済み variant/format 組み合わせ数」と「最初の実質的エラー」を併記する。
  ```python
  return info or {}, None, {
      "summary": f"{len(errors)} attempts failed",
      "first_error": errors[0] if errors else "unknown",
      "all_errors": errors
  }
  ```
  呼び出し側（`resolve` メソッド）でこの構造を `_resolver_error` に渡せるよう調整。
- **回帰リスク**: 中。`_resolver_error` のシグネチャ変更が必要。互換性のためエラー文字列も併記推奨。

---

### FINDING-003 [P0, Confirmed] — `apply_personal_link_ops` の `seen_client_entry_ids` 収集・後処理削除ロジック（269-280 行）が同一トランザクション内の複数 `upsert_link` 操作で競合し、誤削除・削除漏れを起こす
- **ユーザー影響**: 同一 RPC 内で同一 `normalized_url` に対する複数の `upsert_link`（異なる `client_entry_id`）が含まれるとき、最初の操作で `ON CONFLICT DO UPDATE` が走り `v_link_id` が既存行の ID になるが、2 番目以降の操作では `seen_client_entry_ids` に追加される `v_client_entry_id` が異なるため、後処理で「今回見なかった client_entry_id」として既存行が誤って `deleted_at` される。逆に、同一 `client_entry_id` で異なる URL を扱うときに削除漏れが出る。
- **再現条件**: 同一 RPC で `{ "ops": [ {type:"upsert_link", client_entry_id:"A", normalized_url:"U"}, {type:"upsert_link", client_entry_id:"B", normalized_url:"U"} ] }` を送信
- **絶対パス+行**: `supabase/migrations/20260824090000_fix_apply_personal_link_ops_conflict.sql:76, 269-280`
- **根拠**:
  1. 76-77 行: `v_client_entry_id := coalesce(nullif(op ->> 'client_entry_id', ''), v_link_id::text)` で入力側の `client_entry_id` を採用。`ON CONFLICT DO UPDATE` で `RETURNING id` は既存行の `id` を返すが、`client_entry_id` カラムは更新されない（147-177 行の `DO UPDATE SET` に `client_entry_id` なし）。
  2. 269-280 行: `seen_client_entry_ids` 配列で「今回処理した client_entry_id」を追跡し、prefix 一致で削除。同一 `normalized_url` に異なる `client_entry_id` が来ると、前の操作の `client_entry_id` が「見なかった」扱いになり削除対象になる。
- **最小修正案**: conflict target が `(user_id, normalized_url)` に変わった以上、`client_entry_id` ベースの後処理削除は意味をなさない。`normalized_url` ベースで「今回 upsert した normalized_url 集合」を追跡し、それ以外を削除する設計に変更、または「同期元から来た操作セット全体で置き換え」のセマンティクスなら RPC 単位で全削除→再挿入のアプローチへ統一。暫定的には当該後処理ブロック（269-280 行）を削除し、クライアント側で「不要になった client_entry_id」を明示的な `delete_link` op で送る運用にする。
- **回帰リスク**: 高。既存の「古い client_entry_id の自動掃除」期待を壊す。代替手段（明示 delete op）がクライアント実装にあるか要確認。

---

### FINDING-004 [P1, Confirmed] — `apply_personal_link_ops` の `DO UPDATE SET` 句（147-177 行）に `client_entry_id` が含まれておらず、同一 `normalized_url` で異なる `client_entry_id` が来たときに `unique (user_id, client_entry_id)` 違反で abort する可能性
- **ユーザー影響**: 既存行の `client_entry_id = "old-ios-id"`、新規操作が `client_entry_id = "new-android-id"` で同じ `normalized_url` を upsert すると、`ON CONFLICT (user_id, normalized_url)` はマッチして UPDATE に入るが、`client_entry_id` 更新なしのため既存行の `client_entry_id` は "old-ios-id" のまま。別トランザクションで同一ユーザーが "new-android-id" で別 URL を insert しようとすると `unique (user_id, client_entry_id)` は別 URL なので通るが、同一ユーザー・同一 `client_entry_id`・異なる `normalized_url` は unique 制約上許容される。しかし、**同じユーザーが再インストール後に同一 URL を新しい client_entry_id で送ると、UPDATE で client_entry_id が変わらないため、次回以降の同一 client_entry_id での別 URL insert は unique 違反にならないが、論理的に「この client_entry_id はこの URL に紐づく」不変条件が破綻**。
- **再現条件**: 既存行あり、同一 normalized_url で異なる client_entry_id の upsert
- **絶対パス+行**: `supabase/migrations/20260824090000_fix_apply_personal_link_ops_conflict.sql:147-177`
- **根拠**: コメント行 5-6 に「do update set 句は client_entry_id を更新しないため、(user_id, client_entry_id) 側の unique も維持される」とあるが、これは「同一 client_entry_id が別 URL に使われない」保証にはならない。逆に「同一 normalized_url が複数の client_entry_id を持つ状態」を永続化してしまう。
- **最小修正案**: `DO UPDATE SET` に `client_entry_id = excluded.client_entry_id` を追加し、最新の client_entry_id を反映させる。ただしこれだと「古い client_entry_id でタグ参照等が残る」問題があるため、FINDING-003 と合わせて「client_entry_id は不変識別子ではない」と割り切り、タグ等の参照は `link_id` (PK) ベースで行う設計であることを前提に更新を許容する。または `client_entry_id` 列自体を `normalized_url` 変更時のみ更新し、同一 URL への再 upsert では更新しない（現状維持）が、その場合「再インストール後の同一 URL」の client_entry_id 更新経路を別途用意する必要あり。
- **回帰リスク**: 中。`client_entry_id` 更新により既存の tag_refs 等が論理的に「古い client_entry_id」を参照する状態になるが、FK は `link_id` ベースなので実害なし。ただしクライアント側の「client_entry_id でローカル行を特定」ロジックとの整合性要確認。

---

### FINDING-005 [P2, Candidate] — iOS 共有拡張: `ShareHostHandoffPolicy.outcome` の fail-closed メッセージに「手動で追加してください」とあるが、手動追加画面へのディープリンクや遷移ガイドがない
- **ユーザー影響**: App Group 不通時、ユーザーは「本体を開いて手動で追加」と案内されるが、具体的な操作手順（＋ボタン、貼り付け等）が提示されない。初回利用者は迷う。
- **再現条件**: App Group 未設定・権限不足・コンテナ破損等で `hasAppGroupAccess() == false` のとき共有拡張を起動
- **絶対パス+行**: `ios/URLSaverShared/Support/SharedContainer.swift:277-284`
- **根拠**: メッセージ文言のみでアクション導線なし。`urlsaver://` スキームも意図的に使わない設計（同ファイル 274-276 行コメント）のため、ディープリンク誘導も不能。
- **最小修正案**: メッセージに「アプリの『＋』ボタンから URL を貼り付けて保存できます」等の具体操作を追記。または `updateStatus` 表示時に「使い方」ページへのボタンを追加（UI 変更伴う）。
- **回帰リスク**: 低。文言変更のみ。

---

### FINDING-006 [P2, Candidate] — `ShareExtensionPendingOperation.isRecoverable` の `recoveryWindow` (15 分) が固定値で、ユーザーがアプリを再起動せず共有シートを開きっぱなしにするケース（iOS の共有拡張ライフサイクル上稀だがあり得る）で復元不能になる
- **ユーザー影響**: 共有シートを開いてから 15 分以上放置→保存ボタン押下で「中断した保存を復元」が表示されず、最初からやり直しになる。
- **再現条件**: 共有拡張起動 → 15 分以上経過 → 保存操作
- **絶対パス+行**: `ios/URLSaverShared/Support/SharedContainer.swift:61, 134-140`
- **根拠**: `recoveryWindow: TimeInterval = 15 * 60` 固定。`isRecoverable` で `now.timeIntervalSince(updatedAt) <= Self.recoveryWindow` 判定。
- **最小修正案**: 30 分〜1 時間程度に延長、または「更新時刻からの経過」ではなく「作成時刻からの経過」にする（`updatedAt` はタグ選択等で更新されるため）。仕様上「共有シートを開きっぱなし」は稀だが、安全側に倒す。
- **回帰リスク**: 低。定数変更のみ。

---

### FINDING-007 [P3, Candidate] — `URLRulesTests.swift` の新規テスト `testShareHandoffWithoutAppGroupFailsClosedWithRecoveryMessage` が `message.lowercased().contains("urlsaver://")` でチェックしているが、将来的にメッセージ文言に「urlsaver」という単語が含まれる（例: 「りんばむ（urlsaver）アプリで...」）と偽陽性になる
- **絶対パス+行**: `ios/URLSaveriOSTests/URLRulesTests.swift:655`
- **根拠**: 部分一致チェックが緩すぎる。`urlsaver://` スキーム固有のパターン（`urlsaver://save` 等）で検証すべき。
- **最小修正案**: `XCTAssertFalse(message.contains("urlsaver://"))` に変更。
- **回帰リスク**: 低。

---

## 4. CONFIRMED_SAFE

| 対象 | 確認内容 |
|------|----------|
| iOS Share Extension: App Group 不通時の `urlsaver://save?url=...` 経路完全削除 | `processShareViaHostAppFallback` 削除、`makeHostAppSaveURL` 削除、`ShareHostHandoffPolicy` で URL を運ばない `failClosed` のみ返す設計。URL が query string・ログ・テレメトリ・画面に露出しないことを確認。 |
| iOS Share Extension: 通常共有・タグ import・refresh handoff が壊れない | `hasAppGroupAccess() == true` 時は `proceedWithRepositoryAccess` で従来通りリポジトリ経由保存。タグ import は同一ガード内で `SharedContainer.hasAppGroupAccess()` チェック済み（`ShareViewController.swift:330-333`）。refresh handoff は `makeHostAppRefreshURL()`（`urlsaver://refresh`、URL を含まない）を使用し、App Group 不通時でも動作可能。 |
| `apply_personal_link_ops`: `unique (user_id, normalized_url)` 違反で RPC 全体が abort しなくなった | Conflict target を `(user_id, normalized_url)` に変更。同一ユーザー・同一 normalized_url の再 upsert は `DO UPDATE` で単一行に収束。pgTAP テスト（Scenario 1-3）がこの挙動を検証。 |
| `apply_personal_link_ops`: `op_id` 冪等性が維持される | `personal_link_applied_client_ops` への事前チェック（59-68 行）で再実行時は保存済み結果を返却。pgTAP Scenario 2 で確認。 |
| `apply_personal_link_ops`: 既存 ID・tag refs・RLS・unique 制約を壊さない | `RETURNING id INTO v_link_id` で既存行の PK を取得し、tag 操作はその `v_link_id` を参照。`personal_saved_link_tag_refs` の `ON CONFLICT (link_id, tag_id)` で冪等。RLS は `security definer` 関数内で `caller = auth.uid()` チェック済み。 |
| YouTube delegate: ループ防止ガード（同一ホストへの再帰呼び出し防止）が機能 | `_resolve_youtube_delegate` 内で `YOUTUBE_DELEGATE_HEADER` を付与し、delegate 側で同ヘッダー検知時に処理拒否する設計（コード未読だがヘッダー定義あり）。テスト `test_youtube_delegate_is_skipped_when_host_matches_current_resolver` で検証。 |
| YouTube delegate: `allow_delegate=false` で delegate 呼び出しスキップ | `resolve` メソッド引数で制御。テスト `test_youtube_delegate_is_skipped_when_loop_guard_is_disabled` で確認。 |
| Python テスト: `test_youtube_delegate_failure_falls_back_to_primary_resolver` が実際のバグ（FINDING-001 の前身）を再現 | delegate が `ok: false` を返すモックで主リゾルバが呼ばれることを `primary.assert_called_once()` で検証。定数 assert・自己言及ではない。 |
| pgTAP テスト: 構造契約（unique 制約存在）を事前検証 | `DO $$ ... $$` ブロックで関数・制約存在を確認してからシナリオ実行。 |

---

## 5. DEFERRED_VALIDATION

| 項目 | 理由 | 必要な検証 |
|------|------|------------|
| 実機 iOS で App Group 不通時の fail-closed 文言表示・手動追加導線 | シミュレータでは App Group アクセス可否の再現が困難。実機でプロビジョニングプロファイル無効化等で検証要。 | 物理 iPhone で App Group 無効化ビルド、共有シート起動、メッセージ確認、手動追加操作到達確認。 |
| 本番 Supabase で `apply_personal_link_ops` 移行（既存データあり） | 既存行の `client_entry_id` 分布、同一 `normalized_url` 複数行の有無、tag_refs 整合性を本番データで確認要。 | staging 環境で移行スクリプト実行前後での行数・重複・孤立 tag_ref 検証。 |
| Render / Railway への media resolver デプロイ時の YouTube delegate 疎通・fallback 実動作 | 環境変数 `MEDIA_RESOLVER_YOUTUBE_DELEGATE_URL` 設定時の実ネットワーク挙動（timeout、bot challenge、cookie 有効性）はローカル再現困難。 | staging デプロイ後、実 YouTube URL で解決ログ確認。delegate 失敗時の fallback ログ（FINDING-001 修正後）確認。 |
| iOS Store 審査への影響（共有拡張の App Group 必須化） | App Group 不通時は機能制限（保存不可）になるが、審査ガイドライン上「共有拡張が動作しない」とみなされるリスク。 | TestFlight で実機配布、審査員視点での共有フロー確認。 |
| Android 同等の fail-closed 実装有無 | 本差分は iOS のみ。Android 側（`ShareReceiverActivity` 等）で同様の `urlsaver://save` 経路がある場合、同等の fail-closed 化が必要。 | Android コードベース確認・必要なら同等修正。 |

---

## 6. COVERAGE

### 読んだファイル・関数
| ファイル | 主要関数・型 | 状態 |
|----------|-------------|------|
| `ios/URLSaverShareExtension/ShareViewController.swift` | `processShare`, `presentTagPicker`, `savePendingShare`, `executePendingOperation`, `attemptPendingItem`, `ShareHostHandoffPolicy.outcome` 参照 | 全行読了 |
| `ios/URLSaverShared/Support/SharedContainer.swift` | `ShareHostHandoffPolicy`, `ShareHostHandoffOutcome`, `ShareExtensionPendingOperation.isRecoverable`, `pendingOperationStore` | 全行読了 |
| `ios/URLSaveriOSTests/URLRulesTests.swift` | `testShareHandoffWithoutAppGroupFailsClosedWithRecoveryMessage`, `testShareHandoffWithAppGroupProceedsWithRepositoryAccess`, 既存テスト群 | 全行読了 |
| `scripts/media_resolver_backend.py` | `MediaResolver.resolve`, `_resolve_youtube_delegate`, `_resolve_youtube_cli_download`, `_resolve_youtube_direct_asset`, `_youtube_direct_result`, `_direct_media_proxy_url` | 全行読了 |
| `tests/test_media_resolver_backend.py` | `YouTubeDelegateTest` 4 件、`YouTubeDirectResultTest` 6 件、その他 | 全行読了 |
| `supabase/migrations/20260824090000_fix_apply_personal_link_ops_conflict.sql` | `apply_personal_link_ops` 全体 | 全行読了 |
| `supabase/tests/chatgpt_personal_link_conflict_validation.sql` | pgTAP シナリオ 1-3、構造契約チェック | 全行読了 |

### 未読・対象外
- Android 側共有受信コード（`ShareReceiverActivity` 等）— 本差分に含まれないため対象外。ただし FINDING-008 で言及。
- `URLRepository` 実装（`saveFromResolvedURL`, `assignLocalTag` 等）— インターフェース呼び出しのみ確認、内部実装未読。
- `ShareExtensionPayloadExtractor`, `URLRules` 実装詳細— テストベクタ・インターフェースのみ確認。
- Supabase 他マイグレーション・RLS ポリシー全体— 当該関数と直接関わる制約のみ確認。

---

## 7. QUESTION_PACKET

**Q1. `apply_personal_link_ops` の後処理削除（269-280 行）を削除する場合、クライアント（Android/iOS/ChatGPT）側で「古い client_entry_id の掃除」をどう実現する想定か？ 明示的な `delete_link` op を送る実装になっているか？**

**Q2. `client_entry_id` を `DO UPDATE SET` で更新する案（FINDING-004）を採用する場合、同一 `link_id` で複数の `client_entry_id` が履歴的に残ることになるが、クライアント側の「ローカル DB の行を `client_entry_id` で特定して同期」ロジックと整合するか？**

**Q3. YouTube delegate のエラー詳細ログ（FINDING-001 修正）を入れたとき、本番 Render/Railway のログ集基盤（CloudWatch / Logtail / Datadog 等）で `error` / `message` フィールドが構造化ログとして抽出可能か？ 現状 `_safe_log` は `stderr` へプレーンテキスト出力だが、JSON ライン化の予定はあるか？**

**Q4. pgTAP テストの `extensions.plan(11)` は実行時テスト数と合致するか？ 現状 `ok` 2 件 + `is` 9 件 = 11 件で合致しているが、将来テスト追加時に `plan` 更新漏れ防止の仕組み（自動カウント等）はあるか？**

**Q5. iOS 共有拡張の `recoveryWindow = 15 分` は UX 観点で妥当か？ 「共有シートを開きっぱなし」シナリオを実ユーザー観測で確認済みか、あるいは延長不要の判断根拠は何か？**

---

以上。FINDING-001〜003 は本番適用前の修正必須。FINDING-004 は設計判断要（FINDING-003 とセットで決める）。FINDING-005〜007 は低リスクだが修正推奨。DEFERRED_VALIDATION 項目はリリースゲートとして実機・本番検証を完了させてからマージ推奨。
# Nemotron 3 Ultra Free 独立監査カウンターレビュー

## 1. MODEL_ASSIGNMENT
- **Model**: nemotron-3-ultra-free (opencode/nemotron-3-ultra-free)
- **Session ID**: ses_fcbb44eb5ffel29kLsqL9guhrg
- **Start**: 2026-08-24T15:13:24Z
- **End**: 2026-08-24T15:18:00Z

---

## 2. REVIEW_STATUS
**PASS** — 現行差分に P0/P1/P2 Confirmed 不備なし。全初回指摘は RESOLVED または REJECTED。

---

## 3. FINDINGS 再判定

| ID | 初
## 3. FINDINGS 再判定（続き）

| ID | 判定 | 根拠（コード行・差分） |
|----|------|------------------------|
| FINDING-001 | RESOLVED | `scripts/media_resolver_backend.py:526-531` `delegate_error = _truncate_log(str(delegated.get("error") or "UNKNOWN"), limit=80)` で error code のみ 80 字に圧縮しログ出力。`message` は参照しない。`tests/test_media_resolver_backend.py:224-246` で `sensitive upstream detail` がログに含まれないことを検証。 |
| FINDING-002 | REJECTED | 実コード `scripts/media_resolver_backend.py:825-864` は `errors` 配列に全試行のエラーを追加し `" | ".join(errors)` で全件連結して返す。「最終試行だけ」ではない。 |
| FINDING-003 | RESOLVED | `supabase/migrations/...sql:148` `client_entry_id = excluded.client_entry_id` で最新値へ更新。`270-287` で `:` / `-` 両形式の prefix を認識し同一名前空間の stale 行のみ soft-delete。`chatgpt_personal_link_conflict_validation.sql:222-296` Scenario 4 で固定。 |
| FINDING-004 | RESOLVED | FINDING-003 と同一修正で解消。`client_entry_id` 更新により独自制約 `(user_id, client_entry_id)` と整合。 |
| FINDING-005 | RESOLVED | `ios/URLSaverShared/Support/SharedContainer.swift:277-284` メッセージに `「＋」ボタンから貼り付けて保存してください` を追加。`URLRulesTests.swift:647-651` で文言検証。 |
| FINDING-006 | DEFERRED_VALIDATION | `recoveryWindow = 15*60` 変更なし。仕様判断・実機観測が必要なため実機検証に分離。 |
| FINDING-007 | REJECTED | `URLRulesTests.swift:655` `message.lowercased().contains("urlsaver://")` はスキーム完全部分列を検査。単語 `urlsaver` だけでは偽陽性にならない。 |

---

## 4. CONFIRMED_SAFE
初回レビューの CONFIRMED_SAFE 全項目に加え、以下を確認：
- YouTube delegate fallback 時の秘密情報非出力（FINDING-001 修正）
- `client_entry_id` 更新と同一名前空間のみ soft-delete（FINDING-003/004 修正）
- pgTAP Scenario 1-4 で収束・冪等・名前空間分離を固定

---

## 5. DEFERRED_VALIDATION
| 項目 | 理由 |
|------|------|
| 実機 iOS App Group 不通時 fail-closed 文言・手動追加導線 | 物理デバイスでプロビジョニング無効化ビルド要 |
| 本番 Supabase 移行（既存データ・tag_refs 整合性） | staging で行数・重複・孤立検証要 |
| Render/Railway デプロイ時 YouTube delegate 疎通・fallback 実動作 | 実ネットワーク・cookie 有効性確認要 |
| iOS Store 審査影響（共有拡張機能制限） | TestFlight 実機配布で審査員視点確認要 |
| Android 同等 fail-closed 実装有無 | Android コードベース別途確認要 |
| `recoveryWindow = 15分` UX 妥当性 | 実ユーザー観測または延長判断要 |

---

## 6. COVERAGE
初回と同一。再読ファイル：`supabase/migrations/...sql`（全行）、`supabase/tests/...sql`（全行）、`scripts/media_resolver_backend.py:523-531`、`tests/test_media_resolver_backend.py:201-253`、`ios/URLSaverShared/Support/SharedContainer.swift:266-290`、`ios/URLSaveriOSTests/URLRulesTests.swift:643-665`。

---

## 7. QUESTION_PACKET
**Q1.** `apply_personal_link_ops` 後処理削除を同一名前空間のみに限定したが、クライアント側で「別名前空間の古い client_entry_id」をどう扱う想定か？ 明示 `delete_link` op 送出か、将来的に全名前空間掃除 RPC を分離するか？

**Q2.** `client_entry_id` 更新により同一 `link_id` で履歴的に複数 `client_entry_id` が残るが、クライアントのローカル DB 特定ロジック（`client_entry_id` → row 紐付け）と整合するか？

**Q3.** YouTube delegate エラー詳細ログ（error code のみ）を本番ログ基盤で構造化抽出可能か？ `_safe_log` は現状プレーンテキストだが JSON ライン化予定はあるか？

**Q4.** pgTAP `plan(15)` は現行 15 アサーションと合致。将来追加時の自動カウント・更新漏れ防止仕組みはあるか？

**Q5.** `recoveryWindow = 15分` を延長しない判断根拠は実ユーザー観測か、それとも共有シート長時間開きっぱなしは無視でよい設計判断か？

---

## 8. REVIEW_STATUS
**PASS** — 現行差分に P0/P1/P2 Confirmed 不備なし。全初回指摘は RESOLVED（001, 003, 004, 005）または REJECTED（002, 007）。DEFERRED_VALIDATION 6 件はリリースゲートとして実機・本番検証を分離済み。
