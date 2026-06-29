# Codex Instructions

## Goal
Codex 作業時の互換入口を残し、現行の詳細ルールへ迷わず到達できるようにする。

## Context
このリポジトリの実作業ルールは `AGENTS.md` を正とする。Codex 向け prompt の管理ルールは `docs/prompts/README.md` を参照する。

## Constraints
- 破壊的操作、実機データ削除、秘密値、deploy、store 操作は `AGENTS.md` の制約を優先する。
- Phase 1a/1b の URL 保存契約はコア不変条件として守る。
- 検索・タグ・コレクション・共有タグ・AI-friendly export・課金/権限まわりは、承認済みの現在機能として扱う。

## Done when
- 作業開始前に `AGENTS.md` と関連 docs/skills を確認する。
- 変更後は最も近い検証を実行し、未検証事項と残リスクを日本語で報告する。
