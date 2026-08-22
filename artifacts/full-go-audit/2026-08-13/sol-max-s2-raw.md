# Sol MAX S2 生監査報告 — Web / docs / release / external evidence

## 1. 実行識別・独立性

| 項目 | 記録 |
|---|---|
| 親・代表質問者 | C0 / `019fe6c5-f82e-7261-9e0c-5397d17a4695` |
| 担当ID | S2 / `019fee58-b5cd-7ea1-add3-fdffc7f9b878` |
| 依頼モデル / effort | `gpt-5.6-sol/max` |
| モデル確認 | `MODEL_ASSIGNMENT_UNVERIFIED`。実行基盤はランタイムモデルを検証可能な形で提示しなかった。 |
| 独立実行確認 | `INDEPENDENT_AGENT_EXECUTION_UNVERIFIED`。別スレッドIDと委任は確認できるが、暗号学的な独立実行証明はない。 |
| 初回S2開始 | 2026-08-11（正確な時刻は取得不能） |
| 今回の証拠統合開始 | `2026-08-13T15:42:00+09:00` |
| 最終固定時刻 | `2026-08-13T17:31:31+09:00` |
| 一次レビュー時の独立性 | S2一次Web/docsレビュー中は、他担当の一次結論を入力として採用していない。監査原本、対象差分、実行結果を直接確認した。 |
| 他レビュー閲覧 | `YES_AFTER_PRIMARY`。一次提出後の指定された相互反証でS1 mobile差分を直接read-onlyレビューした。後段の証拠統合では既存S1 rawとC0提供のarchive/Store観測も閲覧した。S1の2026-08-13 Data safety CSV新turnは閲覧せず、重複作業もしていない。 |
| 新規F1/F2/F3 | `NOT_RUN_CAP2`。サブエージェント上限2のため、新しい3担当を起動していない。連続無発見監査回数は0。 |

レビューはユーザー、非ITユーザー、管理者、デザイナー、攻撃者、過剰修正反証の観点で行った。秘密値、Cookie、token、個人情報は成果物へ記録していない。

## 2. 対象スナップショットと変更境界

| 対象 | 開始 | 最終対象 | 状態 |
|---|---|---|---|
| root | `965d4d0cdd8fd916bc5adc996fc682b9875022d3` | 同じHEAD、branch `codex/full-go-mobile-s1-20260811` | HEAD不変。ただし共有working treeはdirtyで、tracked変更とactive untracked sourceがある。launch用の不変snapshotではない。 |
| nested `web/usage-guide` | `20add6f8f2af80cf6d43fc3a5bcc4771620b285d` | `dde36e2c253376c01843aa859fddb65d9602374a` / `main` | HEAD変更。working treeはclean。Sites v5のsourceは `ef3a02bce618c95b96d9ec263064c1e4bc3b0537` なので、current HEADとの同一性は未検証。 |

総合snapshot状態は `SNAPSHOT_CHANGED_DURING_REVIEW`。root HEADが同じでもworking treeが変化しているため、開始commitだけを根拠にrelease可とはしない。

この最終化フェーズでは以下を厳守した。

- `app/**`、`ios/**` はread-only。編集していない。
- branch作成・切替、checkout、restore、reset、rebase、stashをしていない。
- stage、commit、push、deploy、Store/Supabase等の本番変更をしていない。
- 他担当の差分を整形・削除・復元していない。
- StoreからのCSV exportはS1担当のため開かず、重複案を作っていない。

## 3. S2一次レビュー範囲

### 確認した範囲

- `web/invite-link`: password recovery、invite fallback、privacy、account deletion、CSP、Referrer Policy、ブランド、公開Vercel契約。
- `web/admin`: 未認証ログイン、API認証境界、入力境界・focus・Forced Colors、favicon、320/1280 CSS px、公開契約。
- `web/usage-guide`: ChatGPT手動受け渡し説明、ブランド、依存監査、lint/test/build、Sites source/deployment対応。
- `docs/**` と `scripts/**`: source-of-truth、canonical tracker、release manifest、privacy/Data safety、Store listing、release hygiene/readiness。
- `supabase/**`: Deno lint/test、問い合わせoutbox cryptoの静的・host test範囲。
- dependency/audit: npm（admin/usage guide）、Gradle release query、Deno/npm、gitleaks履歴のsanitized結果。
- 公開面: public Web、admin、Sites、公式App Storeページ、C0によるPlay Console read-only観測、C0による署名済みiOS archive観測。
- S1 mobile差分: 指定された `app/**`、`ios/**` とmobile testsをread-only相互反証。

### 未確認・アクセス不能

- App Store Connectの編集フォーム、Google Play Data safetyの変更・保存・審査・公開反映。
- current production Supabase Auth email recovery、live account、DB内容、function-to-current-source対応、Resend配信、backup restore。
- GCP Consoleでhistorical key-shaped materialのrestriction/rotation/deactivation状態。
- 認証済みproduction admin画面と本番データを使う操作。
- Store sandbox購入、復元、downgrade、refund、server notification E2E。
- current sourceの物理Android/iPhone全導線、VoiceOver/TalkBack、Dynamic Type最大、Share Extension実ハング時timeout。
- 全画面・全状態・全ブラウザ・全組み合わせの網羅。
- 新しいF1/F2/F3と連続2回無発見監査。

## 4. S2担当指摘の最終状態

| 指摘ID | 最終状態 | 直接証拠・合格境界 |
|---|---|---|
| `M-003` password-reset CSP mismatch | `FIXED_AND_VERIFIED` | inline script/styleを外部化し、strict CSP、SRI、recovery入力制約、memory-only session、即時URL scrub、15秒fail-closed、submit例外復帰、`Referrer-Policy: no-referrer` + HTML metaを実装。10 node tests、public contract、local browser smoke、production route/header確認がPASS。 |
| `M-006` invite失敗復帰 | `FIXED_AND_VERIFIED` | 実在する公式App Store/Google Play URL、招待リンクcopy、手動復帰説明、安全な無効状態を実装。local/public contract PASS。 |
| `M-007` collections current/retired | `FIXED_AND_VERIFIED` | `docs/rinbam-canonical-spec.md#source-of-truth`を唯一の順位にし、Collection/UserLabelをcompatibility-onlyとしてAGENTS/docsを整合。release hygiene/mobile contract PASS。 |
| `M-008` canonical tracker意味検証 | `FIXED_AND_VERIFIED` | PASS行とremaining gateを同時に許さない意味検証、CSV/XLSX story/summary完全同期、59行・一意ID・gate集計を検証。現在のexternal gateは隠さず`BLOCKED_EXTERNAL`。 |
| `M-009` Web/admin contrast | `FIXED_AND_VERIFIED` | control border 3.65:1、focus ring 4.83–5.17:1、status text 5.20–11.83:1、3px focus、Forced Colors、44 CSS px controlsを自動/実描画確認。公開favicon、console error 0、正しいmobile accessibility treeを確認。明示dark modeは`color-scheme: light`固定のため`NOT_APPLICABLE / unsupported by design`であり「dark verified」としない。 |
| `M-009` iOS側 | `FIXED_NOT_VERIFIED` | S1差分のpalette/non-color cueとhost testsをread-only確認したが、current sourceの物理iPhone・高コントラスト・Dynamic Type実描画は未確認。 |
| `N-005` usage guide ChatGPT説明 | `FIXED_AND_VERIFIED`（Web） | Sites v5公開HTMLで「ChatGPTに手動で渡して聞く」「対象件数と除外件数」「15件」の3点を確認。mobile側はS1 source/host確認のみで実機は未確認。 |
| `N-006` user-facing brand | `FIXED_AND_VERIFIED`（Web） | public/invite/privacy/admin/guideの可視ブランドを「りんばむ」へ統一。内部ID・bundle/packageなど互換技術名は維持。public contracts PASS。 |
| `N-011` README Java/SDK | `FIXED_AND_VERIFIED` | READMEをJava 21 / SDK 36へ更新し、C0/S1統合検証のJDK21 Android suite/build PASSと一致。 |
| `P-001` Deno require-await | `FIXED_AND_VERIFIED` | 最小修正後、Deno lint 14 files PASS、Deno tests 44/44 PASS。 |
| `H-009` historical GCP-key-shaped material | `FIXED_NOT_VERIFIED` | current HEAD/treeに候補なし。履歴23記録のSecret/Match/Email/Authorはすべて空にsanitizedし、rule/file/commit/lineだけを保持。gcloudなし・GCP Console未確認のためrestriction/rotation/deactivationは未確認。履歴改変なし。 |
| `H-010` dependency vulnerabilities | `FIXED_AND_VERIFIED_WITHIN_CURRENT_LOCAL_DEPENDENCY_SCOPE` | admin/usage guide full+production npm audit 0、Gradle release OSV 0、Deno/npm OSV 0。jsoupを1.23.1へ更新して旧advisoryを解消し、Android host/lint/build互換性を維持。Store配布Android artifactとcurrent dependency graphの完全対応は別途未確認。 |

## 5. 追加Store / release指摘

### `STORE-IOS-PRIVACY-001`

- 分類: App Privacy。
- 旧判定: cloud/support/purchase実装と公開`データの収集なし`が矛盾するMajor。
- 反証: C0が秘密値を表示せず確認した署名済みarchiveは、public versionと一致する `1.0.17 (19)`、canonical bundle/Team、`SharedTagCloudEnabled=false`、Supabase/contact-support設定empty。StoreKitのdeveloper verificationもcloud/sessionがなければ到達しない。
- 最終状態: `FALSE_POSITIVE`。exact public binaryについてApple privacy Majorは撤回。
- 残余: metadata destination/providerがURL/IPを保持するかは契約・retention証拠なしのため`HYPOTHESIS`。将来cloud-enabled iOS buildにはこの結論を流用しない。

### `STORE-IOS-LISTING-001`

