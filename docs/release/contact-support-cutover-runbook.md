# 問い合わせ送信 cutover runbook

## Goal

問い合わせをアプリから同期送信せず、暗号化 outbox に受付して worker が Resend へ送信する。ユーザーにはメール送信完了ではなく「問い合わせを受け付けました」と表示する。

## 必須設定（値はチャットへ貼らない）

Supabase Functions の secrets に、公式ダッシュボードから次を登録する。

- `CONTACT_SUPPORT_WORKER_SECRET`：worker 呼び出し用の十分な長さのランダム値
- `CONTACT_SUPPORT_OUTBOX_ENCRYPTION_KEY`：AES-256 用の 32 bytes。64 桁 hex または base64url
- `RESEND_WEBHOOK_SECRET`：Resend Webhook の signing secret

既存の `CONTACT_RATE_LIMIT_SALT`、`RESEND_API_KEY`、`CONTACT_TO_EMAIL`、`CONTACT_FROM_EMAIL`、`SUPABASE_DB_URL`、`SUPABASE_SERVICE_ROLE_KEY` は値を表示せず、存在だけ確認する。

## 一括 cutover

1. `20260726190000_contact_support_idempotent_outbox.sql`、`20260727100000_contact_support_outbox_worker.sql`、`20260727120000_contact_support_outbox_dead_letter.sql`、`20260728100000_contact_support_health_schedule.sql` の順で、問い合わせ関連だけを適用する。
2. `contact-support-outbox`、`contact-support-resend-webhook`、`contact-support` の順でデプロイする。公開関数は Supabase Gateway JWT を無効化し、worker は worker secret と service role の二重認証、Webhook は Svix 署名を検証する。
3. Resend の Webhook URL を `https://xocumgxbylmpoobfqows.supabase.co/functions/v1/contact-support-resend-webhook` に設定し、`email.sent`、`email.delivered`、`email.delivery_delayed`、`email.bounced`、`email.failed`、`email.suppressed` を有効にする。
4. Supabase Vault に cron が参照する `SUPABASE_SERVICE_ROLE_KEY` と `CONTACT_SUPPORT_WORKER_SECRET` を登録する。cron は毎分 worker を呼び、毎日 03:17 に dead-letter の 7 日超の本文を消去する。

## Canary の合格条件

- `GET /functions/v1/contact-support` が個人情報なしで `200 {"status":"ok"}` を返す。
- 匿名 `POST` が `202 {"status":"accepted","requestId":"UUID"}` を返す。
- 同じ `Idempotency-Key` の再送が同じ request ID を返し、メールが二重送信されない。
- outbox の payload が暗号 envelope であり、送信成功後 `{}` に scrub される。
- worker heartbeat が 5 分以内に更新される。
- Resend Webhook の署名検証後、配信状態が監査行へ反映される。
- サポート受信箱に実メールが 1 通届く。
- Android 自動テストと iOS Simulator の問い合わせ画面で「問い合わせを受け付けました」が表示される。

## rollback

cron を停止して暗号化 queue を保持する。必要な場合だけ、JWT 無効化済みの直前 Function を再デプロイする。本文や監査行の削除、DB の drop、履歴改変は行わない。復旧後は同じ idempotency key の再送で二重送信を防ぐ。

## 証跡

本番確認では、Function metadata、migration/RPC の存在、heartbeat、outbox scrub、Resend message ID、受信箱の受信時刻を個人情報を伏せた形で記録する。Android 実機と iPhone 実機を確認していない場合は、それぞれ `NOT VERIFIED` と報告する。
