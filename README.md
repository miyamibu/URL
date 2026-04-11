# URL Saver (Android, Phase 1a/1b)

共有された URL を「あとで開き直す」ために保存する Android ネイティブアプリです。

## Target User
SNS やメッセージで見つけた URL を後で見返したい人向けの、軽量な「あとで開く」保存アプリです。

## Core User Value
共有や手動入力で URL を迷わず保存し、保存後は Main / Archive / Detail で状態を理解しながら再オープンできます。

## Key Principles
- 保存主対象は URL
- 重複判定主キーは `normalizedUrl`
- `openUrl = normalizedUrl` (Phase 1a 固定)
- `userTitle` は `fetchedTitle` より優先
- 一覧カードタップは詳細遷移のみ (直接外部起動しない)
- 検索機能は Phase 1a/1b の対象外
- `ACTION_SEND_MULTIPLE` は複数 URL を順次保存し、集計結果を通知する

## Release Gate (Binary)
- `./gradlew assembleDebug` が成功する
- `./gradlew testDebugUnitTest` が成功する
- `./gradlew lintDebug` が成功する
- `docs/phase1b-ux-checklist.md` で P0 の確認状態が実測に基づいて記録されている
- 新規インストールの基本導線（起動 -> 保存 -> 詳細遷移）が silent failure なしで完了する
- Crash reporting は本リリースでは「未導入で公開可」とする運用判断を文書化済み

## Tech Stack
- Kotlin
- Jetpack Compose + Material3
- Navigation Compose
- Room
- ViewModel + Coroutines/Flow
- WorkManager

## Commands
- `./gradlew assembleDebug`
- `./gradlew testDebugUnitTest`
- `./gradlew lintDebug`
- `./gradlew connectedDebugAndroidTest`

## Verification Status (2026-04-11)
- `./gradlew clean assembleDebug`: success
- `./gradlew testDebugUnitTest`: success
- `./gradlew lintDebug`: success
- `./gradlew connectedDebugAndroidTest`: success (`urlsaverApi35` AVD)
- `docs/phase1b-ux-checklist.md`: P0 全項目を `OK` で記録済み
- `ACTION_SEND_MULTIPLE` の複数 URL 処理（batch summary extras）を unit test で確認

## Project Structure
- `app/src/main/java/jp/mimac/urlsaver/domain`: URL ルール/モデル
- `app/src/main/java/jp/mimac/urlsaver/data`: DB/Repository
- `app/src/main/java/jp/mimac/urlsaver/worker`: metadata worker
- `app/src/main/java/jp/mimac/urlsaver/ui`: 画面/Navigation/ViewModel
- `docs/phase1a-spec.md`: Phase 1a 詳細仕様
- `docs/phase1b-draft.md`: Phase 1b 実装仕様（状態理解と再発見性改善）
- `docs/phase1b-ux-checklist.md`: Phase 1b 人手 UX 確認表

## Privacy / Local Storage Policy
- 保存データは端末内 Room DB に保持する（URL、タイトル、メモ、metadata 状態）
- 端末バックアップは無効化している（`allowBackup=false`, `fullBackupContent=false`）
- 復元機能を提供している前提ではない
- Crash reporting は本リリースでは未導入（OS ログ + 手動再現手順で運用）

## Share Intake Degradation Policy
- `ACTION_SEND_MULTIPLE` は対応済み（有効 URL を重複除外して順次保存）
- `ACTION_SEND_MULTIPLE` は「総件数・新規・既存・復元・失敗」の集計を通知する
- `ACTION_SEND` で1つの共有テキストに複数 URL が含まれる場合は、先頭 1 件のみ保存し、UI で明示通知する
- URL が見つからない / 無効な場合は明示エラーを表示し、無言ドロップしない
