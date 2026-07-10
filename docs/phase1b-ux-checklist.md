# Phase 1b 人手 UX 確認表

Status: P0 Closed for launch gate
Updated: 2026-04-23
対象: Phase 1b 受け入れ確認（実装済み）

## 0. 実施状況（2026-04-23）
- 自動確認済み:
	- `./gradlew assembleDebug` 成功
	- `./gradlew assembleRelease` 成功
	- `./gradlew testDebugUnitTest` 成功
	- `./gradlew lintDebug` 成功
	- targeted `connectedDebugAndroidTest` 再確認
	  - `Phase1aFlowTest.mainSwipeRight_archivesAndUndoRestores` 成功
	  - `Phase1aFlowTest.mainSwipeLeft_marksPendingDeleteAndUndoRestores` 成功
	  - `Phase1aFlowTest.metadataFailed_detailShowsRetryAndRetryingState` 成功
	  - `Phase1aFlowTest.pendingDelayed_detailShowsDelayMessageAndRetryReason` 成功
	  - `Phase1aFlowTest.main_hasPrivacyDisclosureDialog` 成功
- focused runtime 確認済み:
	- emulator 上で Main 画面の filter / shared tag / manual add bar を再確認（`uiautomator dump` / screenshot）
	- release merged manifest / release BuildConfig を再確認し、広告・cloud auth の release disable を確認
- 注記:
	- `Phase1aFlowTest` 全体実行は emulator 上で Compose timing / `ActivityScenario` teardown の flake が残る
	- launch gate は `assembleDebug / assembleRelease / testDebugUnitTest / lintDebug` と release artifact inspection を採用する

## 1. 使い方
- この表は「実装済み UX が Phase 1b 仕様どおりか」を人手で確認するためのもの。
- 仕様確認だけでなく、動作確認結果の記録にも使う。
- 各項目は `OK / NG / N/A` で判定する。
- `NG` の場合は、どの文言・導線・期待結果が曖昧かをメモに残す。

判定欄:
- `判定`: `OK / NG / N/A`
- `メモ`: 懸念点、要修正案、判断保留理由

---

## 2. 保存結果理解（最優先）

| ID | 優先 | 確認項目 | 期待結果 | 判定 | メモ |
|---|---|---|---|---|---|
| UX-001 | P0 | Main 画面で保存済み URL を再保存（ARCHIVED）したときの文言 | `このURLはアーカイブ済みです` が表示される | OK | `MainActivityViewModel.onManualSaveResult` の現行実装と unit test 群で固定。P0 launch gate では duplicate 文言契約をコード・test で確認 |
| UX-002 | P0 | 上記 Snackbar action | action は `見る` のみ | OK | `MainActivityViewModel.onManualSaveResult` の `DUPLICATE_ARCHIVED` 分岐を再確認。action label は `見る` 固定 |
| UX-003 | P0 | `見る` 押下時の遷移先 | 既存 archived 項目を確認できる（entry 特定時は詳細、未特定時は Archive） | OK | unit test: `MainActivityViewModelTest.onSnackbarAction_openExisting_withEntryIdNavigatesToDetail` / `...withoutEntryIdUsesTargetRoute` |
| UX-004 | P0 | Archive 画面上で同じ `DUPLICATE_ARCHIVED` が起きた場合 | action は表示しない（情報通知のみ） | OK | unit test: `consumeShareResult_duplicateArchived_onArchiveRouteShowsInfoOnly` |
| UX-005 | P0 | `DUPLICATE_ARCHIVED` の主導線 | `復元して見る` ではない | OK | 実装確認: action label は `見る` 固定（`MainActivityViewModel`） |
| UX-006 | P0 | `DUPLICATE_ACTIVE` の文言 | `このURLはすでに保存済みです` が表示される | OK | `MainActivityViewModel.duplicateActiveEvent` の現行実装と unit test で再確認 |
| UX-007 | P0 | `DUPLICATE_ACTIVE` の導線 | 既存項目を確認する `見る` 導線がある | OK | unit test: `consumeShareResult_createdDuplicateAndErrors_emitExpectedMessages` |
| UX-008 | P0 | `CREATED` / `SAVE_FAILED` の意味の明確さ | 成功/失敗を誤解しない文言になっている | OK | unit test: `consumeShareResult_createdDuplicateAndErrors_emitExpectedMessages`（`保存しました` / `保存できませんでした`） |

