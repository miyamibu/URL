# Entitlement Foundation

## Goal
Supabase が `pro` grant を返したとき、Android / iOS の両方で Pro ユーザーとして扱える土台を作る。

## Phase A: Local Domain
- Android は既存の `PlanType`, `FeatureEntitlements`, `LimitChecker`, `EntitlementResolver` を継続利用する。
- iOS は Android と同じ概念を `Models.swift` に持つ。
- `free` / `launch_standard` は Supabase の既定値ではなく、Resolver fallback として扱う。
- Resolver は active かつ未期限切れの grant だけを見る。`revoked`, `pending`, expired は Pro 扱いしない。
- 複数 grant がある場合は `promo_pro`, `pro`, `launch_standard`, `free` の順に高い plan を優先し、同一 plan では source 優先度と開始日時で決める。

## Phase B: Supabase Grants
- 正本は `public.user_entitlement_grants`。
- 1 user 1 row ではなく、1 user 複数 grant を保存できる。
- `source` に `local_default` は入れない。
- `status` は `active`, `revoked`, `pending` のみ。expired は `expires_at < now()` で判定する。
- receipt / purchase token / webhook raw payload はこのテーブルに入れない。将来必要なら private/audit table に分離する。
- RLS は本人 SELECT のみ許可する。通常クライアントの INSERT / UPDATE / DELETE は許可しない。
- クライアント読み出しは `get_my_entitlement_grants()` RPC を使い、active かつ未期限切れの本人 grant だけを返す。

## Phase C: Client Fetch And Cache
- Android は既存 Supabase 設定と auth session を使って `get_my_entitlement_grants()` を読む。
- iOS も同じ RPC を読み、Android と同じ Resolver に流す。
- 起動時または session 変化時に再取得する。
- 取得成功時は last-known grants を保存する。
- 取得失敗時は同じ auth user の last-known grants を使う。
- cache TTL / grace period は 7 日。TTL を超えた cache は使わず LaunchStandard fallback に戻る。
- サインアウト時や session なしでは remote/cache grant を使わず LaunchStandard fallback に戻る。

## Phase D: Store Billing Integration
- Google Play Billing は Android client で購入開始と purchase token 取得を担当する。
- StoreKit は iOS client で購入開始と transaction/JWS 取得を担当する。
- receipt / purchase token 検証、grant 作成は Supabase Edge Function `verify-store-purchase` に寄せる。
- purchase token / transaction JWS の raw payload は保存せず、検証履歴には token hash と transaction ID を保存する。
- Google Play RTDN / Apple App Store Server Notifications V2 の受信・検証・durable反映契約は `store-notification-receiver` と後続migration `20260727110000_store_notification_durable_contract.sql` に追加した。受信通知だけでは新規grantを作らず、既存の購入verification/grantとのbindingが確認できた場合だけ状態を記録する。
- クライアントは引き続き Supabase の grant を読むだけで、自分を Pro にする直接書き込み権限を持たない。

## Phase E: Durable Store Notifications

