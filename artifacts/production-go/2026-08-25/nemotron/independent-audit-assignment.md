# Nemotron 3 Ultra Free 独立監査割当

## Goal

現行 `origin/main` 基準の修復候補差分を、独立した認可済みレッドチームとして監査し、本番適用前に P0/P1/P2 の確定不備を検出する。

## Context

- canonical root: `/Users/mimac/Desktop/りんばむ`
- branch: `codex/production-go-20260824`
- baseline HEAD: `15c7554a9a51f7288a684ddeffa91038ab2966bd` (`origin/main` と同一)
- 作業ツリーは意図的に dirty。監査対象のソース差分は下記。
- 先行監査の結論と実装者の判断から独立させるため、`artifacts/project-audit/` と `artifacts/production-go/2026-08-24/ox-alpha/` は読まない。その内容を引用しない。

## In-scope source diff

- `ios/URLSaverShareExtension/ShareViewController.swift`
- `ios/URLSaverShared/Support/SharedContainer.swift`
- `ios/URLSaveriOSTests/URLRulesTests.swift`
- `scripts/media_resolver_backend.py`
- `tests/test_media_resolver_backend.py`
- `supabase/migrations/20260824090000_fix_apply_personal_link_ops_conflict.sql`
- `supabase/tests/chatgpt_personal_link_conflict_validation.sql`

## Required review perspectives

1. セキュリティ/プライバシー: iOS App Group 不通時に URL が custom-scheme、ログ、テレメトリ、画面へ露出しないか。fail-closed が通常共有・タグimport・refresh handoffを壊さないか。
2. DB整合性/並行性: `apply_personal_link_ops` の conflict target 変更が、異なる `client_entry_id` と同一 `normalized_url` を安全に収束させるか。既存ID、tag refs、op_id冪等、削除/無効化、RLS、unique制約、マルチデバイスを壊さないか。
3. テスト信頼性: 新規Swift/pgTAP/Pythonテストが実際のバグを再現し、定数assert・自己言及・偽陽性ではないか。
4. レジリエンス: YouTube delegate が `ok=false`、HTTP異常、timeout、不正JSON、部分成功のとき、primary resolver への fallback が適切か。再帰、過負荷、二重ダウンロード、エラー隠蔽がないか。
5. ユーザー/UX/アクセシビリティ: fail-closed 文言が原因と復旧手順を正確に伝え、Dynamic Type/VoiceOverで致命的回帰を作らないか。
6. リリース/運用: この差分を production DB、Render/backend、iOS Store へ出すときのrollback、順序、外部ゲートを指摘する。

## Constraints

- ソース、テスト、ドキュメント、Git状態を変更しない。read-only。
- テスト/build/スクリプトを実行しない。静的監査に限定する。
- 秘密情報、`.env`、トークン、Cookie、署名鍵を読まない。
- 不確実な事項は `CANDIDATE`、実機/本番が必要な事項は `DEFERRED_VALIDATION`とし、確定不備と混ぜない。
- ユーザーへ直接質問しない。疑問は報告内の `QUESTION_PACKET` として代表質問者へ出す。

## Output format

日本語で以下の順。

1. `MODEL_ASSIGNMENT`: 実際のmodel名、session ID、開始/終了時刻
2. `REVIEW_STATUS`: `PASS` / `FAIL` / `INCOMPLETE_REVIEW`
3. `FINDINGS`: 重大度順。各指摘は ID、P0-P3、Confirmed/Candidate、ユーザー影響、再現条件、絶対パス+1-based行、根拠、最小修正案、回帰リスク
4. `CONFIRMED_SAFE`: 問題なしと確認した重要点
5. `DEFERRED_VALIDATION`: 実機/本番/外部サービスが必要な点
6. `COVERAGE`: 読んだファイル、関数、未読/対象外
7. `QUESTION_PACKET`: なければ `NONE`

行根拠のない抽象的警告、実行していない検証のPASS主張、先行監査の結論の転記は不可。

## Done when

対象差分とその直接依存経路を読み、確定不備と外部ゲートを分離し、最終レビュー本文を返す。
