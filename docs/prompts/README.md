# Codex 作業ルール・手順の索引

現行の作業ルールと繰り返し使う手順への入口。仕様と承認境界は `AGENTS.md` を正とし、個別手順は関連するSkillと契約文書を参照する。

## 現行の参照先

- [AGENTS.md](../../AGENTS.md): プロジェクトの仕様、作業範囲、承認境界。
- [CODEX_INSTRUCTIONS.md](../../CODEX_INSTRUCTIONS.md): Codex向けの互換入口。
- [DESIGN.md](../../DESIGN.md): UI/UXの設計方針。
- [Mobile UI Regression Contract](../mobile-ui-regression-contract.md): Android/iOSの画面・データ互換性の回帰契約。
- [Rinbam Single Route](../../.agents/skills/rinbam-single-route/SKILL.md): モバイル変更・検証の共通手順。
- [UI Design Brief](../../.agents/skills/ui-design-brief/SKILL.md): UI設計前の要件整理。
- [Image to UI Implementation](../../.agents/skills/image-to-ui-implementation/SKILL.md): 画像から実装仕様へ変換する手順。
- [Frontend Visual Review](../../.agents/skills/frontend-visual-review/SKILL.md): 変更後の画面レビュー。
- [ローカル制作物・検証資料](../local-artifacts.md): Git管理外に保持している資料と保管方針。

## 旧プロンプト

2026-09-12の整理で、以下の旧資料の削除差分を確定した。現行仕様の入口としては使わない。本文は削除前のGit履歴に保持されている。

- `docs/codex-cross-platform-review-prompt.md`
- `docs/codex-dark-ui-implementation-prompt.md`
- `docs/codex-ios-port-prompt.md`
- `docs/codex-shared-tag-invite-sync-prompt.md`
- `docs/codex-swipe-list-actions-prompt.md`
- `docs/understand-anything/08-image2-final-prompt.md`

これら6件は `bb06744e` に存在するため、必要時は `git show bb06744e:<path>` で本文を参照できる。過去の実行記録に残るファイル名は履歴として保持する。

## 索引の更新

参照先を追加・変更したときは、リンクが実在することと、その文書の役割が現行仕様と一致することを確認する。長い実行手順や仕様をこの索引へ重複記載しない。
