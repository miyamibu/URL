# りんばむ 正準ストーリー検証ログ

- 作成日時: 2026-06-29 00:39:51 +0900
- 正準スプレッドシート: `docs/qa/rinbam_canonical_story_status.xlsx`
- CSVミラー: `docs/qa/rinbam_canonical_story_status.csv`
- 今回の焦点: 2026-06-29時点の追加ストーリーと品質管理行の更新

## 台帳更新

- 既存の単一正準台帳を継続利用。
- ストーリー数: 50
- 追加済み:
  - `US-031` ローカルタグ共有リンク / JSON export/import
  - `US-032` 未ログイン招待の保留 / サインイン後再開
  - `US-033` Standard / Pro ストア購入検証
  - `AS-007` ストア購入検証履歴 / entitlement grant監査
  - `US-034` 共有保存時のタグ選択 / 作成
  - `US-035` 受信箱 / 自作コレクション作成・移動・並び替え
  - `US-036` ChatGPT personal link sync
  - `AS-008` 問い合わせ送信 / Resend配信追跡
  - `ES-006` Privacy・Account deletion・App Links / Universal Links公開運用

## 実行結果

- Android debug/local: PASS `./gradlew assembleDebug testDebugUnitTest lintDebug`
- Android targeted: PASS `./gradlew testDebugUnitTest --tests 'jp.mimac.urlsaver.SharedTagAuthViewModelTest' --tests 'jp.mimac.urlsaver.TagRepositoryTest'`
- Android share/collection targeted: PASS `./gradlew testDebugUnitTest --tests 'jp.mimac.urlsaver.ShareReceiverActivityEntrypointTest' --tests 'jp.mimac.urlsaver.RepositoryBehaviorTest' --tests 'jp.mimac.urlsaver.MainListViewModelTest' --tests 'jp.mimac.urlsaver.ListFilterStateTest' --tests 'jp.mimac.urlsaver.TopFilterOrderResolutionTest'`
- Android contact targeted: PASS `./gradlew testDebugUnitTest --tests 'jp.mimac.urlsaver.ContactSupportClientTest'`
- Android release: PASS `./gradlew assembleRelease`
- iOS targeted: PASS `URLRepositoryTests`, `PendingInviteStoreTests`
- iOS build: PASS generic iOS Simulator build
- iOS full XCTest: PARTIAL/FAIL. `SharedTagCloudLiveSyncTests` 3件が `Invalid API key` で失敗。ローカル対象テストは成功。
- Web admin: PASS `npm run typecheck && npm run build`
- Supabase Edge Function: PASS `deno check supabase/functions/verify-store-purchase/index.ts`
- Supabase contact Functions: PASS `deno check supabase/functions/contact-support/index.ts` and `deno check supabase/functions/contact-support-resend-webhook/index.ts`
- Supabase local lint: BLOCKED_LOCAL `supabase db lint --local` failed because local Postgres `127.0.0.1:55422` refused connection.

## 未検証ゲート

- Android physical: タグJSON共有、招待保留再開、Google Play Billing sandbox購入は未検証。
- iPhone physical: Share Extensionタグimport、保留招待再開、StoreKit sandbox購入は未検証。
- Supabase live/local DB: migration適用、RLS、RPC、store purchase verificationのDB write/read契約は未検証。
- External store: Google Play / App Store Connect側のproduct、価格、sandbox、通知連携は未検証。

## エラー記録

- iOS full XCTest: live Supabase shared-tag tests failed with `Invalid API key`.
- Supabase local lint: local Postgres connection refused on `127.0.0.1:55422`.
- Android release build warnings:
  - release manifest removal markers reported no matching declarations for ad/adservices entries.
  - `Icons.Outlined.MenuBook` deprecated warning.

## 次のループ

- 物理Androidで非破壊のタグ共有/招待再開フローを確認する。
- 物理iPhoneはAppium/WebDriverAgent復旧後にShare Extension/招待再開を確認する。
- Supabase local DBを起動するか、承認済みlinked環境でmigration/RLS検証を実施する。
- ストアsandbox購入は外部ストア設定完了後に実施する。