- 分類: Store listing / product functionality。
- 重大度 / 確信度: `Major / High`。
- 事実: 公式App Storeは2026-08-13にversion `1.0.17`、7月28日、7.2 MB、言語`EN 英語`、共有タグ・同期・招待・クラウド接続を説明。matching archiveはlocal-only。
- 影響: 公開説明で約束した主要クラウド機能を配布binaryが提供できない。
- 状態: `CONFIRMED / BLOCKED_EXTERNAL`。
- 合格条件: App Store説明から無効機能を除去して公開反映を確認する、または正しくprivacy申告したcloud-enabled replacementを提出し、同一version/build provenanceと実動作を確認する。

### `STORE-IOS-I18N-001`

- 分類: localization packaging。
- 重大度 / 確信度: `Minor / High`。
- 事実: current sourceは`ja`を宣言するが、matching archiveはdevelopment region `en`、`CFBundleLocalizations`なし、`.lproj`なし。公開`EN 英語`はarchiveと一致する。
- 状態: `CONFIRMED / BLOCKED_EXTERNAL`。
- 合格条件: Japanese localizationを含む新しい署名archiveを作成・検査し、提出後のStore言語を再確認する。

### `STORE-GOOGLE-001`

- 分類: Google Play Data safety。
- 重大度 / 確信度: `Major / High`。
- 事実: C0の認証済みread-only観測で、Step 2はcollect/share=`Yes`・encrypted=`Yes`、Step 5 previewはShared=`Web browsing history`・Collected=`no data collected`、account creation=`does not allow`、deletion choice未回答。cloud-enabled Android v21のauth/shared-tag/support/purchase実装と一致しない。フォーム変更・保存は未実施。
- 状態: `CONFIRMED / BLOCKED_EXTERNAL`。
- 合格条件: 下記matrixをexact submitted artifactとprocessor契約に照らして確定し、Consoleで変更・保存・reviewし、public Data safetyを再取得して一致を確認する。

### `LAUNCH-INTEGRATION-001`

- 分類: release snapshot。
- 重大度 / 確信度: `Major / High`。
- 事実: root shared treeはdirty、active untracked sourceあり、launch branchではない。`scripts/check_launch_readiness.sh`はこの3条件を正しくFAILする。
- 状態: `CONFIRMED / NO_GO_INTERNAL`。
- 合格条件: 全変更をowner別に統合・reviewし、意図したfilesだけをcommitしたclean immutable release snapshot上でreadinessを再実行する。

## 6. Google Play Console exact input matrix draft

これは現行cloud-enabled Android v21向けの保守的入力案であり、法的断定ではない。Googleのservice-provider例外、user-initiated-action例外、ephemeral例外は、契約・UI・保持期間の一次証拠がない限り適用済みと扱わない。`Shared=Yes (conservative)`の行は、ownerが例外根拠を保存できた場合だけ`No`へ狭める。

| 実データ/flow | Play Console category > subtype | Collected | Shared | Ephemeral | Required / optional | Purpose | Linked assessment | 根拠 |
|---|---|---|---|---|---|---|---|---|
| account email | Personal info > Email address | Yes | Yes (conservative; Supabase processor exceptionは未立証) | No | Optional。local-only利用には不要、cloud account作成時は必須 | App functionality; Account management | Yes | `SharedTagAuthRemoteDataSource.kt` signup/signin/recovery; privacy `:83,96,126` |
| support email | Personal info > Email address | Yes | Yes (conservative; support/mail processor exception未立証) | No | Optional | App functionality; Developer communications | Yes | `ContactSupportClient.kt:20-31,46-67`; privacy `:84,115-116` |
| display/support name | Personal info > Name | Yes when supplied | Yes (conservative; participant/processor例外未立証) | No | Optional | App functionality; Account management; Developer communications | Yes | `SharedTagSyncRemoteDataSource.kt:96-99,366-373`; `ContactSupportClient.kt:20-31` |
| Supabase/auth user ID、member ID | Personal info > User IDs | Yes | Yes (conservative; processor exception未立証) | No | Optional globally; required for cloud membership | App functionality; Account management; Fraud prevention/security | Yes | authenticated RPC/session paths `SharedTagSyncRemoteDataSource.kt:406-446`; privacy `:83,96,116` |
| saved/shared URL | Web browsing > Web browsing history | Yes | Yes | No unless every destination proves memory-only/no retention | Automatic/required for metadata enrichment after save; optional cloud sharing itself | App functionality | Potentially yes through account/tag or network identifiers | privacy `:80,83,96,100-101`; metadata fetch and shared-tag RPC source |
| shared tag/group name | App activity > Other user-generated content | Yes | Yes to participants (conservative until prominent user-action exception is documented) | No | Optional | App functionality | Yes | shared operation/RPC models and `SharedTagSyncRemoteDataSource.kt:138-373`; privacy `:83,96` |
| membership、role、invite、ownership transfer | App activity > Other actions | Yes | Yes to affected participants (conservative) | No | Optional | App functionality; Account management; Fraud prevention/security | Yes | `SharedTagSyncRemoteDataSource.kt:30-109,164-404`; privacy `:83,96` |
| local user title / memo | App activity > Other user-generated content | No developer collection in normal/local/shared-tag contract | Yes (conservative) only when included in a user-chosen OS export recipient; remove if user-action exception is documented | No / N/A | Optional | App functionality | Yes when exported with a user's dataset | privacy `:82,96,104-110`; local export source. These fields are not shown sent to Rinbam cloud in inspected shared-tag contract. |
| local custom tag | App activity > Other user-generated content | No developer collection in normal local mode | Yes (conservative) only for user-chosen OS export; exception decision pending | No / N/A | Optional | App functionality | Yes when exported | privacy `:82,96,104-110` |
| support body | App activity > Other user-generated content | Yes | Yes (conservative; mail processor exception未立証) | No。encrypted outbox/dead-letterは最大7日保持 | Optional | App functionality; Developer communications | Yes to request/contact context | `ContactSupportClient.kt:20-31,46-67`; privacy `:84,115-116` |
| product ID、purchase token、transaction/order ID、entitlement/grant | Financial info > Purchase history | Yes when billing used | Yes (conservative; Store/platform/service-provider treatment未立証) | No | Optional | App functionality; Account management; Fraud prevention/security | Yes | `StorePurchaseRemoteDataSource.kt:22-66,69-125`; privacy `:85,120-121` |
| hashed email | Personal info > Email address | Yes | No for own audit DB; Yes only if separately transmitted | No | Optional/support-dependent | Fraud prevention/security; App functionality | Yes。hash化だけでanonymousとみなさない | privacy `:115-116`; contact-support audit migrations/functions |
| hashed user ID | Personal info > User IDs | Yes | No for own audit DB; Yes only if separately transmitted | No | Optional/support-dependent | Fraud prevention/security; App functionality | Yes | privacy `:116`; contact-support audit functions |
| hashed IP | Device or other IDs（conservative） | Yes | No for own audit DB; Yes only if separately transmitted | No | Optional/support-dependent | Fraud prevention/security | Treat linked until irreversible anonymization proof exists | privacy `:116`; contact-support rate-limit/audit functions |
| request/payload/audit hash | Source-derived category（Other actions / Other user-generated content as applicable） | Yes when persisted | No for own audit DB | No | Optional/support/purchase-dependent | Fraud prevention/security; App functionality | Treat linked while request/user mapping remains possible | privacy `:116`; support/purchase idempotency and audit migrations |
| metadata request to destination/oEmbed | Web browsing > Web browsing history | Yes | Yes to destination/provider | Do not select Yes without provider retention proof | Required for automatic metadata feature; saving itself remains available on failure | App functionality | Potentially linkable through IP/request | privacy `:100-101`; metadata fetch implementation |
| manual ChatGPT/OS share archive | Data categories included in archive | No collection by Rinbam | Yes (conservative) unless Google user-initiated-action exception is formally documented from preview/confirmation/recipient choice | No / N/A | Optional | App functionality | Yes to exported user dataset | privacy `:104-110`; Android export UI/repository contracts |

Form-level inputs:

- Does the app collect or share user data?: `Yes`.
- Is all collected user data encrypted in transit?: source/public endpoint evidence supports `Yes` for app-owned cloud/support/purchase paths; confirm the exact submitted AAB endpoints before saving. Metadata destination sharing is separately disclosed above.
- Does the app allow account creation?: `Yes` for the cloud-enabled Android artifact.
- Account deletion: answer `Yes` and supply the public deletion URL only after confirming the in-app and web routes for the exact artifact; both routes exist in source/public Web.
- Current Step 5 `Collected: no data collected` must not remain unless every retained flow above is removed from the exact artifact or valid exceptions/ephemeral evidence are documented.

## 7. S1 mobile差分の独立read-only相互反証

### 方法と独立性

相互反証時はS1一次報告を先に読まず、HEADとの差分、変更source、追加host testsを直接照合した。後段でC0による統合test数と既存S1 rawを閲覧したため、以下は「一次独立差分確認」と「後段証拠統合」を分けて記録する。S2はmobile suitesを再実行しておらず、C0/S1報告のAndroid 400 host PASS、iOS historical 179 PASS + 3 live Supabase skipを自分の実行結果として主張しない。

