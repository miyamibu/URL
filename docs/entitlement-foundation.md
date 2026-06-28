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
- Google Play RTDN / Apple App Store Server Notifications による継続更新・失効反映は次段階で backend に追加する。
- クライアントは引き続き Supabase の grant を読むだけで、自分を Pro にする直接書き込み権限を持たない。

## Debug Override
- Android は debug source set の `BuildVariantEntitlementOverrides` だけが override を返す。`BuildConfig.DEBUG` でもガードする。
- iOS は `#if DEBUG` 内だけで `UserDefaults` の debug override key を読む。
- Supabase は debug override に関与しない。
- release build では override は空になる。

## Out Of Scope
- Google Play RTDN / Apple App Store Server Notifications の本番 webhook
- ストア側での subscription product / 価格 / 審査文言の最終登録
- 広告 SDK 追加や UI デザイン変更

## Validation
- Android unit tests: Resolver, LimitChecker, active/revoked/pending/expired/multiple grants, debug override。
- iOS tests: Android と同等の entitlement domain、LimitChecker、last-known cache。
- Supabase validation: own SELECT, client write denial, RPC active non-expired filtering、store purchase verification table RLS、Edge Function type check。