## 2026-06-29 Additional Inventory Pass

Added tracker rows:
- US-037 保存リンク検索: Android/iOS source-confirmed, UI/device test gap remains.
- US-038 複数選択で一括アーカイブ/削除: Android/iOS source-confirmed, UI/device test gap remains.
- US-039 確認メール再送/パスワード再設定: Android/iOS/Supabase source-confirmed, live auth mail gap remains.
- US-040 データ取り扱い表示と広告無効化ガード: AdsConfigTest/source-confirmed, store declaration/device UI gap remains.
- TS-001 UserLabel DB/Repository基盤のみでUI未接続: foundation-only not user-facing; needs UI+iOS parity design if promoted.

## 2026-06-29 Targeted Test Pass

- Targeted Android unit pass: AdsConfigTest, MigrationDedupTest.migration_8_9_preservesExistingData_andCreatesUserLabels, SharedTagAuthViewModelTest.

## 2026-06-29 Web/Admin Inventory Pass

Added/updated tracker rows:
- US-039: added public reset-password page evidence and remaining live recovery gap.
- AS-009: added Resend message id manual delivery lookup API; live Resend query gap remains.

## 2026-06-29 Web Admin Retest

- Web admin pass: npm run typecheck && npm run build; delivery API route included in Next build output.

## 2026-06-29 Parity Fix Pass

Fixed and retested:
- US-006: iOS display mode was @State-only while Android persisted it. Changed iOS RootView/AppChrome to persist EntryListDisplayMode via @AppStorage("entryListDisplayMode").
- US-012: Added iOS BGTaskScheduler/BackgroundTaskRunner evidence to tracker.

Validation:
- PASS: ./gradlew testDebugUnitTest --tests MainListViewModelTest.toggleEntryCardDisplayMode_updatesStore --tests ArchiveViewModelTest
- PASS: xcodebuild generic iOS Simulator build after iOS display persistence change
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/MetadataStatusTextTests, 5 tests

Remaining gaps:
- Physical iPhone display-mode persistence across relaunch is not verified.
- Physical Android/iOS background metadata scheduling is not verified.
- iOS test emitted simulator/runtime warnings for App Group entitlement and duplicate display_name migration logs; tests still passed.

## 2026-06-29 Export Entitlement Retest

Retested and tracker-updated:
- US-025 export: Android ExportRepositoryTest/ExportScreenTest PASS; iOS ExportArchiveBuilderTests/ServiceFilterTests export/filter coverage PASS.
- US-023/US-024 entitlement and limits: Android EntitlementResolverTest PASS; iOS URLRulesTests LimitChecker targeted tests PASS.
- US-040: added iOS PrivacyInfo.xcprivacy app/share-extension evidence to tracker.

Remaining gaps:
- Physical Android/iPhone export share sheet and file-save handoff are not verified.
- Supabase live entitlement RPC and actual promo redemption remain unverified.
- iOS simulator tests still emit App Group entitlement and duplicate display_name migration logs; no test failures observed.

## 2026-06-29 iOS Migration Log Fix

Fixed:
- Replaced SharedTagCloud display_name try? ALTER TABLE calls with SQLiteDatabase.addColumnIfMissing for shared_tag_members and shared_tag_group_members.

Validation:
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/MetadataStatusTextTests, 5 tests.
- PASS: /tmp/rinbam-metadata-test.log contains no duplicate column name/display_name migration log after fix.

Remaining simulator warnings:
- App Group entitlement warning still appears in simulator test context.
- CoreSimulator WebCore/WebKit duplicate UIAccessibilityLoaderWebShared warning still appears.

## 2026-06-29 iOS SharedContainer XCTest Warning Fix

Fixed:
- Added XCTest-only SharedContainer fallback so unit tests avoid App Group container lookup when the test host is not entitled.

