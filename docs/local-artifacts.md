# ローカル制作物・検証資料

2026-09-12の作業ツリー整理で、以下を元の場所に保持したまま `.gitignore` の対象とした。ファイルの削除・移動はしていない。

## 対象

パスはリポジトリのルートからの相対パス。件数・容量は今回の整理開始時にGitから見えていた未追跡ファイルの集計で、既にignoreされていた画像などは含まない。

| パス | 件数 | 容量（MiB） | 内容 |
|---|---:|---:|---|
| `.firecrawl/` | 3 | 0.02 | 公開ページの取得資料 |
| `deliverables/` | 54 | 25.10 | V1動画、素材、制作スクリプト、検証記録 |
| `deliverables_v2/` | 211 | 1413.63 | V2動画、元収録、マスター、制作スクリプト、検証記録 |
| `deliverables_v3/` | 5677 | 4224.61 | 操作動画、Blender、レンダー画像、元収録、提出関連資料 |
| `deliverables_sns_h3/` | 45 | 32.79 | 20秒SNS動画、Blender、制作スクリプト、検証記録 |
| `artifacts/ui-review/2026-09-12/` | 25 | 29.42 | metadata検証記録、DB/WAL/SHM、WorkManager DB、Debug APK |
| `artifacts/git-review/` | 14 | 0.66 | Git棚卸し、差分分類、テストログ、実行結果。整理後の資料追加により増える |

除外は上記の具体的なディレクトリだけに限定している。`artifacts/` 全体や、将来の任意の制作フォルダを一括除外する設定ではない。

## 現在の制作物への入口

以下は既存の制作記録が指定するファイルで、今回の整理では存在を確認した。動画の再生・画質・Store公開状態を再検証したという意味ではない。

- 操作動画の索引: `deliverables_v3/README_CURRENT.md`
- 30秒操作動画: `deliverables_v3/final_take_f/RINBAM_OPERATION_SOCIAL_1080x1920.mp4`
- 保存用マスター: `deliverables_v3/final_take_f/RINBAM_OPERATION_MASTER.mov`
- 編集用Blender: `deliverables_v3/blender/rinbam_operation_take_f.blend`
- 20秒SNS動画: `deliverables_sns_h3/RINBAM_SNS_20s_FINAL.mp4`
- SNS制作記録: `deliverables_sns_h3/PRODUCTION.md`
- Git整理の詳細: `artifacts/git-review/2026-09-12/`

Gitから新しくcloneした環境には存在しないファイルのため、上記はローカル保管場所の案内として記載している。

## 保管とGitの境界

- `git status` がクリーンでも、上記ファイルがGitに保存されたことにはならない。今回、外部バックアップは作成していない。
- 制作フォルダには再生成可能な画像だけでなく、制作スクリプト、編集元、元収録、マスター、検証証跡も含まれる。ignoreを削除許可と扱わない。
- DBスナップショットやAPK、未編集の実機収録を通常のソースコミットへ一括追加しない。
- 制作スクリプトなどを将来Git管理へ移す場合は、必要なファイル・依存素材・秘密情報の有無を確認して個別に選ぶ。
- ignoreされた資料を確認するときは、`git status --short --ignored` や `git check-ignore -v <path>` を使う。`git clean -x` などでまとめて消さない。
- 別のGit worktreeはこの保管設定の対象ではない。独立した未コミット作業を保持しており、取り込みや片付けは内容確認のうえで別途行う。

## 2026-09-14 の整理

- `artifacts/ui-review/2026-09-13/` と `artifacts/ui-review/2026-09-14/` は、Android/iOS の実機画面、DB監査、検証ログを原位置に保持する。
- `output/playwright/` は公開ページの取得証拠として保持する。
- `artifacts/git-review/2026-09-14/` に今回の差分分類、作業フォルダのSHA-256照合、検証ログ、ストア提出記録を保持する。
- 過去の2作業フォルダは、未コミットのソースと文書を保全用ブランチへ記録する。提出版には、公開済みの共有・通知機能と現在の修正を内容確認して取り込む。
- 提出用AAB/IPA、DB、元収録、監査の生ログはソースコミットに含めない。除外設定による保管であり、ファイルの削除や移動は行わない。

## 2026-09-16 の整理

- `artifacts/ui-review/2026-09-16/` の97ファイル（8,678,201 bytes）は原位置に保持する。内容は実機画像24件、ログ53件、JSON記録19件、レビュー用作業コピー計画1件。端末識別子、セッション情報、画面の実データを含むため、生資料をソースコミットへ追加しない。
- `artifacts/git-review/2026-09-16/initial-content-manifest.json` に、開始時のUIソース8ファイルと上記資料97ファイルのSHA-256を記録する。これは同一性の検証記録であり、外部バックアップではない。
- `ios/build/` はXcodeの生成物として除外する。今回のSimulator向けビルド出力を保持し、既存資料の削除・移動は行わない。
- Gitへは表示改善のソースと、`docs/release/git-reconciliation-2026-09-16.md` の機密情報を含まない整理記録を保存する。過去ブランチは独自履歴と復元用途を確認して保持する。
