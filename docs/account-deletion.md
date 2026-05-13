# Account Deletion Notes

## Goal

- shared-tag cloud / account creation を公開面に出す前に、アプリ内削除導線と Google Play 向けの web deletion route の両方を整理する。

## Current repo state

- 初回ストア公開方針は local-only。v1.0 では shared-tag cloud / account creation / login / Supabase sync を公開面に出さないため、ストア提出上の account deletion URL は `NOT_APPLICABLE` とする。
- Android shared-tag cloud auth UI には in-app の `アカウント削除` 導線を追加した。
- iOS shared-tag cloud シートにも in-app の `アカウント削除` 導線を追加した。
- Supabase 側には `delete_my_account()` RPC を追加した。
- この RPC は次の安全条件で動作する。
  - 通常メンバーは自分の membership を外して auth user を削除できる。
  - 自分だけが所属する owner shared tag は server-side cleanup の対象にできる。
  - 他の active member がいる shared tag の owner は、先に `transfer_shared_tag_ownership()` で owner を移譲する必要がある。
  - 削除アカウントの `created_by` / `added_by` が残る共有タグデータは、残存する current owner に寄せてから auth user を削除する。

## Google Play web route

- local-only v1.0 では account creation を公開しないため、Google Play の削除 URL は不要。
- shared-tag cloud / account creation を v1.1 以降で公開する前に、`docs/account-deletion-request.html` を静的公開して app listing / data safety の削除 URL として設定する。
- この HTML は repo 内の source-of-truth であり、運用環境では public HTTPS URL として配信する。
- repo には `.github/workflows/account-deletion-page.yml` を追加してあり、GitHub Pages を有効化すれば `docs/account-deletion-request.html` を `/account-deletion/` に配信できる。

## Remaining release work

- Android release で shared-tag cloud を公開する場合は、`release.supabase.url` / `release.supabase.anon.key` / `release.shared.tag.cloud.enabled=true`、または対応する `URLSAVER_RELEASE_*` 環境変数を設定して cloud を有効化する。
- 契約前の local-only release build では `release.shared.tag.cloud.enabled` を未設定または `false` にし、cloud / account creation を公開面に出さない。
- local-only v1.0 の iOS release では `URLSAVER_SHARED_TAG_CLOUD_ENABLED=false`、`URLSAVER_SUPABASE_URL=""`、`URLSAVER_SUPABASE_ANON_KEY=""` を維持する。
- iOS で shared-tag cloud を公開する将来版では `ruby ios/generate_xcodeproj.rb` 実行前に cloud 有効フラグと production Supabase URL / anon key を設定して Info.plist へ反映させる。
- iOS / Android の shared-tag cloud を public release で有効化する前に、実運用用の Supabase 設定と web deletion route の公開をそろえる。
- owner with active members の削除は引き続きブロックされるが、アプリ内の owner 移譲後は削除できる。
- `delete_my_account()` を production で有効にする前に、Supabase project 側で migration 適用と public web deletion route の配信を完了する。
