# Admin AAL2 and idempotency cutover

管理画面の高リスク操作をAAL2/TOTP、監査operation ID、冪等性で保護する。秘密値、deploy ID、storage stateはこの文書やGitへ保存しない。

## 固定順序

1. staging DBで本番適用済み履歴の原文2本を確認する。
   - `20260727090000_admin_aal2_audit_guard.sql`
   - `20260727150000_admin_operation_idempotency.sql`
2. 修正版Admin Webをstagingへdeployする。`mfa/totp`、期限切れ再challenge、capability別表示、operation ID再利用、409既存状態を確認する。
3. stagingで `20260728153000_admin_operation_idempotency_privileges.sql` を適用する。
4. stagingで `20260728160000_admin_operation_idempotency_scope.sql` と
   `20260728161000_admin_bootstrap_first_admin.sql` を適用する。
5. Web typecheck、auth unit test、admin pgTAP、`/`を対象にしたPlaywright E2Eを実行する。
6. 本番では、修正版Admin Webを先にdeployし、旧Webでも利用する
   `SELECT/INSERT/UPDATE` だけを残した状態で読み取りを確認する。
7. 修正版Webのhealthと読み取りを確認してから、1530/1600/1610の
   forward migrationを順番どおり本番適用する。

固定Webは1530適用前後、1600適用前後の両方を扱える。1530は旧Webが使う
`SELECT/INSERT/UPDATE`を維持し、1600でsupport/moderation/revokeのoperation名を
許可する。1610は初回owner登録の競合を閉じる。既存の2 migrationは編集しない。

## 必須確認

- owner: 全管理機能を表示し、高リスク操作は有効なAAL2だけで成功する。
- billing: 優待コード、ユーザー検索、監査だけを利用できる。
- moderator: サポート、モデレーション、監査だけを利用できる。
- readonly: 優待、サポート、モデレーション、監査を読み取れるが変更できない。
- JWTのAMR `mfa/totp` を認識し、TOTP確認から15分後は再challengeを要求する。
- 結果不明後の再試行は同じoperation IDを送り、二重mutationではなく409と既存状態になる。
- 監査画面にoperation ID、phase、assuranceが表示される。
- エラー応答にDB、provider、token、secretの生メッセージが出ない。

## 検証コマンド

```bash
cd web/admin
npm run typecheck
npm run lint
npm run build
node --test lib/auth-policy.test.mjs

cd ../..
python3 -m unittest scripts/test_verify_admin_e2e.py
bash scripts/verify_admin_e2e.sh --dry-run --json
```

Playwright実行時の既定保護画面は `/` である。認証済みstorage stateと外部TOTPは運用担当者が安全な場所から与え、runnerにログインやTOTP登録をさせない。

## ロールバック

- Webの異常: 直前のAdmin Web deploymentへ戻す。ただし新規高リスク操作を一時停止してから行う。
- 権限migration後の異常: `DELETE/TRUNCATE/TRIGGER`を戻さず、必要な非破壊権限だけを新しいforward migrationで追加する。
- migration履歴を書き換えたり、既存の2 migrationを編集したりしない。
- operation IDの一意制約は二重操作防止の証跡なので、削除ではなく原因を調べてforward修正する。

## 外部ゲート

本番deploy、本番migration適用、実管理者AAL2セッション、メール送信、Supabase/Vercel設定は外部操作である。各証跡がない限り本番運用は`NOT_VERIFIED`とする。