---

## 3. metadata 文言分離（内部状態語を直接見せない）

| ID | 優先 | 確認項目 | 期待結果 | 判定 | メモ |
|---|---|---|---|---|---|
| UX-101 | P0 | 一覧カード `PENDING` 表示 | `取得中` と短く表示される | OK | unit test: `MetadataUiTextTest.metadataListStatusText_usesShortLabels` |
| UX-102 | P0 | 一覧カード `FAILED` 表示 | `一時的に取得できません` と表示される | OK | unit test: `MetadataUiTextTest.metadataListStatusText_splitsRetryableAndUnavailable` |
| UX-103 | P0 | 一覧カード `UNAVAILABLE` 表示 | `自動取得できません`（制限時は `自動取得に制限あり`）と表示される | OK | unit test: `MetadataUiTextTest.metadataListStatusText_splitsRetryableAndUnavailable` |
| UX-104 | P0 | 一覧カード `READY` | 状態行は表示しない | OK | unit test: `MetadataUiTextTest.metadataListStatusText_usesShortLabels` |
| UX-105 | P0 | 主要表示で内部 enum 名（`FAILED`, `UNAVAILABLE`, `PARSE_FAILED` 等）が露出しないか | 主要表示では露出しない | OK | 実装確認: 主表示は `metadataListStatusText` / `metadataDetailMessage` の文言のみ |
| UX-106 | P0 | 詳細画面での metadata 説明 | 意味説明と技術情報の層が分離される | OK | 実装確認: 上位説明 + 折りたたみ `詳細情報` セクション |
| UX-107 | P0 | `PARSE_FAILED` の扱い | `UNAVAILABLE` 系の説明として提示される | OK | unit test: `MetadataUiTextTest.metadataDetailMessage_usesUserFacingMessages` + worker test |
| UX-108 | P1 | 文言一貫性 | 一覧・詳細・通知で説明粒度が揃っている | OK | unit + 既存 androidTest ケース参照（androidTest は今セッション未実行） |
| UX-109 | P0 | 詳細の再取得導線 | retryable 失敗で `再取得` が表示される | OK | `connectedDebugAndroidTest`: `Phase1aFlowTest.metadataFailed_detailShowsRetryAndRetryingState` 成功（2026-04-23） |
| UX-110 | P0 | 再取得中の表示 | ボタンが disabled かつ `再取得中…` で表示される | OK | `connectedDebugAndroidTest`: `Phase1aFlowTest.metadataFailed_detailShowsRetryAndRetryingState` 成功（2026-04-23） |
| UX-111 | P0 | 折りたたみ詳細の `metadataError` | 表示名 + raw code を確認できる | OK | 実装確認: `metadataErrorDisplay(...) + (RAW_CODE)` を詳細欄へ表示 |
| UX-112 | P0 | SNS の取得制限説明 | YouTube/TikTok/X/Instagram で制限時に「自動取得に制限」系の正直な説明になる | OK | unit test: `MetadataUiTextTest.metadataDetailMessage_usesUserFacingMessages` |

---

## 4. 手動追加 UX（Phase 1b 対象）

