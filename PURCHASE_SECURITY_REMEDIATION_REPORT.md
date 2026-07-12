# Purchase Security Remediation Report

## 1. 判定

- 実行対象: Google Drive の `prompt.docx`（C001 / C004 remediation）
- 実行日: 2026-07-12 JST
- Phase status: `PARTIAL_BLOCKED_EXTERNAL`
- Assurance: `NORMAL_MULTI_AGENT`
- Repository Release: `NO_GO`
- C001: Apple JWS の trust-chain 検証はソース上修正済み。正規 Apple sandbox/production JWS、Edge runtime、デプロイ後の動作は未検証。
- C004: 新規購入の Apple/Google account binding、provider transaction、owner/idempotency、StoreKit/Play 再確認経路はソース上修正済み。実ストア、通知、legacy 移行、DB race は未検証または未完了。
- 今回は C002 / C003 / C005 / C006 / C009 / C014 / C015 / C016 / C019 を実装しない指定を守った。

ローカルの実装・ビルド・静的検証は通過したが、外部ストア、Supabase linked/production DB、Edge deploy、実機、購入 sandbox の証跡がないため `COMPLETE_LOCAL` や公開 GO にはしない。

## 2. 開始・終了スナップショット

| 項目 | 開始 | 終了 |
|---|---|---|
| branch | `main` | `main` |
| HEAD | `6bba1af5b2c7fb5bcd5b22f824480a39343a7a87` | 同一 |
| upstream / merge base | `origin/main` と同一 / 差分 0 | 同一 |
| commit / push | 実施なし | 実施なし |
| tracked dirty | MetadataFetcher の Android/iOS 4ファイル | 既存差分を保持し、今回の購入修正を追加 |
| untracked | `MULTI_AGENT_REVIEW_REPORT.md` | 同ファイルを保持し、今回のレポート等を追加 |

開始時から存在した MetadataFetcher の変更と `MULTI_AGENT_REVIEW_REPORT.md` は今回の購入修正に混ぜず、戻していない。終了時の dirty 状態は意図した未コミット変更である。

## 3. C001 / C004 の再検証結果

### C001 Apple App Store JWS

- `npm:@apple/app-store-server-library@3.1.0` の `SignedDataVerifier` に置換し、JWS header の `x5c[0]` を直接 trust する旧経路を削除した。
- Apple PKI の Apple Root CA / G2 / G3 DER を固定し、実行時 SHA-256 integrity check を行う。
- `APP_STORE_ENVIRONMENT` は `Sandbox` / `Production` のみ許可し、Production では正の `APP_STORE_APPLE_ID` を必須化した。
- canonical Bundle ID `com.mibu.codebridge.ios` を設定値と照合し、誤設定を fail closed にした。
- SignedDataVerifier の bundle/environment 検証に加え、product、transaction ID、original transaction ID、auto-renewable type、revocation、未来の expiry、`appAccountToken == authenticated user UUID` を確認する。
- product は active な `public.subscription_products` の platform/product/plan/billing period と照合する。

### C004 購入主体・再利用・lifecycle

- iOS は購入時に Supabase user UUID を StoreKit `.appAccountToken` へ渡し、`Transaction.updates` と `Transaction.currentEntitlements` を backend 検証へ接続した。backend verified 後だけ transaction を finish する。
- Android は SHA-256(auth user ID) を `setObfuscatedAccountId` / `setObfuscatedProfileId` に設定し、Billing query を auth session 変更・再接続時に実行する。照合/query/ack の一時失敗は短い 3 回 retry とし、current purchases は照合完了を待つ。
- Google は `externalAccountIdentifiers` の obfuscated account/profile、product、ACTIVE/GRACE、必須かつ妥当な `expiryTime` を確認する。transaction ID は Google 応答の `latestSuccessfulOrderId`、旧 `latestOrderId` のみを採用し、caller supplied ID へ fallback しない。
- Apple は original transaction ID、Google は purchase token hash を subscription key として検証記録と grant を同一ユーザーへ冪等更新する。別ユーザーの verified replay は `purchase_already_claimed`（HTTP 409）で拒否する。
- 先行した `failed` 記録が別ユーザーの正規検証を塞ぐ poisoning を避け、failed 行は ownership lock に使わず、正規の verified 再試行で置換できるようにした。
- additive migration で `original_transaction_id` と `store_subscription_key`、重複事前検査、partial unique index を追加した。

## 4. 実装ファイルと変更理由

