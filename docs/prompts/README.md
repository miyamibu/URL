# Codex Prompt Docs Index

## Goal
Codex 向け prompt ドキュメントを 1 か所で発見できるようにし、既存資産を置き換えず再利用しやすくする。

## Context
- このリポジトリでは Codex 用の実装/レビュー prompt を `docs/codex-*.md` に保持している。
- 本 README はインデックス専用であり、各 prompt 本文は元ファイル側を正とする。

## Constraints
- 既存の `CODEX_INSTRUCTIONS.md` と `.codex/` 設定は上書きしない。
- 既存の `docs/codex-*.md` を改変せず、リンクと短い用途メモのみを管理する。
- 追加時は短い目的説明を 1 行添える。

## Done when
- 現在存在する `docs/codex-*.md` がすべてリンク付きで列挙されている。
- 各エントリに短い用途メモがある。
- 参照リンクがファイル存在チェックで解決できる。

## Output format
- 変更報告は `Changes made` / `Files touched` / `Validation` / `Open issues` の順で記載する。

## Validation method
- `docs/codex-*.md` の実ファイル一覧と index 記載が一致することを確認する。
- index 内リンクが相対パスとして解決可能であることを確認する。

## Failure-handling behavior
- 対象ファイルが見つからない場合は追加せず、欠落名と探索パターンを明記して停止する。
- 記載済みリンクが解決しない場合はリンクを一時削除せず、正しいパスを確定してから更新する。

## Prompt Files (`docs/codex-*.md`)
- [`../codex-cross-platform-review-prompt.md`](../codex-cross-platform-review-prompt.md): Android/iOS の差分、回帰、リスクを防御的に洗い出すレビュー実行 prompt。
- [`../codex-dark-ui-implementation-prompt.md`](../codex-dark-ui-implementation-prompt.md): HTML mock に合わせて挙動を変えずに Dark UI を実装する prompt。
- [`../codex-ios-port-prompt.md`](../codex-ios-port-prompt.md): Android Phase 1a/1b の仕様を native iOS へ移植する実装 prompt。
- [`../codex-shared-tag-invite-sync-prompt.md`](../codex-shared-tag-invite-sync-prompt.md): shared tag invite sync MVP を段階導入する実装 prompt。
- [`../codex-swipe-list-actions-prompt.md`](../codex-swipe-list-actions-prompt.md): Main 一覧の swipe archive/delete を安全に実装する prompt。
