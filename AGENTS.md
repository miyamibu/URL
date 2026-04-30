# AGENTS.md

## Repository Purpose
Android ネイティブで「共有された URL をあとで開き直す」ための実装を維持し、Phase 1a/1b の仕様を守る。

## Phase 1a Scope
- `ACTION_SEND` の単一 URL 共有受信
- `ACTION_SEND_MULTIPLE` の複数 URL 共有受信（順次保存 + 集計通知）
- 手動貼り付け保存
- URL 抽出 / 正規化 / 重複判定
- Room 永続化（`normalizedUrl` DB unique）
- Main / Archive / Detail
- Main の active 一覧スワイプ（右: archive、左: pending delete + Undo）
- 削除猶予 + Undo（DB-backed）
- metadata WorkManager enqueue
- 仕様・テスト・文書更新

## Phase 1b Scope
- duplicate active/archived の状態理解導線改善（`見る`）
- metadata の内部状態と UI 文言の分離
- 手動追加 UX 改善（エラー文言、duplicate 導線、最小入力補助）
- 一覧の軽量な再発見性改善（構造は維持）

## Out of Scope
- WebView
- 検索機能
- タグ / 高度検索
- Hilt / Koin
- Phase 1c 以降の拡張を先行追加

## Directory Roles
- `app/src/main/java/.../domain`: URL ルール・表示ルール・状態モデル
- `app/src/main/java/.../data`: Room/DAO/Repository/Share extras/Work scheduler
- `app/src/main/java/.../worker`: Resolve/Fetch workers
- `app/src/main/java/.../ui`: Compose 画面・Navigation・ViewModel
- `docs/`: Phase 1a 仕様

## Commands
- Build: `./gradlew assembleDebug`
- Unit test: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
- Instrumentation: `./gradlew connectedDebugAndroidTest`

## URL Save Philosophy
- 主対象は URL
- 重複主キーは `normalizedUrl`
- `originalUrl` は生入力保持
- `canonicalId` は補助情報
- `displayUrl` は表示専用
- `openUrl = normalizedUrl`（Phase 1a 固定）
- 一覧カードタップは詳細遷移のみ（直接 open しない）

## Title/Memo Rules
- `effectiveTitle`: `userTitle > fetchedTitle > サービス名 + のリンク > normalizedHost > 保存したリンク`
- `userTitle` 空白入力は `null`
- `memo` 空白のみは `""`
- `userTitle` 最大 120、`memo` 最大 2000
- `userTitle` はインライン編集 + IME Done で即保存 + `UNDO_TITLE_EDIT`
- `memo` はダイアログ編集、Undo なし

## WorkManager
- 役割: metadata 取得
- unique work key: `metadata:{entryId}`
- `ExistingWorkPolicy.KEEP`
- `NetworkType.CONNECTED`
- exponential backoff 初期 10 秒
- retry 合計 3 回

## Never / Always / Ask first
- Never: `normalizedUrl` 以外を重複主キーにしない
- Never: `openUrl` を `normalizedUrl` 以外にしない
- Never: Phase 1c へ勝手に広げない
- Always: ViewModel/Repository/Worker/Domain の責務分離を守る
- Always: metadata 更新だけで `updatedAt` を更新しない
- Always: ShareReceiverActivity → MainActivity は Intent extras のみ
- Ask first: 破壊的変更、仕様外の拡張、データ互換性を壊す変更

## UI / UX work

- For UI/UX tasks, read `DESIGN.md` before editing code and keep long repeatable procedures in `.agents/skills/`.
- Source of truth priority: current explicit user instruction, existing Compose/SwiftUI code and domain contracts, `DESIGN.md`, approved Figma/design files, screenshots or `gpt-image-2` images, then ambiguous natural-language preferences.
- Do not implement directly from generated images or screenshots. First convert the visual reference into an implementation brief covering layout, components, tokens, states, responsive behavior, accessibility, risks, and validation.
- If `gpt-image-2` images are generated or received for UI direction, show the options, summarize strengths/risks, and wait for explicit user approval before implementation.
- Do not add new UI, icon, animation, CSS framework, font, or design-token dependencies without explicit approval.
- Do not commit, push, deploy, publish builds, or change release configuration without explicit approval.
- For substantial UI changes, state the validation plan and save available evidence under `artifacts/ui-review/YYYY-MM-DD/`.

## Destructive Operations Guard

Do not delete, rename, move, overwrite, reset, clean, truncate, drop, or destroy existing project files, folders, source files, docs, tests, migrations, assets, screenshots, logs, evidence files, database records, local data, user data, business data, or audit/traceability data without explicit user approval in the current conversation.

Hard delete/destructive operations include, but are not limited to:

- deleting files or folders
- deleting source files, docs, migrations, tests, assets, logs, screenshots, or evidence files
- `rm`, `rm -rf`, `rmdir`, `unlink`, `shred`, `truncate`
- `git clean`
- `git reset --hard`
- `git checkout --` or broad `git restore` that discards work
- SQL `DELETE`, `DROP`, or `TRUNCATE`
- deleting database files, app data, local storage, seed data, fixtures containing meaningful records, or exported data
- removing data needed to reconstruct user, business, accounting, audit, review, payment, refund, or app state
- running scripts whose purpose or side effect is deletion or irreversible cleanup

Before any destructive operation, ask the user for explicit approval and explain:
1. exactly what would be deleted or changed
2. why it is necessary
3. whether it is reversible
4. what backup or recovery path exists

Prefer reversible alternatives:
- status transitions
- archive flags
- soft-delete fields
- quarantine folders
- backup copies
- narrow diffs
- explicit migration scripts with rollback notes

Safe cleanup exceptions are narrow:
- temporary files created during the current task
- generated build artifacts that are clearly reproducible
- isolated test-only outputs
- isolated temporary test databases
- local throwaway outputs created during the current task

These exceptions do not apply to source files, docs, migrations, tests, production-like data, user/business/audit data, evidence files, screenshots, logs, exported data, or anything needed to explain app behavior.

When uncertain, do not delete. Ask first.