| 指摘ID | 独立照合結果 | 状態 | 反証・未確認 |
|---|---|---|---|
| `M-001` | preview payload、除外理由、snapshot token/generation、selection/content変更時の確認失効、未確認生成拒否をAndroid/iOS source/testsで確認。 | `FIXED_NOT_VERIFIED` | TOCTOU host contractは強化。両物理端末で全payload可視性・覗き見・長文/10kスクロール未確認。 |
| `M-002` | 「りんばむを選ぶだけ」ではなく、tag確認と最後の保存押下を説明する差分を確認。 | `FIXED_NOT_VERIFIED` | source/copy testsはあるが、current physical share sheetで未確認。 |
| `M-004` | Android manual save失敗後に入力/tagを保持し、明示close/successまで消さないSavedState/state-machineとtestsを確認。 | `FIXED_AND_VERIFIED_WITHIN_HOST_SCOPE` | 回転/プロセスkillの実機UI未確認。 |
| `M-005` | Android/iOS retryable payload保持、失敗分だけretry、成功済みIDの二重保存防止、部分成功表示を確認。 | `FIXED_NOT_VERIFIED` | Share Extensionの実際のlifecycle/timeoutと強制DB stallは未確認。 |
| `M-009` iOS | accessible palette、非色手掛かり、44pt hit target/inset修正とtestsを確認。 | `FIXED_NOT_VERIFIED` | 物理iPhone、VoiceOver、Dynamic Type、高コントラスト実描画未確認。 |
| `N-001` | versioned first-run store、既存DBユーザーへ自動表示しないmigration条件、manual guide分離を確認。 | `FIXED_NOT_VERIFIED` | clean install / upgrade / first-shareの物理E2E未確認。 |
| `N-002` | Android empty body/CTA接続とcontract testを確認。 | `FIXED_NOT_VERIFIED` | 320/360dp、最大文字、TalkBack実描画未確認。 |
| `N-003` | iOS guide rowsを同一stackへまとめtitle/body欠落を防ぐ差分を確認。 | `FIXED_NOT_VERIFIED` | Simulator/physical画像差分未再取得。 |
| `N-004` | iOS bottom overlay高さをscroll content insetへ反映する差分を確認。 | `FIXED_NOT_VERIFIED` | 最下部cardとの16pt実測、端末/文字倍率matrix未再取得。 |
| `N-005` | mobile/公開guideに専用ChatGPT handoff、対象/除外/責任境界が追加された。 | `FIXED_NOT_VERIFIED`（mobile）/ `FIXED_AND_VERIFIED`（Sites） | mobile physical guide未確認。Sites公開HTMLは確認済み。 |
| `N-006` | 可視brand「りんばむ」と技術IDの分離を確認。 | `FIXED_AND_VERIFIED_WITHIN_CONTRACT_SCOPE` | 全OS accessibility treeの完全列挙ではない。 |
| `N-007` | fallback title/host重複抑止をsource/testで確認。 | `FIXED_NOT_VERIFIED` | 多様なmetadata card実描画未確認。 |
| `N-008` | 極小固定fontのdynamic styles/最小値改善を確認。 | `FIXED_NOT_VERIFIED` | 最大Dynamic Type、日英混在、実機VoiceOver未確認。 |
| `N-009` | bottom actionsの44x44pt hit areaと重なり防止差分を確認。 | `FIXED_NOT_VERIFIED` | 物理iPhone accessibility frame未確認。 |
| `N-010` | user-facing error/helpコピーから内部語を減らし、次の操作を付ける差分を確認。 | `FIXED_NOT_VERIFIED` | 非IT実ユーザーテストは未実施。 |
| `H-001` | Pro/current entitlement時のpurchase/downgrade CTA guardを確認。 | `FIXED_NOT_VERIFIED` | Store sandbox、legacy entitlement、refund/downgrade未確認。 |
| `H-002` | iOS apply operation responseのop ID/status照合、terminal failure/non-success扱い、retry testsを確認。 | `FIXED_NOT_VERIFIED` | production Supabase conflict/forbidden/offline E2E未確認。 |
| `H-003` | iOS 10k limitをSQLite transaction内でnew-only atomic判定し、bind parameterをchunk化する差分/testsを確認。 | `FIXED_NOT_VERIFIED` | 実10k DB、同時extension/app writer、低速storage未確認。 |
| `H-004` | standard exportがsnapshot/tag failureを成功へ縮退せず、partial/completenessを明示する差分/testsを確認。 | `FIXED_AND_VERIFIED_WITHIN_HOST_SCOPE` | 実端末故障注入とarchive consumer互換未確認。 |
| `H-005` | generation/query照合、in-flight cancellation、stale completion拒否のdeterministic testsを確認。 | `FIXED_AND_VERIFIED_WITHIN_HOST_SCOPE` | 実10k検索性能はH-008側に残る。 |
| `H-006` | batch共通deadline、transaction境界、Undo対象の一貫性を確認。 | `FIXED_NOT_VERIFIED` | 5秒超実DB/physical timer race未確認。 |
| `H-007` | success/failure/missing IDsのtyped batch result、失敗selection保持、成功分のみUndoを確認。 | `FIXED_NOT_VERIFIED` | 両OSのmixed failure実描画/a11y announcement未確認。 |
| `H-008` | Android Room chunk size 900、iOS bind chunk 500、streaming/export制限とtestsを確認。 | `FIXED_NOT_VERIFIED` | 10k実データのwall time/RSS/battery/low-memory benchmark未実施。 |

### 新規相互反証項目 `S2-CROSS-IOS-SHARE-TIMEOUT-001`

- Severity / confidence: `Major candidate / Medium`。
- 対象: `ios/URLSaverShareExtension/ShareViewController.swift:680-716,727-790,828-833`。
- 静的観測: 20秒timeout taskはsave taskをcancelするが、同期SQLite save呼出し内部にSwift cancellation suspension pointがない。同期I/Oが長時間blockした場合、cancelだけでdeadline時にpreemptできるとは証明できない。
- 反証: SQLite側のbounded busy retryと通常処理時間は、実害可能性を下げる。host testsでtimeout状態遷移は確認できる。
- 状態: `HYPOTHESIS / FIXED_NOT_VERIFIED`。確認済みMajorへ昇格しない。
- 必要な再検証: disposable test containerでSQLite lock/slow I/Oを注入し、20秒以内にextension UIが再試行/取消へ戻り、late completionが二重保存/成功表示を起こさないことを物理iPhoneで確認。

### S1範囲の独立release判定

- source/host contract: `PARTIALLY_VERIFIED`。
- mobile full release: `INCOMPLETE_REVIEW / NO_GO`。実機/実描画、Share Extension blocked-save timeout、Store sandbox、production cloud、10k benchmarkが残る。
- 未解決を文書上だけGOへ変える根拠はない。

## 8. 公開・外部サービス証拠

| 対象 | 証拠 | 状態 / 境界 |
|---|---|---|
| Public Web | Vercel deployment `dpl_52Tc5XaiiLeV9F1EkxTLPXssqRmq`; alias `https://miyamibu.xyz`; public verifier PASS | `VERIFIED` for privacy/deletion/reset/invite/association files on named deployment. Store declarationは閉じない。 |
| Reset security | route CSP、`Referrer-Policy: no-referrer`、HTML referrer meta、external JS/CSS、memory-only recovery、early scrub、timeouts、submit recovery | source/unit/local browser/public HTTP contract `VERIFIED`; real expired email link and production Supabase recovery sessionは未確認。 |
| Admin | deployment `dpl_AhovWHbFyK6sVi7TQGSWi7i7CfCA`; alias `https://rinbamu-admin.vercel.app`; release verifier PASS | unauthenticated/API boundary、320/1280、Forced Colors、favicon、console 0 error `VERIFIED`。authenticated adminは`INACCESSIBLE/UNVERIFIED`。 |
| Sites usage guide | version `appgver_5acdb626a92081918fb169698267b802`; deployment `appgdep_6a7aa4f9a7f481918f9c27634538b7ff`; source `ef3a02bce618c95b96d9ec263064c1e4bc3b0537`; succeeded | deployed content 3新文言 `VERIFIED`。current nested HEAD `dde36e2…`とのsource correspondenceは`UNVERIFIED`。一般Python UAの403は残余bot特性、browser UAは200。 |
| App Store public | official page version 1.0.17、July 28、7.2MB、EN、cloud copy、no collection | public page `VERIFIED`。App Store Connect form変更は未実施。 |
| Signed iOS archive | `URLSaveriOS-20260728-1.0.17-distribution.xcarchive`: 1.0.17(19)、canonical bundle/Team、local-only、Supabase/support empty | `C0_DIRECT_VERIFIED / VALUES_NOT_RECORDED`。Apple privacy反証の強いartifact証拠。 |
| Google Play Console | C0 authenticated read-only Data safety Step 2/5観測 | `READ_ONLY_VERIFIED`; correction/save/review/public propagationは未実施。 |
| Android signed v21 | signed artifactのversion/signature/hashは既存release証拠。sanitized marker inspectionはcloud modeとapp-owned remote pathsの存在を確認 | `PARTIALLY_VERIFIED`; Storeで実際にactive servingされるartifact/current dependency graphの完全対応は未確認。秘密値は記録していない。 |
| Supabase | CLI installation/authenticated project-list reachabilityはC0確認 | `PARTIALLY_VERIFIED`。current production Auth/DB/functions/config、3 live tests、backup restoreは未確認。本phaseでdeploy/migration/data変更なし。 |
| Resend | repository Deno tests PASS | production delivery/console/webhook correspondenceは`INACCESSIBLE/UNVERIFIED`。 |
| GCP historical key | current tree scanとsanitized history artifact | current reachability `PARTIALLY_VERIFIED`; Console restriction/rotation/deactivationは`INACCESSIBLE`。 |
| Store purchase | source/host tests | Play Billing/StoreKit sandbox、refund、downgrade、server notificationは`UNVERIFIED`。 |
| Physical devices | historical evidenceのみ | current sourceのAndroid/iPhone full run、Share Extension timeout、VoiceOver/TalkBackは`UNVERIFIED`。 |

## 9. dependency更新理由と監査結果