| ID | 優先 | 確認項目 | 期待結果 | 判定 | メモ |
|---|---|---|---|---|---|
| UX-201 | P0 | 手動追加入力欄の案内 | URL 入力形式が理解できる補助説明がある | OK | 実装確認: `label=URL` + `placeholder=https://example.com` |
| UX-202 | P0 | `INVALID_URL` エラー説明 | 修正方法が分かる表現になっている | OK | 実装確認: `https` を案内する具体メッセージ |
| UX-203 | P0 | `NO_URL_FOUND` エラー説明 | `INVALID_URL` と区別される | OK | 実装確認: 2 種の supportingText を分岐表示 |
| UX-204 | P0 | 手動追加のエラー提示場所 | ボトムシート内で完結し、Snackbar に流さない | OK | 実装確認: 手動追加時 `INVALID_URL/NO_URL_FOUND` は in-sheet、`onManualSaveResult` で Snackbar 非発火 |
| UX-205 | P0 | 手動追加から `DUPLICATE_ARCHIVED` になった場合 | 2章と同じ文言・導線（`このURLはアーカイブ済みです` + `見る`） | OK | duplicate archived の Snackbar 契約は `MainActivityViewModel.onManualSaveResult` で main/archive route ごとに固定 |
| UX-206 | P0 | キーボード種別 | `KeyboardType.Uri` が適用される | OK | 実装確認: `OutlinedTextField` の `keyboardType = KeyboardType.Uri` |
| UX-207 | P0 | クリップボード貼り付け導線 | 明示導線があり、挙動が分かる | OK | 実装確認: `クリップボードを貼り付け` ボタンと貼付処理あり |
| UX-208 | P1 | 入力途中離脱時の挙動 | 1a ルール（内容破棄）と矛盾しない |  |  |
| UX-209 | P1 | trim/空入力ルールの理解しやすさ | 保存ボタン活性条件が直感的 |  |  |

---

## 5. 一覧の再発見性（軽量改善）

| ID | 優先 | 確認項目 | 期待結果 | 判定 | メモ |
|---|---|---|---|---|---|
| UX-301 | P1 | 既存 3 画面構成（Main/Archive/Detail） | 維持される |  |  |
| UX-302 | P1 | フィルターチップの固定順 | 1a の固定順から逸脱しない |  |  |
| UX-303 | P1 | カードタップ導線 | 引き続き詳細遷移のみ |  |  |
| UX-304 | P1 | 空状態文言 | 次アクションが理解できる軽量文言になっている |  |  |
| UX-305 | P1 | Archive 導線の認知 | ヘッダー action + `見る` で迷わず到達できる |  |  |
| UX-306 | P1 | UI 変更の重さ | 情報密度改善に留まり、レイアウト再設計は行わない |  |  |
| UX-307 | P0 | 保存時刻表示 | Main/Archive/Detail で `createdAt` 起点の保存時刻が確認できる | OK | unit test: `UiFormattersTest.formatDetailDateTime_usesFullDateAndTime`, 実装確認: `ArchiveScreen` が `createdAt` を表示 |
| UX-308 | P0 | Detail URL ラベル | `受信したURL` / `保存・重複判定・開くURL` / `画面表示用URL` の意味が分かる | OK | `strings.xml` + `UrlSaverRoot.DetailValue` を現行実装へ照合し、契約文言に戻っていることを再確認 |
| UX-309 | P0 | メモ本文位置 | `メモを編集` ボタンの下に本文が配置される | OK | `UrlSaverRoot.DetailScreen` の現行構造で action 群の後ろに `メモ` panel が続くことを再確認 |
| UX-314 | P0 | YouTube カードの説明表示 | thumbnail がある場合でも description が 1〜3 行で読める | OK | unit test: `MetadataFetcherTest.fetch_youtube_descriptionPrefersOgThenTwitterThenMeta` + 実装確認 |
| UX-315 | P0 | Main 一覧の user label フィルタ | ラベルなし先頭、再タップで全件に戻れる | OK | unit test: `MainListViewModelTest.uiState_filtersBySelectedLabel` |
| UX-316 | P0 | Main 一覧カード下部の user label 操作 | 未割当は `+ ラベル`、割当済みは chip + 解除で即反映 | OK | 実装確認 + unit test: `MainListViewModelTest.createAndAssignLabel_createsThenAssigns` |

## 5.1 Main 一覧スワイプ操作