Validation:
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/MetadataStatusTextTests, 5 tests.
- PASS: /tmp/rinbam-metadata-test-after-sharedcontainer.log contains no App Group container_create_or_lookup warning.
- PASS: /tmp/rinbam-metadata-test-after-sharedcontainer.log contains no duplicate column name/display_name migration log.

Remaining simulator warning:
- CoreSimulator WebCore/WebKit duplicate UIAccessibilityLoaderWebShared warning still appears.

## 2026-06-29 Search Parity Fix

Fixed:
- US-037: Android search no longer matches every entry when any unrelated tag name matches the query.
- US-037: Android search now uses per-entry local tag assignments for tag-name matching.
- US-037: iOS search now includes assigned local tag names.

Validation:
- PASS: ./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.MainListViewModelTest.
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/ServiceFilterTests, 15 tests.

Corrected during validation:
- First iOS retest failed because the Swift filter closure needed an explicit return after adding the local-tag condition. Fixed and retested successfully.

Remaining gaps:
- Physical Android/iPhone search-field open, input, clear, back, and guide-to-search UI operation is not verified.
- iOS custom collection name search is not equivalent to Android yet. URLRecord.collectionID and collections Repository/API foundation now exist, but RootView/UI collection-name search wiring is still missing under US-035/US-037.

## 2026-06-29 Batch Selection Contract Tests

Retested and tracker-updated:
- US-038: Android MainListViewModel batch archive returns only successfully archived ids while still attempting each selected id.
- US-038: Android MainListViewModel batch pending-delete returns only successful pending-deletion timestamps.
- US-038: iOS URLRepository multiple archive/pending-delete state transitions are covered by URLRepositoryTests.

Validation:
- PASS: ./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.MainListViewModelTest.
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/URLRepositoryTests, 10 tests.

Remaining gaps:
- Physical Android/iPhone selection bar operation, select all, cancel, batch archive/delete, and Undo are not verified.
- iOS RootView selection UI itself remains source-confirmed plus repository-tested, not UI-operated.

## 2026-06-29 iOS Collection Foundation Pass

Fixed/foundation added:
- US-035: Added iOS URLRecord.collectionID.
- US-035: Added iOS url_entries.collection_id with DEFAULT 1 and idx_url_entries_collection.
- US-035: Added repository decoding and a URLRepositoryTests assertion that newly saved entries default to inbox collectionID 1.
- US-035: Added iOS collections table, CollectionSummary, load/create/assign/reorder/delete repository APIs.
- US-035: Added repository coverage for default inbox, duplicate collection name reuse, entry move, custom order, delete moving entries back to inbox, and default inbox delete refusal.

Validation:
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/URLRepositoryTests, 12 tests.

Corrected during validation:
- First iOS retest failed because collectionID was accidentally added to ParsedURL instead of URLRecord. Moved it to URLRecord and retested successfully.

Remaining gaps:
- iOS still lacks UI connection for custom collection create, move, delete, reorder, and filter parity with Android.
- Physical Android collection reorder/delete and physical iPhone collection UI are not verified.

## 2026-06-29 iOS Collection UI Wiring Pass

Fixed/added:
- US-035: iOS AppModel now loads collections and exposes create/assign collection actions.
- US-035: iOS main/archive top filters now show collection chips and a +保存先 create chip.
- US-035: iOS manual URL save sheet can choose an existing collection or create a new collection before saving.
- US-037: iOS search now matches the entry's own collection name, not unrelated collection names.

Validation:
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/ServiceFilterTests, 17 tests.

Remaining gaps:
- iOS still needs existing-entry move UI, collection delete UI, collection reorder UI, and same-name local-tag linkage UI for full US-035 parity.
- Physical Android/iPhone collection/search UI operation is not verified.

## 2026-06-29 iOS Collection Detail Move Pass

Fixed/added:
- US-035: DetailView now shows the current collection and opens a save-destination editor.
- US-035: Existing entries can be moved to another collection or to a newly created collection from the detail screen.