- Android `org.jsoup:jsoup`を`1.23.1`へ更新した理由は、旧versionに対する既知advisoryを除去するため。current Gradle release query/OSVは0件で、JDK21 host tests、lint、debug/release buildが維持された。S2最終化フェーズでは`app/**`を編集していない。
- `web/usage-guide`は既存toolchainを維持した最小override/version選定で、`drizzle-kit -> esbuild`と`vinext -> image-size`の全依存auditを0にした。production-only auditも0。lint/test/build PASS。
- `web/admin`は既存ESM実装に`type=module`を明示してNodeのtypeless warningを除去し、依存を互換範囲で更新。full/production npm audit 0、typecheck/lint/19 tests/build PASS。
- 新規の大規模dependencyやframeworkは追加していない。
- OSV JSONの現行結果は、過去advisoryを現行脆弱性として残さず0件を記録。過去結果を参照する場合はhistorical/beforeと区別する。

## 10. 変更ファイル

### 今回2026-08-13最終化でS2が変更したroot files

- `docs/release/repo-go-evidence.md`
- `docs/release/privacy-data-safety-draft.md`
- `docs/release/privacy-policy-and-store-disclosure-checklist.md`
- `docs/release/store-listing-draft.md`
- `docs/release/final-submission-checklist.md`
- `docs/qa/rinbam_canonical_story_status.csv`
- `docs/qa/rinbam_canonical_story_status.xlsx`（正準sync scriptで生成・同期）
- `artifacts/full-go-audit/2026-08-13/project-file-manifest.csv`
- `artifacts/full-go-audit/2026-08-13/execution-ledger.csv`
- `artifacts/full-go-audit/2026-08-13/coverage-summary.md`
- `artifacts/full-go-audit/2026-08-13/sol-max-s2-raw.md`
- 同directoryの検証log（コマンド結果のdurable evidence）

### 同じS2担当作業で共有treeに残るWeb/docs/release files

- `.gitignore`, `AGENTS.md`, `README.md`
- `docs/ai/redaction-policy.md`, `docs/rinbam-canonical-spec.md`
- `scripts/check_release_hygiene.sh`, `scripts/verify_admin_web_release.sh`, `scripts/verify_canonical_story_tracker.py`, `scripts/verify_public_web_release.sh`
- `scripts/reset-password-recovery.test.mjs`, `scripts/serve_public_web_preview.py`, `scripts/sync_canonical_story_tracker.py`, `scripts/test_public_web_browser.sh`, `scripts/test_public_web_contract.py`, `scripts/test_verify_canonical_story_tracker.py`, `scripts/verify_web_ui_contrast.py`
- `supabase/functions/_shared/contact-support-outbox-crypto.ts`
- `web/admin/app/icon.svg`, `web/admin/app/layout.tsx`, `web/admin/app/page.tsx`, `web/admin/app/styles.css`, `web/admin/lib/promo.ts`, `web/admin/package.json`, `web/admin/package-lock.json`
- `web/invite-link/account-deletion/index.html`, `auth/reset-password/index.html`, `auth/reset-password/reset-password.js`, `auth/reset-password/styles.css`, `index.html`, `invite/index.html`, `invite/invite.css`, `invite/invite.js`, `privacy/index.html`, `promo/index.html`, `vercel.json`
- `artifacts/ui-review/2026-08-11/**` と `artifacts/ui-review/2026-08-13/web-admin-postdeploy/**` の証拠files。

この一覧は「S2 laneに属する差分」であり、共有tree全変更の作者をS2だと主張しない。全tracked/untracked pathと状態は`project-file-manifest.csv`を正準とする。

### nested `web/usage-guide`

- 最終working tree変更: なし（clean）。
- current HEAD: `dde36e2c253376c01843aa859fddb65d9602374a`。
- published Sites v5 source: `ef3a02bce618c95b96d9ec263064c1e4bc3b0537`。
- 本最終化ではnested commit/push/deployを行っていない。

### app / ios

- 本最終化で変更なし。read-only cross-reviewのみ。

## 11. 検証コマンド台帳

### 正式PASS

| コマンド | exit / 件数 | 証拠 |
|---|---|---|
| `python3 scripts/sync_canonical_story_tracker.py` | 0 / 59 stories同期 | tracker CSV/XLSX |
| `python3 scripts/verify_canonical_story_tracker.py` | 0 | `verify-canonical-story-tracker.log` |
| `python3 scripts/test_verify_canonical_story_tracker.py` | 0 / 10 PASS | `test-verify-canonical-story-tracker.log` |
| `python3 scripts/verify_release_manifest.py` | 0 | `verify-release-manifest.log` |
| `python3 -m unittest scripts.test_verify_release_manifest` | 0 / 4 PASS | `test-verify-release-manifest-module.log` |
| `bash scripts/check_release_hygiene.sh` | 0 | `check-release-hygiene.log` |
| `bash scripts/verify_public_web_release.sh` | 0 / 24 assertions | `verify-public-web-release-final.log` |
| `bash scripts/verify_admin_web_release.sh` | 0 / root 200 + six 401 + MCP 404 | `verify-admin-web-release-final.log` |
| `node --test scripts/reset-password-recovery.test.mjs` | 0 / 10 PASS | `test-reset-password-recovery.log` |
| `python3 scripts/test_public_web_contract.py` | 0 | `test-public-web-contract.log` |
| `bash scripts/test_public_web_browser.sh` | 0 / 5 PASS | `test-public-web-browser.log`。開始直前のlocal server retryでcurl接続失敗1行があるが、scriptはserver ready後の5 assertionを実行してPASS。 |
| `python3 scripts/verify_web_ui_contrast.py` | 0 / 19 PASS | `verify-web-ui-contrast.log` |
| `deno lint ...release functions...` | 0 / 14 files | `deno-lint-release-functions.log` |
| `deno test ...release functions...` | 0 / 44 PASS, 0 FAIL | `deno-test-release-functions.log` |
| `web/admin: npm audit; typecheck; lint; test; build` | 0 / audit 0、19 tests PASS | `web-admin-full-validation.log` |
| `web/usage-guide: npm audit full+prod; lint; test; build` | 0 / audit 0、2 tests PASS | `usage-guide-full-validation.log`。対象だったJSON import/extension warningは0。vinextのroute static-classification表示はtool informational output。 |
| `git diff --check` | 0 | 最終検証PASS。 |

### 意図どおりFAIL / 成功扱いしない

| コマンド | exit | 理由 |
|---|---:|---|
| `bash scripts/check_launch_readiness.sh` | 1 | dirty working tree、codex branch、active untracked sourceの3 FAIL。`NO_GO_INTERNAL`を正しく返した。 |

### 先行した誤った呼出しと訂正

- `python3 scripts/test_verify_release_manifest.py`はpackage import context不足でexit 1。正式なmodule呼出し`python3 -m unittest scripts.test_verify_release_manifest`を実行し4/4 PASS。
- 複数test moduleを同時に渡した呼出しはtracker helper import path不足で1 error。各scriptの正式な個別呼出しではrelease 4/4、tracker 10/10 PASS。失敗logを削除・隠蔽せず残した。

## 12. 生成物・証跡衛生

- `.playwright-cli/`: current statusに存在しない。今回の一時cacheは除外済みで、必要な公開/視覚証拠は`artifacts/ui-review/**`とaudit logsへ保持した。
- admin mobile accessibility tree: `artifacts/ui-review/2026-08-13/web-admin-postdeploy/mobile-320x900-accessibility-tree.json`はtarget title/bodyが`りんばむ 管理`で、usage guide誤採取ではない。summaryはfavicon published、console records空、failed network空を示す。
- gitleaks history: 23 records。`Secret`、`Match`、`Email`、`Author`は全て空。個人情報やcredential値を複製していない。
- `project-file-manifest.csv`: Git indexとnon-ignored untracked一覧をNUL区切りで全件取得し、root/nestedを別repoとして列挙。secret-like pathは内容/hashを読まず`INACCESSIBLE`。UTF-8 BOM 1個、8列、状態語彙制約、重複なしを検証。
- ignored/cache: Git active ignore規則による全件数をcoverageへ集計し、manifest上のreviewed fileへ昇格していない。
- artifact SHA-256:
  - `project-file-manifest.csv`: `7c7853112ae9f2770203d6d616ae8c0b4350569cc1df3a9c2cb94a9de2cfe034`
  - `execution-ledger.csv`: `3742782d655e86232efc43dc0e954855f49a5b911c2a14d0e8354d62760065b5`
  - `coverage-summary.md`: `e1540631f864f30ed1f69ee29f94ad7d6b340d08621a84fb7538ddd51b2d0709`
  - raw reportは自己参照hashを本文に埋め込まない。最終外部hashはC0側で取得可能。

## 13. 問題がないと確認した範囲

- public privacy/deletion/reset/invite/association routesはnamed deploymentでHTTP/内容契約PASS。
- password recoveryはordinary implicit tokenを受理せず、recovery token/codeを即時scrubし、memory-only clientで一度だけ初期化する。timeout/late completion/update exceptionはfail closed。
- admin unauthenticated APIは401、MCPは404、faviconは200相当で公開され、mobile/desktop/Forced Colors treeにnamed controlsがある。console errorは0。
- usage guide公開v5は新ChatGPT説明3点を含む。browser UA 200。
- current npm/Deno/Gradle queryで確認済み脆弱性は0。
- Apple exact public iOS 1.0.17(19) archiveについて、cloud developer collectionを示す証拠はなく、`データの収集なし`と直接矛盾しない。
- canonical trackerは未解決gateをPASSに隠さず、CSV/XLSXを一致させる。

## 14. 独立release判定

- S2 Web source/local/public contracts: `RELEASE_READY_WITHIN_NAMED_WEB_DEPLOYMENT_SCOPE`。
- full project RELEASE判定: `NO_GO`。
- レビュー完了状態: `PARTIALLY_VERIFIED / SNAPSHOT_INVALIDATED`。
- 内部状態: `NO_GO_INTERNAL`（dirty/untracked/unfrozen snapshot）。
- 外部状態: `BLOCKED_EXTERNAL`（Google Data safety Major、App Store listing functionality Major、Store post-change proof、production/device gates）。
- 事実上100%確認済み: `NO`。
- 連続無発見監査回数: `0`。

