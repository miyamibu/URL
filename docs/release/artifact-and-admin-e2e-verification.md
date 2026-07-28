# Release artifact and admin E2E verification

この契約は、既存のビルド・管理画面・CIを変更せずに、成果物と任意の管理画面E2Eを機械判定するための入口である。署名、外部ログイン、Store提出、deployは実行しない。

## 判定値と終了コード

| 判定 | 意味 | 終了コード |
| --- | --- | ---: |
| `PASS` | 対象の確認を実行し、契約を満たした | `0` |
| `FAIL` | 対象は存在するが、unsigned・署名不正・E2E失敗などを確認した | `1` |
| `NOT_VERIFIED` | 対象、検証ツール、ブラウザ、storage state、設定が不足して確認できない | `2` |

`NOT_VERIFIED` は成功ではない。release判定に混ぜず、必要な外部証跡を補って再実行する。

## Android / iOS成果物

既に作成済みのファイルだけを明示して実行する。

```bash
bash scripts/verify_release_artifacts.sh \
  --apk app/build/outputs/apk/release/app-release.apk \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --expected-android-cert-sha256 "$PLAY_APP_SIGNING_CERT_SHA256" \
  --xcarchive /secure/path/URLSaveriOS.xcarchive \
  --require-ios-distribution \
  --expected-ios-team-id "$APPLE_TEAM_ID" \
  --require-ios-share-extension \
  --json
```

- Androidの固定identityはpackage `jp.miyamibu.urlalbum`である。APK/AABは署名前にZIP内の`AndroidManifest.xml`（binary XMLまたはfixture XML）を読み、別package・manifest欠落・identityを読めない成果物を`FAIL`とする。
- Androidのrelease検証は、`--expected-android-cert-sha256`を指定しない限り`NOT_VERIFIED`となる。`--require-android-signing-identity`は後方互換の明示指定として残している。packageだけを確認する非release検査では`--allow-android-signing-identity`を指定できるが、release判定に使ってはならない。Play App Signingまたは今回の配布対象で使うfingerprintは外部Consoleから安全に渡し、リポジトリへ保存しない。
- APKはpackage一致後に`apksigner verify --verbose --print-certs`を実行し、v1〜v4のいずれかの署名schemeが`true`でなければ`FAIL`とする。`apksigner`またはJava runtimeが無い場合は`NOT_VERIFIED`とする。
- AABはpackage一致後、ZIP内の`META-INF/*.SF`と署名ブロックを確認し、`jarsigner -verify -strict`を実行する。unsigned、署名不正、package不一致は`FAIL`、`jarsigner`またはJava runtimeの不足は`NOT_VERIFIED`とする。
- iOSの固定main bundle identityは`com.mibu.codebridge.ios`である。XCARCHIVE/IPAは各`.app`の`Info.plist`を読み、別bundle・欠落・未解決の`CFBundleIdentifier`を`FAIL`とする。release検証では`--expected-ios-team-id`も必須で、未指定の署名済み成果物は`NOT_VERIFIED`となる。
- `PlugIns/*.appex`が含まれる場合はshare extension bundle `com.mibu.codebridge.ios.share`も検査し、main appと同じ`TeamIdentifier`であることを要求する。`--require-ios-share-extension`指定時はextension欠落も`FAIL`とする。特定Team IDをさらに固定する場合は`--expected-ios-team-id TEAM_ID`を指定する。
- XCARCHIVE/IPAは`_CodeSignature/CodeResources`、`codesign --verify --deep --strict`、証明書Authority、TeamIdentifierを確認する。`--require-ios-distribution`指定時はApple/iPhone Distribution Authorityと`--expected-ios-team-id`を要求し、Team ID未設定のままでは`NOT_VERIFIED`とする。`codesign`が無い場合は`NOT_VERIFIED`、unsigned・署名不正・identity mismatchは`FAIL`とする。
- IPA内に署名された`.app`が無い場合は`FAIL`とする。

検証を行わない確認計画だけを表示する場合も、終了コードは`2`である。

```bash
bash scripts/verify_release_artifacts.sh --dry-run --json
```

