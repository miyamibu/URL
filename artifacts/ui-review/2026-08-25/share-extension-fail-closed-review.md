# iOS Share Extension fail-closed visual review

Review summary:

- 対象は App Group にアクセスできない場合の完了メッセージのみ。画面構造、色、ボタン、制約、アニメーション、依存関係は変更していない。
- 変更後は「保存できなかった理由」と「共有元でURLをコピー → りんばむ本体の『＋』 → 貼り付けて保存」という復旧手順を一画面で示す。技術語や custom URL scheme はユーザー表示に含めない。
- 既存の `updateStatus(_:finished:)` を再利用し、iOS の Dynamic Type、複数行中央揃え、Safe Area 内余白、完了ボタンとの分離を維持する。
- 静的レビューと契約テストの範囲では visual/UX 回帰なし。App Group 不通状態の物理 iPhone 実画面は未再現のため、最終 visual proof は `PARTIAL_PASS` とする。

Issues found:

- Confirmed P0/P1/P2: なし。
- 文言が旧案の「手動で追加してください」だけでは初見ユーザーに操作が曖昧だったため、Nemotron の Candidate 指摘を採用し、「＋」ボタンと貼り付け保存まで具体化した。
- 15分の pending-operation recovery window は本変更の表示レイアウトとは別の仕様候補。確定不具合として変更していない。

Evidence:

- `/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Support/SharedContainer.swift`: URLを運ばない `ShareHostHandoffOutcome.failClosed` と最終文言。
- `/Users/mimac/Desktop/りんばむ/ios/URLSaverShareExtension/ShareViewController.swift`: App Group 不通時に既存 `updateStatus` へ文言を渡し保存処理を中止。
- `statusLabel.numberOfLines = 0`、`preferredFont(forTextStyle: .title2)`、`adjustsFontForContentSizeCategory = true`、Safe Area と左右24pt以上の制約を確認。
- `/Users/mimac/Desktop/りんばむ/ios/URLSaveriOSTests/URLRulesTests.swift`: 保存失敗、「＋」ボタン、貼り付け保存、`urlsaver://` 非含有を契約化。
- Nemotron 3 Ultra Free 同一セッションのカウンターレビューで FINDING-005 `RESOLVED`、現差分の Confirmed P0/P1/P2 なし。

Commands run:

- `python3 scripts/verify_mobile_ui_contract.py`: PASS。
- focused `URLRulesTests`: 39 tests / 0 failures（最終文言反映後）。
- iOS Simulator full suite: 232 tests / 0 failures / 3 live-cloud tests skipped（変更前の最終構造。最終文言反映後に再実行予定）。
- `git diff --check`: PASS。

Residual risk:

- 通常の署名済みビルドでは App Group が有効なため、不通画面を物理 iPhone 上で自然再現できていない。App Group entitlementを外した専用検証ビルドは配布物と署名条件が異なるため、本番同一性の証拠にはしない。
- 実画面未取得のため、最大 Dynamic Type での最終スクリーンショット、VoiceOver読み上げ順、狭い Share Extension 高さでの改行位置は未検証。既存UILabelの可変行・Dynamic Type設定により静的には対応している。
- 本番候補で App Group が正常なことは、署名entitlementと通常共有保存の実機確認を別ゲートとして検証する。

REVIEW_STATUS: PARTIAL_PASS (static and automated contract pass; physical App Group-unavailable visual state deferred)
