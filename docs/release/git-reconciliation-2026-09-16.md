# 2026-09-16 Git整理・検証記録

## 対象と変更内容

- 開始時の `main` / `origin/main`: `6b75e2555cc0446141260ba1f2db7cc134e36e1d`。`git fetch --no-prune origin` と `git ls-remote --heads origin` で照合した。
- UIソースコミット: `40c21d400af5d4d3f37eb35dc466e2974de251f8`。
- Android 5ファイル、iOS 3ファイルの既存未コミット差分を全件確認した。カードの余白・文字階層、タグのタップ領域とアクセシビリティラベル、詳細メタデータのアイコン、詳細タグ欄の可変高さ、iOSの大きな文字での下部ラベル維持が対象。
- URL正規化、重複判定、保存・削除、DB schema、共有・課金ロジック、Web、Supabase、サーバー、リリース番号には今回の差分がない。整理中に8ソースファイルの内容を変更していない。
- 開始時の8ソースと97検証資料、計105ファイルはSHA-256を記録し、コミット前に105/105一致を確認した。原資料の削除・移動はしていない。

## ブランチと作業フォルダ

数値は整理開始時の `main` との比較。左がmain側の独自コミット、右が対象側の独自コミット。

| ブランチ | 先端 | main / 対象 | 判定 |
|---|---|---:|---|
| `codex/修正` | `a56d73c0` | 40 / 13 | 過去のUI・共有・metadata・文書変更。公開機能の選択的統合は9月14日に記録済み。現在との差を一括マージすると異なる実装・旧文書へ戻るため保存。ローカルとoriginの先端一致。 |
| `codex/preserve-review-136b-20260914` | `15ca607c` | 40 / 1 | 118ファイルを含む過去レビューの保存スナップショット。独自履歴と復元用途があり保存。作業フォルダはclean、originと一致。 |
| `codex/preserve-release-1.0.20-20260914` | `142e3530` | 24 / 1 | 49ファイルを含む旧公開版1.0.20の保存スナップショット。現行1.0.22への一括復元を避けて保存。作業フォルダはclean、originと一致。 |
| `origin/codex/release-hardening-20260728` | `16d5235e` | 87 / 4 | 129ファイルにまたがる旧hardening系統。独自履歴があり保存。現行backendへ一括反映しない。 |

- ローカルの通常ブランチは `main` を含め4本、リモートは上記hardeningを含め5本。削除・rebase・force pushは行わない。
- `web/usage-guide` は独立したGitリポジトリ。`main` / `dde36e2`、clean、remote未設定。親リポジトリのpushへ混ぜない。
- 既に実体のない一時release worktreeの登録1件が `prunable` と表示される。HEADは `119cb067`。今回の整理は復元情報を破棄せず、登録を保持する。現存する3作業フォルダとは区別する。

## 今回の検証

| 確認 | 結果 |
|---|---|
| `python3 scripts/verify_mobile_ui_contract.py` | PASS |
| `python3 scripts/verify_release_manifest.py` | PASS、Android/iOSは1.0.22 (37)のまま |
| `bash scripts/check_release_hygiene.sh` | PASS |
| Java 21 `./gradlew --console=plain assembleDebug testDebugUnitTest lintDebug` | PASS。58タスク中55はUP-TO-DATE。対応するXML結果は500テスト、失敗0、エラー0、skip 0。今回500件を新規実行したという意味ではない。 |
| iOS Debug、generic iOS Simulator、署名なしbuild | PASS。AppIntents.framework未使用によるmetadata抽出skip警告のみ。ローカルではXCTestを再実行していない。 |
| UI差分 `git diff --check` / staged gitleaks | PASS、秘密情報検出なし |

ログ・ハッシュ一覧・ブランチ比較はローカルの `artifacts/git-review/2026-09-16/` に保持する。リモートCIの結果はpush後に対象SHAを指定して確認し、ローカルの最終receiptへ記録する。

## デプロイと公開の境界

- 公開Web `https://miyamibu.xyz` は `verify_public_web_release.sh` PASS。privacy/account-deletion/reset/invite、Android App Links、iOS Universal Links、reset CSPを確認した。
- 管理Web `https://rinbamu-admin.vercel.app` は `verify_admin_web_release.sh` PASS。管理API6経路の未認証GETは401、無効なMCP経路は404。
- Railwayの `/health` はHTTP 200 / `ok: true`。GitHubに記録された最新deploymentは `6097436869`、source `6b2ddc30`、状態success。これ以降の差分にbackendの変更はなく、関連scriptsの変更はモバイルUI契約の検証のみ。health応答にはsource versionがなく、稼働バイナリとGit SHAの一致までは証明しない。
- Renderの `/health` は最初の30秒リクエストでタイムアウトし、その後の確認でHTTP 200 / `ok: true`。応答versionは開始時mainの `6b75e2555cc0446141260ba1f2db7cc134e36e1d` と一致した。確認時刻は2026-09-16 12:46 UTC。
- 今回の変更にWeb・backend・DBの新しいデプロイ対象はないため、手動再デプロイやmigrationは実施しない。Git連携による自動デプロイの状態はpush後の記録と区別する。
- モバイルの新しいUIコミットは今回Storeへアップロード・審査提出していない。9月14日の提出受付は旧ソースに対する記録であり、今回UIの配布証明や現在の審査・公開状態の証明には使わない。

## 既存UI検証資料の扱い

- 9月16日の既存資料には、canonical iOSアプリのWDAによる使い方ページ（3セクション）・戻る・検索・AI選択・スクロールの記録がある。初期のメニュー／検索の失敗と後続の成功を区別して読んだ。
- 詳細タップとVoiceOverの資料にはiPhone Mirroring / AccessibilityAuditの記録が含まれ、WDA操作証拠と同等には扱わない。音声録音はない。今回のGit整理中には実機・VoiceOver操作を再実行していない。
- Android実機の今回再検証は未実施。静的契約やビルド成功を実機操作成功へ読み替えない。
- 既存Kimi K3レビューは送信後300秒で `transport_failure / TimeoutError`、出力本文なし。レビュー結果は未取得であり、PASSとして扱わない。今回の整理依頼では再送していない。
- 原資料97件は機密性のある端末／セッション情報を含むためローカルに保存し、Gitにはこの要約と保管方針だけを含める。ignoreはバックアップや削除許可を意味しない。
