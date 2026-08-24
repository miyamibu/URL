# Production GO UI design brief

Goal:
監査で残った共有受信、iOS Share Extension、metadata失敗、エラー復旧、アクセシビリティ候補を、既存の静かなURL保存UXを崩さず閉じる。

Context:
Android ComposeとiOS SwiftUI/Share Extensionが対象。既存の状態復元差分では、タグ選択・新規タグ名・エラー・結果を構成変更後も保持し、処理coroutineと同寿命の`isSaving`は保存しない。独立監査には誤検知があるため、現行コードと契約で確認できた項目だけ変更する。

Target users:
初見ユーザー、IT不慣れユーザー、大量保存を行う熟練ユーザー、Dynamic Type/TalkBack/VoiceOver利用者、運用・サポート担当。

Target platform:
Android共有受信、Android/iOSのmetadata状態表示、iOS Share Extension。ホーム5アクション、使い方、タグ行、カード、詳細タグ見出しは既存契約を維持する。

Primary flow:
URL共有または手動保存 -> 必要ならタグ選択 -> 保存中 -> 成功・重複・部分失敗・再試行可能エラーを明示 -> 一覧または完了へ戻る。

States to support:
- 入力中、タグ選択済み、新規タグ入力、タグ作成エラー
- 保存中、一時中断、構成変更、成功、重複、部分タグ失敗
- metadata pending/failed/unavailable/offline/retry
- Share Extensionの共有領域利用可・利用不可・複数URL縮退
- Dynamic Type、TalkBack/VoiceOver、キーボード、回転、ダークモード

Existing UI patterns to reuse:
既存Material3/Orbit token、SwiftUI標準Button/alert/status text、既存`MetadataUiText`、既存の結果画面・Undo・部分失敗文言。新規dependency、font、icon、animation、design tokenは追加しない。

Constraints:
- `normalizedUrl`が唯一の重複キー、`openUrl = normalizedUrl`
- ShareReceiverActivityからMainActivityはIntent extrasのみ
- iOS fallbackで元URLを平文custom URL queryへ新規露出させない
- silent failure禁止。安全なhandoffが成立しない場合は失敗を明示してfail closed
- shared tag、AI-safe export、MCPの既存privacy境界を変更しない
- Collection/UserLabel互換殻を削除しない
- Android実機データを消さない

Implementation notes:
- `rememberSaveable`はユーザー入力・選択・結果に限定し、一時処理フラグは処理主体と同寿命にする
- iOS App Group不通時は、実証済みの安全な不透明token handoffが構築できなければ平文URL routeを廃止し、理由付きで保存を中止する
- タグ複数割当を原子的にする場合、local/shared tagのlimit・outbox・scheduleを含めてRepository境界で一貫させる
- metadata失敗表示は技術語を避け、再試行可能性と次の操作を示す

Validation plan:
- 変更前後`python3 scripts/verify_mobile_ui_contract.py`
- Java 21 Android unit/lint/build/release bundle
- iOS unit/build-for-testing/test-without-building、unsigned Release build
- Android emulatorまたはデータ保持可能な実機で共有・回転・再試行
- USB physical iPhone + Appium/WDAでShare Extension、Dynamic Type、scroll、result state
- screenshots/source/logを`artifacts/ui-review/2026-08-24/production-go/`へ保存

Failure handling:
データ消失、silent failure、raw URL/secret露出、既存UI契約の回帰、正準Bundle ID不一致、実機操作証拠不足があればGOへ進めない。外部認証が必要な場合は代表質問者へ集約する。