無条件GOは、証拠のないlabel変更では実現できない。最小の解除順は、(1) Google Data safety入力・保存・公開反映、(2) App Store説明またはbinaryの整合、(3) current sourceのclean immutable integration snapshot、(4) production Auth/DB/Resend/backupとStore sandbox、(5) current physical-device/Share Extension timeout、(6) fresh F1/F2/F3と連続監査である。

---

## Addendum — 2026-08-13 公開プライバシーポリシー開示補完（source only）

### 実行境界

- 担当: S2 Web/docs/external。ただし本addendumの書込対象は`web/invite-link/privacy/index.html`、`scripts/test_public_web_contract.py`、本S2 raw reportだけ。
- 開始時刻: 受領時刻を独立timestampとして取得していないため`UNVERIFIED`。最初のファイル更新記録は`2026-08-13T18:04:07+09:00`。
- 終了時刻: `2026-08-13T18:10:16+09:00`（最終検証完了後に`date`で取得）。
- 他者作業保護: S1が更新するdocs、CSV、S1 raw、`app/**`、`ios/**`は変更していない。実装根拠の確認目的でAndroid/Supabase sourceをread-only閲覧した。
- 独立性: 本addendumでS1の新しい一次報告・新turn結果は閲覧していない。既存S2 rawに記録済みの過去のS1差分cross-review事実は保持する。
- 禁止操作: Vercel deploy、Store/Supabase Console操作、公開ブラウザ確認、端末操作、stage、commit、push、branch操作は実施していない。秘密値・Cookie・token・個人情報は開いていない。

### 変更と直接証拠

1. `web/invite-link/privacy/index.html:78-104`
   - 共有タグ同期でアプリが生成するランダムな同期元識別子を端末内に継続保存し、同期時にクラウドへ送ることを明記。
   - タグ／グループの作成・名称変更・削除、URL追加・解除、招待、参加者追加・削除、権限・所有者変更を列挙。
   - 端末側は再試行対象の操作番号・種別・対象・送信状態、クラウド側は重複防止対象の操作番号・同期元識別子・適用結果を保持する、と保存場所と目的を分離。
   - 同期状態反映、二重適用防止、障害調査、監査の目的と「一時処理ではない」ことを明記。
   - クラウドアカウント削除時の操作記録削除、共同利用資源の残存、Android端末内同期記録の削除境界を明記。
   - 根拠: `app/src/main/java/jp/mimac/urlsaver/domain/SharedTagSyncContracts.kt:7-48`、`app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt:68-105`、`app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncEntities.kt:114-149`、`app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncDao.kt:232-242`、`supabase/migrations/20260420120000_shared_tag_sync.sql:134-140,250-266,465-468`、`supabase/migrations/20260716140000_restore_account_reassignment.sql:204-207`。
2. `web/invite-link/privacy/index.html:121-125`
   - 問い合わせでハッシュ化したメールアドレス・IPアドレス・サインイン中ユーザー識別子を監査情報として保持し、IP hash等が仮名識別子であることを明記。
   - 重複防止、rate limit、不正利用対策、配信確認、障害調査、監査の目的を明記。
   - 暗号化本文の最長7日消去と監査行の保持を分離し、監査情報には同じ自動消去期限がなく、共有タグアカウント削除だけでは必ずしも消えない境界を明記。
   - 根拠: `supabase/functions/contact-support/index.ts:335-337`、`supabase/migrations/20260627120000_contact_support_requests.sql:3-50`、`supabase/migrations/20260726190000_contact_support_idempotent_outbox.sql:260-296`、`supabase/migrations/20260727120000_contact_support_outbox_dead_letter.sql:167-169`。
3. `scripts/test_public_web_contract.py:165-185`
   - 上記の識別子、操作種別、端末／クラウド保持境界、非一時性、仮名識別子、目的、削除境界が欠落すると失敗するcontract assertionを追加。

### 検証結果

| コマンド | exit / 結果 | 判定 |
|---|---:|---|
| 変更前 `python3 scripts/test_public_web_contract.py` | 0 | baseline PASS。既存reset recovery Node tests 10/10 PASS、既存public contract PASS。 |
| 変更後 `python3 scripts/test_public_web_contract.py` | 0 | PASS。reset recovery 10/10、privacy disclosure contract、local HTTP/CSP/reset/invite contractすべてPASS。 |
| `bash scripts/check_release_hygiene.sh` | 0 | PASS。release artifact/privacy manifest/public web contract/Android canonical ID/ads disabled/release manifest/hygieneを確認。 |
| 最終 `git diff --check` | 0 | raw追記後を含む共有working tree全体にwhitespace errorなし。 |

### 状態・公開境界・残リスク

- 開示不足のrepo-local sourceとcontract test: `FIXED_AND_VERIFIED`（ローカルsource/contract範囲）。
- `https://miyamibu.xyz/privacy/`への今回差分の反映: `FIXED_NOT_VERIFIED`。本addendumではdeploy・公開取得を明示禁止されているため、既存named deploymentのPASSは今回の新開示を証明しない。本節がそれ以前の「public privacy named deploymentで確認済み」という記述を、新規開示について上書きする。
- Google Play Console Data safety: 未変更・未再確認。本修正は公開ポリシーsourceの透明性を補うだけで、既知の`Collected: no data collected`との不整合Majorを解消しない。
- Androidアカウント削除後の端末内`shared_tag_sync_state`／未完了outbox消去: 現行`clearLocalAccountData`はセッションとAI local dataを処理するが、同期テーブルの明示削除を確認できなかったため、公開文で残存可能性を開示した。実装修正は本taskの許可範囲外で未実施。
- production Supabase migration適用状態、実データ、実際の保持期間、公開HTML、Store表示、物理端末挙動は本addendumでは未確認。
- 独立release判定: full projectは引き続き`NO_GO`。本source差分単体は、deploy前のため公開release gateを通過していない。

---

## Addendum — 2026-08-21 公式公開状態の独立read-only再検証と外部action packet

### 1. 実行台帳・独立性・安全境界

- 担当: 既存S2（Web/docs/external）。agent ID `019fee58-b5cd-7ea1-add3-fdffc7f9b878`。
- 代表質問者: 親C0（source thread `019fe6c5-f82e-7261-9e0c-5397d17a4695`）。S2からユーザーへの直接質問なし。
- 依頼model: `gpt-5.6-sol/max`。実行runtimeのmodel割当を外部証明できないため`MODEL_ASSIGNMENT_UNVERIFIED`。
- 今回の証拠固定開始時刻: `2026-08-21T23:32:43+09:00`（公式5 URLの最終一括capture時刻）。それ以前の準備コマンドの厳密な初回時刻は記録していないため、この時刻より前まで遡る開始時刻は`UNVERIFIED`。
- 終了時刻: `2026-08-21T23:37:20+09:00`（raw追記後の最終`git diff --check`完了時刻）。
- root snapshot: branch `codex/full-go-mobile-s1-20260811`、HEAD `965d4d0cdd8fd916bc5adc996fc682b9875022d3`。共有working treeは多数のtracked/untracked差分を含み、immutable snapshotではない。
- nested `web/usage-guide`: branch `main`、HEAD `dde36e2c253376c01843aa859fddb65d9602374a`、今回変更なし。
- 最新`AGENTS.md`を再読。SHA-256 `84266cdb5863daef8520225537205d02e10dd1f5df11682dc0b61583eb9612e3`、170行。`decision-memory`、`codex-repo-safety`、repo-local `ai-release-gate`の契約を確認した。外部公開状態を過去証拠から現在へ自動昇格しない境界に従った。
- 一次独立性: 公式公開ページの表示判定は、各公式URLを未認証で直接取得した本文に基づく。S1の今回の新turn結果・Data Safety export案は閲覧していない。既存S2 rawには過去のS1 mobile差分cross-reviewが既に含まれるため、2026-08-21 addendum全体をS1についてblindとは表現しない。他者の結論を公式ページ観測の代替にしていない。
- 禁止操作を維持: deploy、public write、Store/Console import/save/review/submit、認証情報入力、課金、Git stage/commit/push/branch、端末操作、secret閲覧、`web/**`・`app/**`・`ios/**`・`supabase/**`・提案CSV編集は実施していない。

### 2. 公式公開面の直接証拠

capture時刻は`2026-08-21T23:32:43+09:00`。App Store / Google Play本文は動的生成されるため、下記SHA-256はその取得本文を特定するだけで、source revisionや将来の表示を保証しない。

