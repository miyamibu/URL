# 管理ユーザー一覧 本番反映手順

## 目的

所有者権限の管理者に限り、登録ユーザー数、メール、表示名、登録日、最終利用、現在プラン、状態、詳細、権限履歴を管理Webで確認できるようにする。TOTP追加認証後は、管理メモ、停止・BAN・再有効化、手動権限付与・取消も監査ログ付きで行えるようにする。

## 反映順序

1. `20260729150000_admin_user_directory.sql` を対象Supabaseへ適用する。
2. `admin_user_directory_validation.sql` を対象と同じスキーマ構成の検証DBで実行する。
3. Admin Webをstagingへデプロイする。
4. ownerでサインインし、総数、検索、状態フィルター、ページング、詳細、表示中CSVを確認する。
5. ownerでTOTP追加認証を行い、検証用ユーザーに対して管理メモ、停止、再有効化、手動権限付与・取消を確認する。BANは確認後の復旧経路がある検証用ユーザーだけを対象にする。
6. 各変更がユーザー詳細へ反映され、`admin_audit_logs` と管理画面の操作履歴に操作理由・対象・管理者・AAL2保証情報が記録されることを確認する。
7. billing / moderator / readonlyではユーザー一覧と管理操作が表示されないことを確認する。
8. 認証なしの `/api/admin/users` と `/api/admin/users/{id}` が401になることを確認する。
9. 問題がなければ本番Admin Webをデプロイし、`scripts/verify_admin_web_release.sh` を実行する。

## セキュリティ確認

- `admin_list_users`、`admin_get_user`、`admin_manage_user_audited` は `service_role` だけが実行できる。
- Admin WebはJWT検証後、ownerにだけ付与される `users.read` / `users.manage` capabilityを要求する。
- 更新系APIはAAL2のTOTP追加認証、操作理由、操作IDを必須とし、同一操作IDの再実行を拒否する。
- owner自身を停止またはBANする操作はDB関数側で拒否する。
- レスポンスは `Cache-Control: no-store` とし、ブラウザやCDNへ個人情報をキャッシュしない。
- CSVは現在画面に表示中の最大50件だけを出力し、サーバー側の一括エクスポートは提供しない。

## ロールバック

問題がある場合は、先にAdmin Webを直前のデプロイへ戻す。追加したDB関数は一般ユーザーから実行できず、既存データも変更しないため、緊急時は残置できる。関数削除が必要な場合は、別の承認済みロールバックマイグレーションとして実施する。