## 任意の管理画面Playwright E2E

Playwrightを新規追加せず、`web/admin`またはリポジトリで既に解決できる`@playwright/test`だけを使う。Node、`@playwright/test`、ブラウザ実行ファイル、storage state、必須selectorのいずれかが無ければ`NOT_VERIFIED`で終了する。no-opを`PASS`に変換しない。

実行者が、外部ログインを済ませたstorage stateを安全な場所に用意し、検証対象のstaging/local URLとselector契約を渡す。runnerはログイン、TOTP発行、npm installを行わない。

```bash
export RINBAM_ADMIN_E2E_BASE_URL='https://staging.example.invalid'
export RINBAM_ADMIN_E2E_STORAGE_STATE='/secure/operator/admin-storage-state.json'
export RINBAM_ADMIN_E2E_ROLE_SELECTOR='[data-testid="admin-role"]'
export RINBAM_ADMIN_E2E_EXPECTED_ROLE='owner' # 実際のadmin_users.roleに合わせる
export RINBAM_ADMIN_E2E_STEP_UP_SELECTOR='[data-testid="admin-step-up"]'
export RINBAM_ADMIN_E2E_SUBMIT_SELECTOR='[data-testid="admin-submit"]'
export RINBAM_ADMIN_E2E_ERROR_SELECTOR='[role="alert"]'
export RINBAM_ADMIN_E2E_MUTATION_ROUTE='**/api/admin/promo-codes/send'

bash scripts/verify_admin_e2e.sh --json
```

管理画面の実画面はルート `/` にあるため、runnerの既定値も `/` である。別pathへ配置したstagingだけ
`RINBAM_ADMIN_E2E_PROTECTED_PATH` と `RINBAM_ADMIN_E2E_SENSITIVE_PATH` を明示する。`/admin`を既定値として使わない。

実行するテストは次の4項目である。

1. storage stateを使わないadmin APIが401/403または認証redirectになること。
2. 事前に認証・role付与・step-up済みのセッションでroleとstep-up UIが確認できること。TOTPの自動入力はしない。
3. mutation routeをPlaywright内でfixture応答に差し替え、server error後の再試行が同じ`operationId`を再利用し、409の既存状態を扱えることを確認する。実際の外部mutationは発生させない。
4. 保護画面の表示要素について、visibleなbutton/link/form controlのaccessible nameと画像の`alt`を確認すること。

`RINBAM_ADMIN_E2E_A11Y_SCOPE`、`RINBAM_ADMIN_E2E_PROTECTED_PATH`、`RINBAM_ADMIN_E2E_PROTECTED_API_PATH`、`RINBAM_ADMIN_E2E_SENSITIVE_PATH`、`RINBAM_ADMIN_E2E_ERROR_STATUS`で対象を調整できる。値はstorage stateやtokenそのものを含めない。

Playwright未導入・ブラウザ未導入・設定不足・storage state不足は、次のdry-runと同じく`NOT_VERIFIED`であり、E2E実施済みとは報告しない。

```bash
bash scripts/verify_admin_e2e.sh --dry-run --json
```

## ローカルfixtureと構文確認

署名鍵、Store、外部URL、ログインを必要としない。

```bash
python3 -m unittest \
  scripts/test_validation_entrypoints.py \
  scripts/test_verify_release_artifacts.py \
  scripts/test_verify_admin_e2e.py
```

fixtureでは、unsigned AAB/APK/iOS archiveを`FAIL`、dry-runとPlaywright/設定不足を`NOT_VERIFIED`、偽の検証ツールで作った署名fixtureだけを`PASS`として確認する。

## この契約だけでは閉じないゲート

この入口は、署名済み成果物の存在・Store Sandbox/TestFlight/App Store/Play Console状態・物理Android/iPhone操作・本番Supabase/Resend・OpenAI staging/submissionを証明しない。各ゲートは、認証済みの担当者が外部画面・実機・本番相当環境で実行し、対象build/version、時刻、結果、画面またはconsole証跡を別途記録する。証跡が無い限り、状態は`NOT_VERIFIED`または`NO_GO_INTERNAL`のままとする。