| ファイル | 変更理由 |
|---|---|
| `supabase/functions/verify-store-purchase/index.ts` | Apple trust chain、環境/bundle/product/state/account binding、Google provider/account/expiry/order 検証、owner/idempotency、safe failure logging/status を実装 |
| `supabase/functions/verify-store-purchase/apple-root-certificates.ts` | Apple PKI root DER と固定 SHA-256 manifest |
| `supabase/functions/verify-store-purchase/deno.lock` | Edge Function の npm 依存バージョン固定 |
| `supabase/functions/verify-store-purchase/index.test.ts` | root hash、policy helper、malformed JWS fail-closed、missing token の Deno 検証 |
| `supabase/migrations/20260712090000_purchase_security_binding.sql` | 既存履歴を変更しない additive schema/index/duplicate guard |
| `ios/URLSaveriOS/App/StoreKitPurchaseService.swift` | appAccountToken、Transaction.updates/currentEntitlements、verified 後 finish |
| `ios/URLSaveriOS/App/URLSaverAppModel.swift` | bootstrap/auth entitlement refresh から current entitlements を再検証 |
| `app/src/main/java/jp/mimac/urlsaver/billing/GooglePlayBillingService.kt` | obfuscated account/profile、query/reconnect/retry、照合完了後 ack |
| `app/src/main/java/jp/mimac/urlsaver/ui/SharedTagAuthViewModel.kt` | auth session 変更時の Play purchase recheck。成功後だけ同一ユーザーを記録 |

MetadataFetcher の Android/iOS source/test 4ファイル、`MULTI_AGENT_REVIEW_REPORT.md` は開始前からの既存変更として保存し、購入修正のためには編集していない。

## 5. Trust / identity / replay contract

| 面 | 信頼する値 | 拒否条件 |
|---|---|---|
| caller | Supabase `auth/v1/user` の authenticated user ID | bearer 欠落、auth 不一致 |
| Apple | Apple `SignedDataVerifier` が chain/root/environment/bundle を検証した payload | 自己提示 x5c、誤環境/bundle/product、token 欠落/不一致、revoked/expired |
| Google | Android Publisher API の response と `externalAccountIdentifiers` | account/profile mismatch、product/state/expiry/order 欠落・不一致 |
| client request | product と任意 transaction は照合用のみ | client request の transaction を provider 値の代替にしない |
| replay | Apple original transaction、Google token hash、subscription key、owner | 別 user、重複 grant、race 後の owner 不一致 |
| failure record | 監査用の failed 行 | failed 行を別 user の ownership lock にしない |

legacy 互換のため Google の旧 raw user UUID を一時的に受理する分岐は残している。旧 iOS purchase の appAccountToken 欠落、旧 Google purchase の obfuscated ID 欠落、旧 grant の backfill 方針は未定義であり、運用前に明示的な移行設計が必要である。Edge は現行の public verification/grant path を使用する一方、旧 private table/RPC 契約は残っているため、将来の webhook/reconciliation で source of truth を統一する必要がある。

## 6. 攻撃・失敗テストマトリクス

| ケース | 期待 | 実施結果 |
|---|---|---|
| malformed / self-signed App Store JWS | 502、verified/grant なし | Deno test PASS |
| Apple root manifest hash 改変 | verifier 初期化 fail closed | 3 root hash test PASS、runtime改変試験は未実施 |
| purchase token 欠落 | 400 `invalid_request` | Deno test PASS |
| unknown/inactive catalog product | grant なし | source実装済み、DB接続試験は未実施 |
| Apple account token mismatch | grant なし | source fail closed、正規JWS fixture未実施 |
| Apple revoked/expired/type mismatch | grant なし | source fail closed、sandbox未実施 |
| Google account/profile mismatch | grant なし | source fail closed、API response mock未実施 |
| Google expiry/order 欠落 | grant なし | source fail closed、API response mock未実施 |
| cross-user verified replay | HTTP 409、grant変更なし | source owner check、DB race/automated test未実施 |
| failed poisoning → legitimate verified retry | 正規 verified が通る | source修正済み、専用test未実施 |
| same-user resend | 同じ verification/grant を refresh | source key/lookup実装済み、DB race未実施 |
| iOS updates/current entitlements | backend verified 後 finish | build/XCTest PASS、StoreKit sandbox未実施 |
| Android query/reconnect/ack failure | retry、未ackを保持 | build PASS、Play実機未実施 |
| migration duplicate guard/index | duplicate なら apply abort | SQL source確認のみ、DB apply未実施 |

## 7. 検証ログ