| Surface | URL / HTTP / SHA-256 | 観測事実 | 状態 |
|---|---|---|---|
| Apple App Store | `https://apps.apple.com/jp/app/%E3%82%8A%E3%82%93%E3%81%B0%E3%82%80/id6771251450` / 200 / `8e53f174a255f722fa8ae70c3437eb793b65d2b91b9fc3af1fc45ba7c99545ad` | version `1.0.17`、公開日`2026-07-28`、`7.2 MB`、Languages `EN 英語`。説明は共有タグ・同期・招待・クラウド接続を含み、App Privacyは`データの収集なし`。 | 署名済み同版archive `1.0.17 (19)`のlocal-only証拠によりPrivacy Majorは再成立しない。説明と実配布機能の不一致は`CONFIRMED_MAJOR / BLOCKED_EXTERNAL`、言語は別`Minor`。 |
| Google Play listing | `https://play.google.com/store/apps/details?id=jp.miyamibu.urlalbum&hl=ja&gl=JP` / 200 / `57db6e6d78c55a4dd36313ff41c158251309f2d1df9c1c63e31135f1111e92c9` | package `jp.miyamibu.urlalbum`、version `1.0.17`、更新日`2026/08/05`。説明はクラウド同期を含む。表示は`データは収集されません`、`データは送信中に暗号化されます`。 | cloud-enabled Androidとの不一致Major継続。 |
| Google Play Data safety detail | `https://play.google.com/store/apps/datasafety?id=jp.miyamibu.urlalbum&hl=ja&gl=JP` / 200 / `8c9fb7f1ccb509997c277ea6b068726f0e3dad909de33aedf6fe6a6b2a25b076` | Shared=`ウェブ閲覧履歴`、purpose=`アプリの機能`。Collected=`データは収集されません`、in-transit encryption表示あり。取得本文にデータ削除／削除リクエスト表示なし。 | `CONFIRMED_MAJOR / BLOCKED_EXTERNAL`。削除表示なしは公開本文の観測であり、Console回答自体の推定ではない。 |
| Public Privacy Policy | `https://miyamibu.xyz/privacy/` / 200 / `15a1e8d5cc11a3be0227e5e2f063d486b9264856fcf6e0d94c353fcabb2eb953` | repo-local sourceにある永続sync-source識別子、非一時的な操作／適用結果、ハッシュ化IP等の仮名監査識別子の追記が公開本文にない。 | `PUBLIC_CONTENT_STALE_VS_REPO / BLOCKED_EXTERNAL`。 |
| Public account deletion | `https://miyamibu.xyz/account-deletion/` / 200 / `16cbc0f0ecf05f5380afecff98c4919f66f7d337a4625e240f7b6ac270d56be6` | repo-local `web/invite-link/account-deletion/index.html`とbyte-for-byte一致。 | `VERIFIED`（source/public対応のみ）。 |

Repo-local Privacy Policy sourceは`web/invite-link/privacy/index.html`、SHA-256 `f96ba1f6ee11119e11053767119ae0b48f6fae4e6552e79de6728ef7a0e9686f`。公開本文とbyte不一致で、上記3概念はlocalに存在しpublicに存在しないことを個別に確認した。

### 3. 既存draft・CSVの再確認

| Artifact | Schema / 内容境界 | SHA-256 |
|---|---|---|
| `artifacts/full-go-audit/2026-08-13/google-play-data-safety-proposed.csv` | UTF-8 BOM、5列（Question ID / Response ID / Response value / Answer requirement / Human-friendly label）、782 response rows、59 non-empty answers。編集なし。 | `a98bbb4d8ffa09f264a54cf1efc04248f3dd51faedc47b8738cb1ba5422a91c8` |
| `docs/release/privacy-data-safety-draft.md` | Google/Appleのdraftとデータフロー境界。今回read-only。 | `dd4a205eee70579d22e8bad9080580567608b9ae136c1316412ab228f9b40ca4` |
| `docs/release/store-listing-draft.md` | cloud-enabled Android draftとlocal-only iOS copyを分離。2026-08-21 current evidence/action packetを追記。 | `e80b42e53f81bdd3c14fee3cad486b0fd7526d14e5130ee2de6ea31bb5de72b6` |
| `docs/release/privacy-policy-and-store-disclosure-checklist.md` | public policy stale、Store correction packet、owner gateを追記。 | `7350e194f4364d727270649b53b2d5fe911b83e174f78b555f88a76a8186f429` |
| `docs/release/repo-go-evidence.md` | 2026-08-21 direct recheckとcurrent action packetを最上段の現行証拠として追記。 | `629f02b8c8f5a8ebbe7bfd48fa00dbfd74f633464fd7cc3e8d344973507dabd6` |

Google CSVが選択する8データ型は、Name、Email address、User IDs、Purchase history、Web browsing history、Other user-generated content、Other actions、Device or other IDs。既存row-level案ではcollected/shared、ephemeral、required/optional、purpose、linkedを個別回答している。今回CSV自体は変更していない。

### 4. 外部修正のexact action packet（未実行）

#### A. Public web

1. 対象sourceを`web/invite-link`に固定する。
2. `web/invite-link/.vercel/project.json`で既存linked project名が`invite-link`、project/org IDが設定済みであることを秘密値非表示で再確認する。別projectを作らない。
3. deploy前に対象diffを限定確認し、`python3 scripts/test_public_web_contract.py`と`bash scripts/check_release_hygiene.sh`をPASSさせる。
4. owner承認下で同projectのproductionへdeployし、production domain `https://miyamibu.xyz`へaliasする。
5. post-verify: `/privacy/`と`/account-deletion/` HTTP 200、上記3開示概念、意図したsourceとの本文対応、account-deletion非回帰、reset CSP、`Referrer-Policy: no-referrer`、recovery URL即時scrub／timeout／例外復帰、invite fallback、AASA/assetlinks、`bash scripts/verify_public_web_release.sh`。deployment ID、source SHA/commit、時刻、alias、response hashを保存する。

#### B. Google Play Data safety

1. canonical packageを`jp.miyamibu.urlalbum`に固定し、変更前フォームとStore previewをexport／sanitized screenshotで保存する。
2. 上記SHA-256の`google-play-data-safety-proposed.csv`を同packageのData safety importへ指定する。importは現行回答を上書きするため、ownerが差分と対象appを再確認するまで実行しない。
3. 8カテゴリすべてについて、exact v21 artifactに対するcollected/shared、non-ephemeral、required/optional、purpose、linked、encryption、account creation、deletionを再確認する。
4. 解釈gate 1: Supabase、問い合わせ配送、billing/store processorごとに、Googleのcurrent service-provider exceptionを満たす契約・利用目的・再利用制限があるかownerが確認する。provider名だけで`not shared`にしない。
5. 解釈gate 2: 参加者に見える共有タグデータと手動OS/ChatGPT共有ごとに、current user-initiated-action exceptionの要件と実UI操作証拠をownerが確認する。一括例外にしない。
6. ownerが上書き内容を承認した後だけsave→review→submitする。認証/2FA、契約判断、save/submitはユーザー側操作。反映後、公式listing/Data safetyを再取得し、version/date、8カテゴリ、収集/共有、暗号化、削除表示をbefore/afterで照合する。

#### C. App Store Connect

1. app ID `6771251450` / bundle `com.mibu.codebridge.ios`、現行公開`1.0.17`、配布archive `1.0.17 (19)` local-onlyを対象境界にする。
2. App Privacy `Data Not Collected`は、exact archiveから別のretained off-device flowが確認されない限り維持する。
3. description/subtitle/review notesを`docs/release/store-listing-draft.md#local-only-app-store-copy--ios-1017-19`へ合わせ、cloud/shared-tag sync/sign-in/invite/purchaseを約束しない。
4. 公開済みversionのlocalized metadataがcurrent statusで編集不可なら、in-place更新済みと主張せず次versionを作成し、同じlocal-only範囲のexact buildとcopyを対応付ける。version番号はownerがrelease計画で決めるため捏造しない。
5. `EN 英語`はmetadata文言だけで閉じない。日本語localizationを実際に含む新しいsigned archiveを検査してからpublic languageを再確認する。
6. 認証/2FA、契約、version作成、metadata保存、build選択、審査送信はowner側入力・承認を要する。今回未実行。

### 5. 実行検証

| Command / check | Exit | 結果と証拠境界 |
|---|---:|---|
| 未認証公式5 URL一括取得・本文marker/hash確認 | 0 | App Store、Play listing、Play Data safety、Privacy、account deletionは全HTTP 200。上記表示とSHA-256を固定。 |
| `python3 scripts/test_public_web_contract.py` | 0 | Node reset 10/10 PASS、privacy disclosure contract、local HTTP/CSP/reset/invite contract PASS。 |
| `python3 scripts/verify_release_manifest.py` | 0 | Android/iOS source、migration head、release documentsがmanifestと一致。 |
| `python3 scripts/verify_canonical_story_tracker.py` | 0 | CSV/XLSX整合PASS。remaining gatesはandroid_device 4、iphone_device 4、distribution_signing 3、supabase_auth 21、store_console 6、resend_live 4。 |
| `bash scripts/check_release_hygiene.sh` | 0 | release hygiene、privacy manifests、public web local contract、canonical IDs、release manifest PASS。 |
| `bash scripts/verify_public_web_release.sh` | 0 | 現行public routes/CSP/invite/associationの既存契約PASS。ただしこのscriptは8月13日追加の上記3開示概念をassertしないため、このPASSはstale Privacy本文を閉じない。 |
| `git diff --check` | 0 | 共有working tree全体にwhitespace errorなし。既存他者差分は変更・整形していない。 |

### 6. 変更、未確認、独立判定

今回変更したのは次の4ファイルのみ。`web` source、Store proposal CSV、`app/**`、`ios/**`、`supabase/**`、tracker、coverage、execution ledgerは変更していない。

- `docs/release/repo-go-evidence.md`
- `docs/release/privacy-policy-and-store-disclosure-checklist.md`
- `docs/release/store-listing-draft.md`
- `artifacts/full-go-audit/2026-08-13/sol-max-s2-raw.md`

未確認／未実行:

- App Store ConnectとGoogle Play Consoleのcurrent authenticated form、import preview、保存、審査、公開反映。
- Vercel production deployと、新しいPrivacy本文のpublic反映。
- production Supabase Auth/DB/function/migration、Resend live、backup restore、Feature Flag、Store purchase sandbox。
- current sourceのclean immutable snapshot、current signed binaries、物理iPhone/Android、Share Extension timeout、F1/F2/F3、連続無発見監査。

独立release判定は`NO_GO / PARTIALLY_VERIFIED`。repo-local action packetは実行可能な粒度まで確定したが、公開Privacyがlocal sourceより古く、Google Play Data safety MajorとApp Store listing functionality Majorが未解消である。共有dirty treeと外部未確認も残るため、`RELEASE_READY`または無条件GOへ変更する直接証拠はない。

---

## 2026-08-22 S1 cross-rebuttal addendum

### 1. 実行台帳、独立性、対象スナップショット