Validation:
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/ServiceFilterTests, 17 tests.

Remaining gaps:
- iOS still needs collection delete UI, collection reorder UI, and same-name local-tag linkage UI for full US-035 parity.
- Physical Android/iPhone collection/search UI operation is not verified.

## 2026-06-29 iOS Collection Management Parity Pass

Fixed/added:
- US-035: iOS top filter can open 保存先管理.
- US-035: iOS custom collections can be reordered with up/down controls and deleted from the management sheet.
- US-035: Deleting a custom collection moves entries back to inbox and deletes a same-name local tag.
- US-035: Detail collection moves now create/assign same-name local tags, including the previous custom collection name, matching the Android preservation behavior.

Validation:
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/URLRepositoryTests -only-testing:URLSaveriOSTests/ServiceFilterTests, 29 tests.

Remaining gaps:
- Physical Android/iPhone collection/search UI operation is not verified.

## 2026-06-29 iPhone Physical Verification Preflight

Checked:
- Ran Appium XCUITest real-device lister.
- Ran Xcode device listing.
- Checked local Appium server status on 127.0.0.1:4723.

Result:
- BLOCKED: Appium server was not running.
- BLOCKED: Appium/usbmuxd reported 0 connected real devices.
- BLOCKED: Xcode listed physical iPhones/iPads only under Devices Offline.

Impact:
- Physical iPhone UI gaps in the tracker remain NOT VERIFIED on physical iPhone.
- Simulator/local XCTest results must not be treated as physical-iPhone operation proof.

## 2026-06-29 UserLabel Foundation Audit

Checked:
- Android source has UserLabelEntity, UserLabelDao, userLabelId, observe/create/delete/assign repository APIs, and migration coverage.
- Android UI source and tests do not reference observeUserLabels/createUserLabel/deleteUserLabel/assignLabel/userLabelId outside migration coverage.
- iOS shared/app/test source has no UserLabel/userLabelId equivalent.
- git history shows UserLabel entering through the Android data-foundation commit, not through a removed user-facing UI flow.
- PASS: ./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.MigrationDedupTest.migration_8_9_preservesExistingData_andCreatesUserLabels.

Conclusion:
- TS-001 remains FOUNDATION_ONLY_NOT_USER_FACING.
- Do not add a user-facing label UI without a product decision, because DESIGN.md treats advanced tagging as out of scope unless explicitly approved and the app already has local tags and collections.

## 2026-06-29 Canonical Tracker Integrity Fix

Fixed:
- ES-001: The XLSX workbook filter definedName still covered A1:S42 while the current sheet dimension was A1:S57.
- Updated `_xlnm._FilterDatabase` to `canonical_story_status!A1:S57` so all 56 story rows are included in the spreadsheet filter range.
- Regenerated the XLSX `summary` sheet from the CSV status counts after ES-002 status changed from the old full-PASS text to `LOCAL_PASS_WITH_CONNECTED_TEST_GAP`.
- Added a remaining-gate section to the XLSX `summary` sheet so physical-device, Supabase/Auth, Store/public-console, Resend, and connected instrumentation gaps are visible in the same workbook.

Validation:
- PASS: CSV row count is 56.
- PASS: XLSX sheet dimension is A1:S57.
- PASS: XLSX filter definedName is A1:S57.
- PASS: CSV/XLSX sheet1 cell comparison has 0 diffs.
- PASS: XLSX summary status counts match the CSV status counts.
- PASS: XLSX summary remaining-gate counts are generated from the current tracker rows.
- PASS: unzip integrity check reports no errors.

## 2026-06-29 ES-002 Status Precision Fix

Fixed:
- ES-002 previously used a `PASS:` status while the same row still documented unverified connectedDebugAndroidTest, physical share, and purchase-operation gates.
- Updated the status to `LOCAL_PASS_WITH_CONNECTED_TEST_GAP` so local Android build/test/lint success is separated from destructive/physical-device gates.

