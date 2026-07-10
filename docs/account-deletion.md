# Account Deletion Notes

## Goal

- shared-tag cloud / account creation を公開面に出す前に、アプリ内削除導線と Google Play 向けの web deletion route の両方を整理する。

## Current repo state

- 提出版 `1.0.11` は shared-tag cloud / account sign-in / Supabase sync を公開面に出すため、ストア提出上の account deletion URL が必要。
- Android shared-tag cloud auth UI には in-app の `アカウント削除` 導線を追加した。
- iOS shared-tag cloud シートにも in-app の `アカウント削除` 導線を追加した。
- Supabase 側には `delete_my_account()` RPC を追加した。
- この RPC は次の安全条件で動作する。
  - 通常メンバーは自分の membership を外して auth user を削除できる。
  - 自分だけが所属する owner shared tag は server-side cleanup の対象にできる。
  - 他の active member がいる shared tag の owner は、先に `transfer_shared_tag_ownership()` で owner を移譲する必要がある。
  - 削除アカウントの `created_by` / `added_by` が残る共有タグデータは、残存する current owner に寄せてから auth user を削除する。

## Google Play web route

- `web/invite-link/account-deletion/index.html` を public HTTPS URL として配信し、app listing / data safety の削除 URL として設定する。
- 正本 URL は `https://miyamibu.xyz/account-deletion/` を想定する。
- 2026-06-27 時点では live URL が 404 のため、`web/invite-link` のデプロイと再確認が提出前ブロッカー。

## Remaining release work

- Android release は `release.supabase.url` / `release.supabase.anon.key` / `release.shared.tag.cloud.enabled=true`、または対応する `URLSAVER_RELEASE_*` 環境変数で cloud を有効化する。
- iOS release は `URLSAVER_SHARED_TAG_CLOUD_ENABLED=true`、production Supabase URL / anon key、contact-support endpoint を Info.plist に反映させる。
- iOS / Android の shared-tag cloud を public release で有効化する前に、実運用用の Supabase 設定、store privacy answers、public web deletion route の公開をそろえる。
- owner with active members の削除は引き続きブロックされるが、アプリ内の owner 移譲後は削除できる。
- `delete_my_account()` を production で有効にする前に、Supabase project 側で migration 適用と public web deletion route の配信を完了する。