- Apple受信はApp Store Server Notifications V2の`signedPayload`をApple公式`SignedDataVerifier`で検証し、bundle ID、environment、transaction/product、original transaction、`appAccountToken`のuser bindingを確認する。どれかが確認できない場合はgrantを変更しない。
- Google受信はCloud Pub/Sub pushのOIDC JWTを署名・issuer・audienceで検証し、push設定の期待service account emailとの一致と`email_verified=true`も必須にする。RTDN payloadのpackage name、subscription product、purchase token bindingを確認した後、subscription通知ではraw purchase tokenをhash以外に永続化せず一時的にGoogle Play Developer API `purchases.subscriptionsv2.get`へ照会する。APIの`kind`、`subscriptionState`、matching `lineItems[].productId`、`expiryTime`、order IDを検証してcommandへ反映し、credential未設定、API失敗、product/state/expiry不一致ではgrant RPCを呼ばずfail-closedとする。
- `public.store_subscription_notification_events`はprovider notification IDとprovider event ID/typeを一意化し、署名済みイベント、処理結果、失敗理由をraw signed payloadなしで保存する。`public.store_subscription_states`はprovider subscription keyごとの最新イベント時刻を保持し、古い通知が新しい状態を巻き戻さない。
- `public.apply_store_subscription_notification`はservice role専用で、既存の`store_purchase_verifications`と`user_entitlement_grants`に一致する場合だけ反映する。refund/revoke/expiredだけが通知単独でgrantをrevokedへ変更し、更新・billing通知は権威ある期限がなければgrantを変更しない。
- `store-entitlement-reconciliation`は既存のevent ID replayを維持する。現在状態の能動再照合では、Googleは一時的なpurchase tokenをEdge内でSHA-256化して保存済み`purchase_token_hash`と先に照合し、raw tokenはDB・ログ・結果へ渡さない。照合後にGoogle Play Developer API `purchases.subscriptionsv2.get`を呼び、snapshotをprovider-aware RPC経由でdurable reconciliation event、subscription state、既存grantへ反映する。Appleは一時`signedTransactionInfo`の署名・transaction/user bindingを確認したうえで、App Store Server APIの`Get All Subscription Statuses`をES256 JWTで呼び、responseのbundle/environment/app identityと各`lastTransactions[].signedTransactionInfo`を検証する。status、権威期限、revokeを正規化し、raw JWSは保存せずSHA-256だけを追加RPCへ渡す。
- 本番設定では`APP_STORE_ENVIRONMENT`/`APP_STORE_BUNDLE_ID`/`APP_STORE_APPLE_ID`、Apple Server API用の`APP_STORE_SERVER_API_ISSUER_ID`/`APP_STORE_SERVER_API_KEY_ID`/`APP_STORE_SERVER_API_PRIVATE_KEY`、`GOOGLE_PLAY_PACKAGE_NAME`（必ず`jp.miyamibu.urlalbum`）/`GOOGLE_RTDN_PUSH_AUDIENCE`/`GOOGLE_PUBSUB_EXPECTED_SERVICE_ACCOUNT_EMAIL`、Google Play Developer API用の`GOOGLE_PLAY_SERVICE_ACCOUNT_CLIENT_EMAIL`/`GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY`（任意の`GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY_ID`）、`STORE_RECONCILIATION_SECRET`をsecret managerまたは非秘密設定へ登録する。`APP_STORE_APPLE_ID`は既存の購入検証ではProduction時に必須だが、現在状態のServer API照合ではSandboxでもresponseのapp identityを検証するため必須とする。private keyはログへ出力しない。OIDC JWKS URLを差し替える場合だけ`GOOGLE_PUBSUB_OIDC_JWKS_URL`を使い、endpointのprovider署名/OIDC検証と矛盾しないよう外部gateway設定で確認する。値の登録・endpoint公開・実通知送信・Google/Apple API接続は未実施である。
- 契約fixtureは`supabase/functions/store-notification-receiver/index.test.ts`、`supabase/functions/store-entitlement-reconciliation/index.test.ts`、`supabase/tests/store_notification_durable_validation.sql`、`supabase/tests/store_apple_active_reconciliation_validation.sql`に置く。テストではApple/Google外部APIを呼ばず、API response/JWT/JWS検証はfixtureで確認する。

## Debug Override
- Android は debug source set の `BuildVariantEntitlementOverrides` だけが override を返す。`BuildConfig.DEBUG` でもガードする。
- iOS は `#if DEBUG` 内だけで `UserDefaults` の debug override key を読む。
- Supabase は debug override に関与しない。
- release build では override は空になる。

## Out Of Scope
- Apple/Google Consoleでの通知 endpoint・Pub/Sub topic・OIDC audience・environmentの本番登録と疎通
- Store Sandbox/TestFlight/Play Consoleの実通知、返金、更新、billing retry、reconciliationの外部E2E
- Google Developer APIの本番credential登録、Sandbox/本番RTDN、実購入・更新・返金によるreconciliationの外部E2E
- ストア側での subscription product / 価格 / 審査文言の最終登録
- 広告 SDK 追加や UI デザイン変更

## Validation
- Android unit tests: Resolver, LimitChecker, active/revoked/pending/expired/multiple grants, debug override。
- iOS tests: Android と同等の entitlement domain、LimitChecker、last-known cache。
- Supabase validation: own SELECT, client write denial, RPC active non-expired filtering、store purchase verification table RLS、Edge Function type check。