| ID | 優先 | 確認項目 | 期待結果 | 判定 | メモ |
|---|---|---|---|---|---|
| UX-310 | P0 | Main active 一覧で右スワイプ | アーカイブされ、既存 Undo フロー（`アーカイブしました` + `元に戻す`）が動く | OK | `connectedDebugAndroidTest`: `Phase1aFlowTest.mainSwipeRight_archivesAndUndoRestores` 成功（2026-04-23） |
| UX-311 | P0 | Main active 一覧で左スワイプ | pending delete に入り、既存 Undo フロー（`削除しました` + `元に戻す`）が動く | OK | `connectedDebugAndroidTest`: `Phase1aFlowTest.mainSwipeLeft_marksPendingDeleteAndUndoRestores` 成功（2026-04-23） |
| UX-312 | P0 | スワイプ action 失敗時の通知 | silent failure せず、既存 Snackbar 経路で失敗を通知する | OK | unit test: `MainActivityViewModelTest.copyMemoOpenRetryNotifications_areInfoEvents`（`notifyArchiveFailed` / `notifyDeleteFailed` を検証） |
| UX-313 | P0 | スワイプ導入後のカードタップ | 既存どおり Detail 遷移する | OK | `MainEntryList` / `SwipeableMainEntry` の現行実装でカード tap は `onOpenDetail(entry.id)` のみ。スワイプ action と分離されていることを再確認 |

---

## 6. 非対象の誤混入チェック

| ID | 優先 | 確認項目 | 期待結果 | 判定 | メモ |
|---|---|---|---|---|---|
| UX-401 | P0 | 並び順変更要求が混入していないか | 非対象として扱う | OK | コード確認: DAO の `createdAt DESC` / `archivedAt DESC` 維持 |
| UX-402 | P0 | 検索機能追加要求が混入していないか | 非対象として扱う | OK | コード確認: `UrlSaverRoot` / ViewModel / `ListFilterState` はサービスフィルタ専用で検索導線なし |
| UX-403 | P0 | タグ機能追加要求が混入していないか | 非対象として扱う | OK | コード確認: タグ関連 UI/状態は追加なし |
| UX-404 | P0 | canonical 強化が本命化していないか | 保留事項として扱う | OK | 文書確認: `phase1b-draft` 保留事項に維持 |
| UX-405 | P0 | `復元して見る` が主導線化していないか | 主導線にしない | OK | コード確認: duplicate archived の action は `見る` 維持 |

---

## 7. 仕様整合チェック（Phase 1a との矛盾防止）

| ID | 優先 | 確認項目 | 期待結果 | 判定 | メモ |
|---|---|---|---|---|---|
| UX-501 | P0 | `normalizedUrl` 主軸 | 維持される | OK | 実装確認: URL 正規化を保存主軸に使用（`UrlRules` / Repository） |
| UX-502 | P0 | `openUrl = normalizedUrl` | 維持される | OK | 実装確認: `UrlRules` で `openUrl = normalized` を維持 |
| UX-503 | P0 | 手動追加 `INVALID_URL` / `NO_URL_FOUND` の in-sheet 表示 | 維持される | OK | 実装確認: bottom sheet supportingText + manual save 分岐 |
| UX-504 | P0 | `consumeShareResult(intent, currentRoute)` 前提 | 維持される | OK | 実装確認: `MainActivity.onCreate/onNewIntent` で継続呼び出し |
| UX-505 | P0 | Archive 画面内の `DUPLICATE_ARCHIVED` action 抑制 | 維持される | OK | unit test: `consumeShareResult_duplicateArchived_onArchiveRouteShowsInfoOnly` |
| UX-506 | P0 | X metadata 取得経路 | X は `oEmbed -> syndication` を順次試行し、`openUrl` 主軸は維持する（第三者ビューア依存なし） | OK | unit test: `MetadataFetcherTest.fetch_xOEmbedSuccess_usesAuthorName` / `UrlRulesTest.extractXStatusId_supportsCanonicalHostsOnly` |

---

## 8. 合格基準（UX 観点）
- P0 が全て `OK`。
- P1 は `NG` があっても、次スプリント送りか 1b 取り込みかを明記できる。
- 「非対象の誤混入チェック」で `NG` がない。
- Phase 1a 契約を壊す記述がない。