- S2 worker / thread ID: `019fee58-b5cd-7ea1-add3-fdffc7f9b878`。代表質問者 / C0 source thread: `019fe6c5-f82e-7261-9e0c-5397d17a4695`。
- 反証対象のS1 worker ID: `019fee58-b16d-7642-9c7d-a61f1b0a93b6`。
- Requested model / effort: `gpt-5.6-sol` / `max`。実行runtimeのmodel attestationはこのworkerへ公開されていないため、`MODEL_ASSIGNMENT_UNVERIFIED`。
- 厳密な最初のsource-read時刻は別artifactへ固定していないためstart timeは`UNVERIFIED`。timestamp付きの最初のdurable test outputは`2026-08-22T00:14:34+09:00`。source/test review closeは`2026-08-22T00:19:36+09:00`。
- 指定snapshotはbranch `codex/full-go-mobile-s1-20260811` / HEAD `965d4d0cdd8fd916bc5adc996fc682b9875022d3`だったが、実測branchは同じ、実測HEADは`10b2e3e332dc5dc606e59cfc767712fb7d0a9ff3`だった。差はcommit `10b2e3e3 fix(web): include Play signing certificate in assetlinks`の`web/invite-link/.well-known/assetlinks.json`のみ。account-deletion sourceは共有dirty working tree上の未commit差分であり、`SNAPSHOT_CHANGED_BEFORE_REBUTTAL`として扱う。branch切替・restore・reset等は実施していない。
- S1 raw `artifacts/full-go-audit/2026-08-13/sol-max-s1-raw.md:670-837`は本フェーズの明示対象として閲覧した。したがってS1結論に対してblindではない。S1の主張を最終根拠にはせず、current source、diff、testsを独立に照合してから判定した。他の新規agent報告は閲覧していない。
- 禁止操作を維持した。削除、stage、commit、push、deploy、Store/production操作、secret閲覧、ADB、`connectedDebugAndroidTest`、物理端末操作は0件。product source、web、release docs、S1 rawは編集せず、本S2 rawへの本addendum追記だけを行った。
- `understand-diff` skillが通常生成する`.understand-anything/diff-overlay.json`は、本依頼の「反証結果だけをrawへ追記」と競合するため生成しなかった。既存knowledge graphも存在しなかったため、`git diff` / `rg` / 1-based source readへfallbackした。

### 2. 確認範囲と問題なしを確認した部分範囲

確認したもの:

- Android account deletion marker、repository orchestration、WorkManager scheduler、shared sync coordinator / DAO、URL provenance pruning、pending invite、entitlement cache/repository、production wiring、関連JVM tests。
- iOS cleanup marker、shared store transaction、sync-flight cancellation、foreground shared-tag mutations、entitlement service/cache、pending invite、AppServices wiring、AppModel、関連Simulator tests。
- 現行privacy/account-deletion sourceとS1の置換文案。
- S1報告の逐次テスト主張を、現working tree上のfocused host/Simulator testsで再実行。

問題なしを確認できた部分範囲:

- Androidの対象ユーザー削除は`/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt:104-118`で同一mutex・Room transaction内にあり、outbox/stateは`SharedTagSyncDao.kt:235-245`、synced tag/referenceは`TagDao.kt:465-469`で`authUserId`限定。残存参照を再集計し、`UrlEntryDao.kt:327-354`は`localProvenanceCount = 0`かつ参照0のshared-only rowだけを消す。
- iOSは`/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Data/SharedTagCloud.swift:1912-1931`で対象`authUserID`のshared tablesだけをSQLite transaction内で削除し、同ファイル`1934-1950`で残存参照を再計算する。
- Android `SharedTagSyncRepositoryTest.kt:370-489`とiOS `SharedTagStoreTests.swift:122-208`は、user Aを消しuser B、local provenanceのURL、memo、archive状態を保持する逐次DB契約を実際に検出する。Androidはlocal tagもassertする。`userTitle`およびiOS self-created tagはテストで明示assertしていないが、上記削除条件・対象tableからはlocal rowを更新／削除しない。
- 通常sign-outはAndroid `DefaultTagRepository.kt:701-703`、iOS `SharedTagCloud.swift:2458-2460`ともsession clearのみ。Android `TagRepositoryTest.kt:357-369`、iOS `SharedTagStoreTests.swift:210-246`がdeletion cleaner/shared cacheを巻き込まないことを検出する。
- markerが既に正常保存された後の逐次local cleanup retryはremote deleteを呼ばない。Android `DefaultTagRepository.kt:736-812`、iOS `SharedTagCloud.swift:3160-3235`とrequest-count testsで確認した。この限定条件は維持される。

### 3. S1 findingごとの相互反証判定

| Finding ID | S2判定 | Severity / confidence | 反証結論 |
|---|---|---|---|
| `S1-ACCDEL-001` | `DISPUTED` | Major / high | 対象ID限定の静止DB cleanupは`FIXED_AND_VERIFIED`だが、cleanup後にold-session foreground mutationがstate/outbox/snapshotを再生成でき、pending inviteはuser-scopedでない。したがって「対象ユーザーだけのaccount-linked stateを完全に消す」という全体主張は成立しない。 |
| `S1-ACCDEL-002` | `DISPUTED` | Major / high | Android markerの同期`commit()`と各stage保存、iOSの全stage/User ID再生成testは有効。しかしremote成功前後を跨ぐdurable protocolではなく、iOSは複数UserDefaults keyを個別更新しserialization/atomic commitがない。marker成立後の逐次範囲を超えて「完全な耐久契約」とはいえない。 |
| `S1-ACCDEL-003` | `DISPUTED` | Major / high | marker成立後の逐次retryがremoteを再実行しないことは確認。しかしremote成功後marker保存前の失敗／process loss、およびmarker未成立時の並行`deleteAccount()`はremoteを再実行し得る。S1自身も`sol-max-s1-raw.md:828-829`で前者を未解消と記録しており、ledgerの`FIXED_AND_VERIFIED`と矛盾する。 |
| `S1-ACCDEL-004` | `FIXED_AND_VERIFIED` | Major / high | Android/iOSとも通常sign-outはsession-onlyであり、focused host/Simulator regressionもPASS。production/provider挙動ではなくlocal implementation/test範囲の判定。 |
| `S1-ACCDEL-005` | `DISPUTED` | Major / high | remote response後のsession再確認は追加されたが、再確認とcache writeが同じlock/actor/generation gateではない。cleanupが両者の間へ入るTOCTOUで旧account cacheを再生成できる。既存testはremote callback内でsessionを先にclearする順序しか作らない。 |
| `S1-PRIV-009` | `DISPUTED` | Major documentation gate / high | S1文案はaccount-linked local dataの確実な削除とremote非再実行を無条件に約束するが、下記Majorが残る。現行`privacy/index.html:101-104`の「残る場合がある」は保守的には偽ではない。account-deletion pageもlocal cleanup完了をまだ断定していないため、文案は実装修正・競合test後まで適用不可。 |

### 4. 新規確認済み問題と仮説

#### S2-ACCDEL-006 — entitlement session revalidation後のcache-write TOCTOU