| コマンド | 結果 | 制限 |
|---|---|---|
| `deno check --lock=... index.ts index.test.ts` | PASS | Edge runtime/OCSP通信は未確認 |
| `deno test --allow-env --allow-net --lock=... index.test.ts` | 4 passed / 0 failed | provider positive、DB raceなし |
| `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` | `BUILD SUCCESSFUL`、108 actionable tasks | connected/device/cold startなし |
| `xcodebuild ... -scheme URLSaveriOS -configuration Release CODE_SIGNING_ALLOWED=NO ... build` | `BUILD SUCCEEDED` | signing/archive/store uploadなし |
| `xcodebuild ... -destination 'platform=iOS Simulator,id=4F6815BF-8D89-4F96-BB88-B005E35F7AE9' ... test -only-testing:URLSaveriOSTests` | 114 tests、3 skipped、0 failures | simulatorのみ、StoreKit sandbox/physical iPhoneなし |
| `bash scripts/check_release_hygiene.sh` | PASS | external/store stateなし |
| `bash scripts/check_launch_readiness.sh` | PASS | migration replay/実運用証跡ではない |
| `git diff --check` | PASS | commit/pushは未実施 |
| Apple root SHA-256 `deno eval` | Apple Root CA / G2 / G3 全て一致 | runtime certificate downloadなし |
| `pg_isready -h localhost -p 54322` | no response | Supabase local DBなし |
| `supabase status` | Docker daemon unavailable | migration lint/apply未実施 |

`connectedDebugAndroidTest`、physical Android/iPhone、`supabase db reset`/migration apply、production/linked DB、Edge deploy、Apple/Google purchase sandbox、store console、secrets投入は実行していない。

## 8. Wave 2 独立監査

- Auditor D（current verifier）: 読み取り専用。Apple official verifier/root、environment/bundle、account binding、replay/owner、migration の source-level fix を確認。canonical bundle、legacy、通知/reconciliation、private/public dual contract、runtime/DB/store を未検証として残した。
- Auditor E（probable verifier）: 読み取り専用。Android/iOS lifecycle、Google provider order/expiry、failed-row poisoning、HTTP 409、retry 到達範囲を再確認。failed poisoning、Google expiry/order、canonical bundle、HTTP 409 は再修正後に source-level Fixed。StoreKit/Play、DB race、cache/UI propagation、legacy、notification は未検証/未完了とした。
- 両監査ともコード編集、テスト実行、外部書込みを行わず、開始/終了 HEAD は同一だった。

## 9. 実施していない外部・破壊的操作

- Supabase linked/production migration、DB push、RLS/RPC変更、Edge deploy は未実施。
- Apple App Store Connect / Google Play Console、upload、review、公開、実購入、refund/revoke、server notification 設定は未実施。
- production secret、service account、Apple ID、購入JWS/tokenの値は取得・表示・保存していない。
- commit、push、branch作成、merge/rebase/reset/clean は未実施。
- 通常端末の data reset、uninstall/reinstall、connected instrumentation、物理 iPhone/Android 操作は未実施。

## 10. 残リスクと次に必要な条件

1. Apple sandbox の正規 JWS（正しい/偽 root、誤 bundle/environment、account mismatch、renewal、revocation、expiry）を使った staging positive/negative test。
2. Google Play internal test の account binding、renewal/token replacement、active/grace/canceled/on-hold、wrong user、expiry/order 欠落、ack retry test。
3. linked DB の schema/index/RLS と同一 user 再送・別 user replay・同時 race を、C002 replay blocker 解消後の disposable DB で検証。
4. Apple Server Notifications / Google RTDN または定期 reconciliation、refund/revoke/renewal の grant 状態同期。
5. 旧購入の appAccountToken/obfuscated ID と旧 grant の安全な backfill/manual recovery 方針。`original_transaction_id`/subscription key の legacy backfillを決める。
6. public Edge path と private verification/RPC path の canonical source-of-truth を一つにする。
7. Android 購入完了後と iOS Transaction.updates 成功後に entitlement cache/UI を即時再取得する callback、foreground/restore trigger、重複 query 抑制を追加検討する。
8. remote failure 時の7日 paid cache fallbackが revoke/expiry 後の権限を残す可能性を、fail-closed 方針とともに仕様化する。

## 11. 次の remediation（C002、今回は未実装）

`supabase/migrations/20260627103000_plan_limit_policy.sql` は `manual single-file apply from ...;` という非SQL placeholder 1行であり、fresh migration replay を停止させる。適用済み本番履歴から正確なSQLを復元し、checksum/履歴を壊さない additive migration または明示的な履歴修復方針を決め、破棄可能なDBで全replayとlinked schema diffを行う必要がある。今回の指定どおり、このファイルは編集・削除していない。

## 12. 差分の境界

- 今回の意図的差分は購入検証 Edge Function、Apple root manifest/lock/test、additive migration、iOS StoreKit lifecycle、Android Play binding/recheck/retry、関連 auth hook に限定した。
- 既存の MetadataFetcher 差分と `MULTI_AGENT_REVIEW_REPORT.md` は保存し、無関係な修正や全面置換を行っていない。
- UI変更、依存追加以外のリファクタリング、秘密値、実購入データ、JWS本文、token値は追加していない。
- 未コミット・未デプロイのため、現時点の公開/本番判定は `NO_GO` のままである。