Validation:
- PASS: tracker contradiction scan no longer reports ES-002 as full PASS with unverified text.

## 2026-06-29 Public Privacy Live Verification

Checked:
- ES-006: `https://miyamibu.xyz/privacy/` returned HTTP 200.
- ES-006: `https://miyamibu.xyz/account-deletion/` returned HTTP 200.
- ES-006: `https://miyamibu.xyz/.well-known/assetlinks.json` parsed as JSON and includes `jp.miyamibu.urlalbum`.
- ES-006: `https://miyamibu.xyz/.well-known/apple-app-site-association` parsed as JSON and includes `8R3B5675ZJ.com.mibu.codebridge.ios`.

Found:
- BLOCKED_PUBLIC_DEPLOY_STALE: live privacy page still says `本物の課金も行いません`.
- Repo source `web/invite-link/privacy/index.html` already discloses Standard / Pro subscriptions, Google Play Billing, and StoreKit.
- The public deployment is therefore stale relative to the billing-enabled `1.0.11` source and store/privacy assumptions.

Updated:
- ES-006 status changed to `PARTIAL_PUBLIC_PRIVACY_STALE_WITH_FINAL_SHA_GAP`.
- US-040 status changed to `LOCAL_PASS_WITH_STORE_DECLARATION_AND_PUBLIC_PRIVACY_GAP`.
- `docs/release/final-submission-checklist.md` now marks the public privacy URL as stale until redeployed.
- `docs/release/privacy-data-safety-draft.md` now separates account-deletion public 200 from the stale public privacy page.
- `docs/release/store-listing-draft.md` now marks the privacy policy URL as usable only after redeploy verification.
- Added `scripts/verify_public_web_release.sh` so the public web release checks can be rerun without copying ad hoc curl commands.

Validation:
- PASS: `bash -n scripts/verify_public_web_release.sh`.
- FAIL expected-current: `scripts/verify_public_web_release.sh` passes HTTP/JSON/package/bundle checks but reports 5 privacy wording issues because the live page is stale.

Remaining:
- Redeploy the privacy page, then re-run live curl/body checks.
- After redeploy, rerun `scripts/verify_public_web_release.sh`; it should exit 0 only when the public privacy page discloses Standard / Pro, Google Play Billing, and StoreKit and no longer contains no-real-billing wording.
- Replace/reverify Android App Links after final Play App Signing SHA-256 is available.

## 2026-06-29 Android App Privacy Dialog Alignment

Fixed:
- US-040: Android `privacy_dialog_body` previously described local URL storage, metadata fetch, and no third-party crash collection only.
- Updated it to also disclose shared-tag cloud sync, contact-support submission, Standard / Pro Google Play Billing processing, and no ads/analytics/third-party crash collection.

Validation:
- PASS: `./gradlew assembleDebug`.
- PASS: `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.ads.AdsConfigTest`.
- NOTE: An earlier `--tests jp.mimac.urlsaver.AdsConfigTest` run failed because the filter used the wrong package name; the correct test class is `jp.mimac.urlsaver.ads.AdsConfigTest`.

Remaining:
- Physical Android privacy dialog display is not verified.
- Public privacy page is still stale until redeployed.

## 2026-06-29 iOS Same-Name Collection Tag Filter Fix

Fixed/added:
- US-035: iOS collection filtering now includes entries assigned to a local tag with the same name as the selected collection, matching Android ListFilterState behavior.
- Added ServiceFilterTests coverage for same-name local-tag entries appearing under the selected collection.

Validation:
- First retest failed because filteredEntries became a multi-statement Swift function without an explicit return. Added the return and retested.
- PASS: xcodebuild test -only-testing:URLSaveriOSTests/URLRepositoryTests -only-testing:URLSaveriOSTests/ServiceFilterTests, 30 tests.

Remaining gaps:
- Physical Android/iPhone collection/search UI operation is not verified.