- State: `CONFIRMED`。Severity: `Major`。Confidence: `high`。
- Android direct evidence: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/EntitlementGrantRepository.kt:21-33,45-63`はremote response後にsession IDを確認してから別のsuspend callでsaveする。`EntitlementGrantStore.kt:74-113`のsave/clearはstore内mutexでは直列化されるが、session確認はmutex外であり、delete generation/tombstoneを再確認しない。
- iOS direct evidence: `/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Data/SharedTagCloud.swift:1292-1333`もsession確認後に`cache.save`する。cache内lockは`1036-1109`にあるが、session確認と同じatomic operationではない。
- Reproduction ordering: account Aのfetch完了 → Aがcurrentだと確認 → account deletionがsession/cacheをclear → suspended refreshがcache lockを取得してAのgrantsをsave。この順序ではcleanup後に旧account cacheが復活する。
- Counter-evidence limit: Android `EntitlementResolverTest.kt:321-375`はremote fake内でsessionをclearしてからreturnするため「確認より前」の順序だけを検出し、確認後／save前のbarrierを作らない。iOSは`URLRulesTests.swift:383-404`のmatching-account clearだけで、in-flight entitlement race test自体がない。
- Required fix: account deletion generation/tombstoneをsessionとcache mutationに共有し、cache lock/actor内でgenerationとauth IDを再検証してからsaveする。refresh/redeem taskも削除時にcancel-and-awaitし、この厳密な割込み順序をbarrier testで再現する。

#### S2-ACCDEL-007 — foreground shared-tag mutationがcleanup fence外で旧account stateを再生成する

- State: `CONFIRMED`。Severity: `Major`。Confidence: `high`。
- Android direct evidence: `DefaultTagRepository.kt:238-262,293-331,343-373`は削除前に取得したsessionを保持し、Room transaction内で`SharedTagSyncCoordinator.ensureClientId()`を呼び、outboxを作成してsyncをscheduleできる。cleanupは`SharedTagSyncCoordinator.kt:104-118`の同mutexを使うが、foreground mutation全体や取得済みsessionをinvalidateしない。cleanup後にmutationがmutexを取得すると`ensureSyncState()`が削除済みuserのclient stateを再作成し、captured sessionでoutboxを保存できる。
- iOS direct evidence: `SharedTagCloud.swift:2185-2213`の`SharedTagSyncFlight`が追跡するのは`2572-2593`の`syncCurrentSession()`だけ。`2595-2617`のcreateTagや`2685-2731`のgroup mutationsは削除前のsessionを保持してremote operation後に`refreshLocalState(session:)`を呼ぶ。`3237-3244`はcaptured auth IDでsnapshotをapplyし、削除後のsession/generation再検証がない。
- Reproduction ordering: mutationがA sessionをcaptureしremote待機 → deleteがsign-out/cancel tracked sync/clear A → mutationが戻りA snapshotまたはclient state/outboxをapply。Aの削除済みlocal stateが再生成される。
- Counter-evidence limit: WorkManager cancel-and-awaitとiOS sync-flight testsはscheduled/current syncだけを扱い、foreground create/rename/delete/assign/invite/member/role mutationを開始した状態でdeleteするtestがない。
- Required fix: 全shared-tag mutationとsyncをaccount-scoped operation gate/generationへ参加させ、削除開始後は新規operationをfail closed、既存operationをcancel-and-awaitし、全local write直前にsession ID + generationを再検証する。

#### S2-ACCDEL-008 — remote成功から最初のdurable markerまでの窓と並行remote再実行

- State: `CONFIRMED`。Severity: `Major`。Confidence: `high`。
- Android direct evidence: `DefaultTagRepository.kt:705-733`はremote delete成功後に初めてmarkerをsaveする。`LocalAccountCleanupStore.kt:43-70`はcommit失敗をthrowするが、このsaveはdelete flowで回復処理されず、session clearもまだ始まっていない。次回`deleteAccount()`はmarkerなし/sessionありとしてremoteを再実行する。
- iOS direct evidence: `SharedTagCloud.swift:3114-3157`もremote成功後に初めてmarkerをsaveする。`2117-2182`はstateを複数UserDefaults keyへ順次書きmarker keyを最後にsetし、service-level serializationもない。process loss前のdurabilityと2つの同時delete callを閉じない。
- Existing testsの反証限界: Android `TagRepositoryTest.kt:260-282,394-450`、iOS `AiTransparencyTests.swift:350-470`は最初のmarkerが保存され、その後のlocal stageだけをfailさせる。marker save failure、remote commit直後のprocess loss、2 concurrent initial callsは再現しない。
- Required fix: server deleteをidempotent/status-queryableにし、client側はremote call前にtarget ID付きのsingle-blob durable state（例: `remoteDeletionPending` / `remoteDeletionConfirmed`）を原子的に保存する。delete/retryを同一mutex/actorで直列化し、unknown outcome時はstatus queryまたはidempotent keyで収束させる。

#### S2-ACCDEL-009 — pending invite cleanupが対象user限定でない

- State: `CONFIRMED`。Severity: `Minor`。Confidence: `high`。
- Android: `/Users/mimac/Desktop/りんばむ/app/src/main/java/jp/mimac/urlsaver/data/PendingInviteStore.kt:5-13,19-38`のrecordはtoken/timeだけでauth user IDを持たず、`AccountLinkedLocalDataCleaner.kt:27-29`はglobal clearする。
- iOS: `/Users/mimac/Desktop/りんばむ/ios/URLSaverShared/Data/PendingInviteStore.swift:4-42`もtoken/timeだけで、`AppServices.swift:82-86`は同じglobal keychain itemをclearする。
- Impact: account A削除時に、未紐付けまたは別accountで後から受ける予定の招待も消える可能性がある。local URL等の主要データは失わないが、招待リンクの再取得が必要になる。
- Required fix: invite recordへ所有auth ID／pre-auth global分類を持たせ、target Aに紐付くものだけを消す。global pre-auth inviteを削除対象にする製品契約なら、対象限定要件と公開文をその契約へ明示的に合わせる。

#### S2-ACCDEL-010 — Android lock-order inversionによるcleanup停止候補

- State: `HYPOTHESIS`。Severity: `Major`。Confidence: `medium-high`。runtime再現testは未実施のため断定しない。
- Static evidence: foreground pathsは`DefaultTagRepository.kt:241-261,295-329,349-370`でRoom transactionを先に開始し、その中でcoordinator `operationMutex`を取る。一方cleanupは`SharedTagSyncCoordinator.kt:104-118`で`operationMutex`を先に取り、その中でRoom transactionを開始する。
- Candidate interleaving: foregroundがRoom transactionを保持してmutex待ち、cleanupがmutexを保持してRoom transaction待ちになる。cleanup/retryが停止し得る。
- Required verification/fix: deterministic barrier testで両順序を再現する。`ensureClientId`をtransaction前に固定するか、全pathを同じaccount gate → DB transaction順へ統一し、timeoutなしで完了することをtestする。

### 5. テスト実行と証拠

| Command | Exit / result | Evidence boundary |
|---|---:|---|
| `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.TagRepositoryTest --tests jp.mimac.urlsaver.SharedTagSyncRepositoryTest --tests jp.mimac.urlsaver.EntitlementResolverTest --tests jp.mimac.urlsaver.EntitlementGrantStoreTest --tests jp.mimac.urlsaver.LocalAccountCleanupStoreTest --tests jp.mimac.urlsaver.SharedTagAuthViewModelTest` | 0 / 60 PASS、0 fail、0 error、0 skip | `/Users/mimac/Desktop/りんばむ/app/build/test-results/testDebugUnitTest/TEST-*.xml`の対象6 suiteを再集計。ADB/端末なし。逐次契約を確認するが上記barrier順序は含まない。 |
| `xcodebuild ... -derivedDataPath /tmp/rinbam-s2-cross-rebuttal-derived build-for-testing CODE_SIGNING_ALLOWED=NO` | 0 / `TEST BUILD SUCCEEDED` | iOS Simulator buildのみ。署名・物理端末なし。AppIntents metadata extraction skipped warningはaccount deletionと無関係。 |
| `xcodebuild ... id=876E2C29-9B8A-4C5F-9691-72929AE1975F ... test-without-building -only-testing:...AiTransparencyTests -only-testing:...SharedTagStoreTests -only-testing:...URLRulesTests` | 0 / 60 PASS、0 fail、0 skip | `/tmp/rinbam-s2-cross-rebuttal-focused-20260822-0016.xcresult`。Simulator `Rinbam-S1-20260811`、iPhone 17 Pro model、iOS Simulator 26.5。NavigationRequestObserver warning 1件はtest failureではない。 |
| `xcrun xcresulttool get test-results summary --path /tmp/rinbam-s2-cross-rebuttal-focused-20260822-0016.xcresult` | 0 / total 60、passed 60 | xcresultの件数を独立集計。 |

既存testsの有効範囲:

- 真陽性を検出する: remote failure時にlocal untouched、stageごとのfail-once/retry、marker再生成、post-marker remote call count 1、対象user DB cleanup、other user/local URL/memo/archive保持、通常sign-out分離、sessionがremote return前に消えたentitlement応答の破棄。
- 検出しない: marker save failure、remote commit直後process loss、concurrent delete/retry、session recheck後cache save前の割込み、foreground mutationとの競合、Android lock inversion、実WorkManager worker、iOS foreground mutation Task cancellation、auth-scoped invite、production Supabase/provider cascade。
- よって60/60 + 60/60 PASSを「account deletion競合まで完全検証」とするのは偽陽性である。

### 6. Privacy/account-deletion文言との整合

- Current source `/Users/mimac/Desktop/りんばむ/web/invite-link/privacy/index.html:101-104`はAndroid local sync identifier/outboxがaccount deletion後も残る場合を開示する。上記S2-ACCDEL-007/008が残るため、この保守的開示を「stale」と断定できない。
- S1案`sol-max-s1-raw.md:818-822`の「進行中同期を停止」「対象account stateを消去」「cloud削除を繰り返さずlocalだけ再試行」は、通常逐次成功／marker成立後retryには合うが、全実行順序には合わない。現状で公開すると過剰保証になる。
- Current account-deletion source `/Users/mimac/Desktop/りんばむ/web/invite-link/account-deletion/index.html:70-99`はcloud側削除と通常local URLの境界だけを記し、競合時cleanup完了を断定していない。本フェーズでは変更不要。実装修正後に、target-scoped cleanup、temporary marker、ordinary sign-outとの差を短く追記し、process-loss/idempotency contractも実証してから公開する。

### 7. 未確認範囲、修正要否、release判断

未確認:

- production Supabase delete RPC、provider-side cascade/retention/idempotency/status query。
- 実行中WorkManager workerとaccount cleanupのcancel-and-wait、process kill / power loss、real pending invite、StoreKit/Google Play entitlement、physical Android/iPhone、Share Extension lifecycle。
- deterministic concurrent delete/retry、entitlement post-check barrier、foreground mutation cleanup、Android lock inversion test。既存product/test編集が禁止されたため追加testは作っていない。
- 指定HEADと実測HEADが異なり、共有dirty treeも変動可能なためimmutable snapshot再現性。

修正要否:

- `S2-ACCDEL-006`、`S2-ACCDEL-007`、`S2-ACCDEL-008`は公開前にproduct source + deterministic regression testの修正が必要。
- `S2-ACCDEL-009`は対象限定要件を維持するなら修正必要。仕様としてglobal inviteを消すなら要件／文言の明示変更と回帰testが必要。
- `S2-ACCDEL-010`はMajor候補として再現testで確定し、lock orderを統一してからcloseする。

Release判断:

- 問題なしと確認した狭い部分範囲（exact-user sequential SQL cleanup、local provenance保持、ordinary sign-out、marker成立後の逐次retry）: `FIXED_AND_VERIFIED_WITHIN_ISOLATED_SEQUENTIAL_TEST_SCOPE`。
- account-deletion focused scope全体: `NO_GO`。未解決confirmed Major 3件 + Major hypothesis 1件 + Minor 1件。
- full public project: `NO_GO / PARTIALLY_VERIFIED / SNAPSHOT_INVALIDATED`。外部・実機・provider gateに加え、本addendumのmobile競合Majorが残る。
- `事実上100%確認済み: NO`。S1のfocused `RELEASE_READY_WITHIN_DECLARED_LOCAL_SCOPE`は本反証で維持できない。
- raw reportのfinal SHA-256は、自己参照で本文を再変更しないため本文内へ埋め込まず、addendum追記後の最終handoffで報告する。
