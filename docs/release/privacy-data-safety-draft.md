# Privacy And Data Safety Draft

## Goal
現在の source baseline（Android `1.0.17 (versionCode=21)` / iOS `1.0.17 (build=19)`）から次回提出する privacy / Data safety 回答を、選択した release mode と照合できる状態にする。過去の `1.0.11` 提出証跡は履歴として残すが、現在の提出可否の証明には使わない。

## Current source snapshot (manifest-backed)

- Android: `jp.miyamibu.urlalbum`, `versionName=1.0.17`, `versionCode=21`
- iOS: `com.mibu.codebridge.ios`, `shortVersion=1.0.17`, `build=19`; share extension `com.mibu.codebridge.ios.share`
- Supabase migration head: `20260822090000_account_deletion_idempotency.sql`

## Release Assumption
- Ads: disabled.
- Billing: enabled for Standard / Pro subscriptions through Google Play Billing and StoreKit.
- Third-party analytics: not used.
- Third-party crash reporting: not used.
- Shared-tag cloud: release mode dependent. Android reads release local/env config; iOS defaults to tracked local-only xcconfig and requires an explicit secrets xcconfig for cloud-sharing Archive/TestFlight.
- Account sign-in: enabled only when shared-tag cloud is enabled for the submitted binary.
- Current integration status: repo-local disclosure sources and public web checks can pass independently, but the shared working tree is dirty/unfrozen and current launch status is `NO_GO_INTERNAL / BLOCKED_EXTERNAL`. The signed iOS 1.0.17 (19) archive is local-only, so its public `データの収集なし` answer is not treated as a confirmed privacy mismatch. Google Play remains a confirmed Data safety mismatch for the cloud-enabled signed Android v21 candidate. The App Store has a separate listing-functionality mismatch because its description advertises cloud/sync/invite features that the distributed local-only binary cannot provide.

## Exact-binary Store Reconciliation (2026-08-13)

- App Store official product URL directly confirmed `1.0.17`, release date July 28, 7.2 MB, language `EN 英語`, shared-tag/sync/invitation/cloud description, and App Privacy `データの収集なし` on 2026-08-13 JST.
- C0 inspected the matching signed distribution archive without printing secret values: version `1.0.17`, build `19`, bundle `com.mibu.codebridge.ios`, Team `8R3B5675ZJ`, `SharedTagCloudEnabled=false`, and empty Supabase/contact-support configuration. Under Apple's collection definition, this is strong evidence that the distributed binary's `データの収集なし` answer is plausible; the earlier Apple privacy Major is withdrawn unless a deeper exact-binary SDK review proves another retained developer-accessible flow.
- The confirmed Apple Major is instead product accuracy: the public listing promises shared-tag cloud, sync, and invitations while the matching archive disables those features. The archive also has `CFBundleDevelopmentRegion=en`, no `CFBundleLocalizations`, and no `.lproj`; therefore public `EN 英語` matches the archive and exposes a separate localization-packaging Minor for a Japanese-primary app.
- Google Play official product page describes cloud synchronization while Data safety says no data is collected. C0 directly read the authenticated Console form on 2026-08-13: Step 2 says collection/sharing `Yes` and encryption `Yes`; Step 5 previews shared Web browsing history but says no data is collected; account creation is `does not allow`, and deletion is unanswered. No field was changed or saved.
- The signed Android v21 candidate contains a cloud-enabled shared-tag configuration and non-empty public client endpoint/config markers; no credential value is retained in this report. The live policy at `https://miyamibu.xyz/privacy/` discloses Supabase account email/session; shared-tag names, URLs, membership and invitations; optional support email/name/body plus audit metadata; and StoreKit/Google Play purchase identifiers and entitlement state.
- Apple requires app-functionality collection to be declared and defines collection around off-device transmission retained beyond the immediate request: https://developer.apple.com/app-store/app-privacy-details/
- Google requires complete and accurate Data safety answers for how the app, including integrated SDKs, collects and shares data: https://support.google.com/googleplay/android-developer/answer/10787469
- Therefore Google Play's current public/Step 5 `no data collected` answer is a confirmed disclosure mismatch for the cloud-enabled Android artifact. No Console correction is claimed. Do not project that Android result onto the local-only iOS binary.

## Data Stored Locally
- Saved URL, normalized URL, display URL.
- User title, memo, local tags.
- Fetched metadata such as page title, description, thumbnail URL, host, and service classification.
- Record state such as active, archived, pending delete.

## Network Access
- Metadata fetch may access user-provided pages and public oEmbed endpoints.
- Shared-tag URL data and shared-tag metadata are synced to Supabase when the user signs in and uses shared tags.
- For a signed-in shared-tag user, manual ChatGPT ZIP generation refreshes the existing shared-tag state before eligibility is finalized. This safety refresh does not send the ZIP or question text to Supabase.
- If in-app contact support is enabled, the app sends the user's contact email, name, and inquiry body to the configured support endpoint for email delivery. The audit table stores only minimal metadata such as request ID, hashed email/IP/user identifiers, platform, app version, build type, delivery provider/message ID, and delivery status. The delivery outbox/dead-letter path can persist the support payload encrypted while retrying (and for its bounded dead-letter retention); the audit table itself does not store plaintext inquiry body, email, or name.
- `ChatGPTに聞く` performs local-tag selection, redacted preview, and ZIP creation on device, then opens the OS share surface. The app does not call OpenAI API/OAuth/MCP/provider endpoints for this handoff. Starting Android direct share, or selecting a recipient in the OS share surface, grants that recipient temporary read access to the ZIP; the recipient's data handling then applies. Rinbam cannot observe the recipient app's final send.

## Manual ChatGPT Handoff Disclosure Boundary

- Included by user action: eligible ACTIVE/local saved-link fields shown in the preview, `publicSafeId`, local tag names, and saved-time metadata/excerpts.
- Excluded: shared-tag-derived, archived, pending-delete, raw `fetchedBody`, raw prompt, raw DB IDs, attachments, and app-owned production credentials.
- Redaction: known email/phone/token-like/Supabase/JWT/local-path patterns are masked. Unknown secret formats may remain, so the user must review and explicitly confirm before ZIP generation/share.
- The ZIP remains local until the user taps the share action. Starting Android direct share or selecting a recipient in the OS share sheet grants that recipient temporary read access. Rinbam cannot observe whether the recipient app later submits the attachment.
- Google Doc chapter 13's 34 items are prompts/use examples for ChatGPT after attachment. Rinbam provides only selection, preview, ZIP, and OS share; it provides no question field, automatic input/send, API/OAuth/login, or model setting.
- Public Privacy Policy and App Review notes must explain this before submitting a binary that exposes the feature.
- Google Play Data Safety and App Store Privacy treatment of the manual OS share itself still needs exact-binary/current-definition review. The cloud account, shared-tag, support, and purchase flows are separate off-device processing and cannot remain under a blanket `no data collected` answer.

## Account Data
- Email address and authentication session are handled through Supabase Auth.
- Shared tag membership, role, invite, and owner transfer records are stored in Supabase.
- Account deletion is available in app and through a public deletion request page.

## App Store Privacy Draft
- Distributed 1.0.17 (19): `Data Not Collected` is supported by the matching local-only archive evidence, subject to a final exact-binary SDK/privacy-manifest review. Do not copy the cloud-enabled draft below onto this binary.
- Future cloud-enabled iOS build only: declare Contact Info (auth/support email and optional name), User Content (shared-tag data and customer support), Identifiers (account ID), Purchases (transaction/entitlement processing), App Functionality purposes, and linked-to-user treatment where applicable.
- Tracking: No while no tracking/advertising profile is implemented.
- Required reason APIs: no app-owned categories currently declared in `PrivacyInfo.xcprivacy`.
- Account deletion: available in app and by public web request.
- Console status: `PRIVACY_CHANGE_NOT_REQUIRED_FOR_VERIFIED_LOCAL_ONLY_ARCHIVE / LISTING_ACCURACY_BLOCKED_EXTERNAL`. Correct the cloud/sync/invite product claims or replace the binary with an accurately disclosed cloud-enabled build; capture the exact version/build and resulting public page.

## Google Play Data Safety Draft
- Data collected: `Yes` for a cloud-enabled release. Include Name, Email address, User IDs, Purchase history, Web browsing history, Other user-generated content, Other actions, and Device or other IDs under the closest current categories.
- Identifiers: the app generates and persists a per-account/install shared-tag sync `client_id`, sends it with cloud operations, and the backend retains it with applied operation results. Contact support also retains a hashed source IP for abuse prevention. These are `Device or other IDs`; per-event request/op UUIDs alone are not separately classified as device IDs.
- App activity: shared-tag mutation types such as create/rename/delete/assign/invite are transmitted as durable operations and are `Other actions`. Tag names, group names, shared URLs, membership/collaboration fields, and support inquiry text remain `Other user-generated content`.
- Contact support data: email address, name, and inquiry body are transmitted for support email delivery when the user submits the contact form. The audit table excludes plaintext content, while the encrypted outbox/dead-letter path retains the support payload for delivery/retry under its bounded retention policy.
- Purchase data: store subscription product ID, purchase token / transaction ID, and entitlement grant state are processed to unlock paid plan features.
- Purpose: app functionality; account management for account/profile/purchase identity; fraud prevention/security for account IDs, purchase verification, persistent sync identifiers, and hashed source IP where used for idempotency/rate limiting.
- Required/optional: account/cloud/purchase data is required only for the corresponding opted-in feature; support submission is optional and user initiated.
- Sharing draft: declare Web browsing as shared because automatic metadata requests send the saved URL to destination/oEmbed services and no applicable exception was established for that path. Treat Supabase hosting and support email delivery as service-provider processing only while they act on the developer's instructions; shared-tag participant visibility and explicit OS handoff rely on the specific user-initiated-action exception. Re-open the sharing rows before submission if provider roles or UI expectations differ.
- Ephemeral: answer `No` for every selected category. The app/backend retains cloud account, shared content, purchase, sync identifier/action, and support audit data; arbitrary destination/oEmbed no-retention was not established for Web browsing.
- Security: the exact signed Android `1.0.17 (21)` AAB was hash-verified and inspected without emitting endpoint/key values; it contains cloud enabled plus HTTPS-shaped Supabase/contact-support endpoints. Metadata fetching also passes `NetworkUrlPolicy`, which requires HTTPS outside test-only loopback. This supports encrypted in transit `Yes` for this exact AAB.
- Delete account URL: use the public account deletion route from the deployed `web/invite-link/account-deletion/index.html`.
- Import-review artifact: `artifacts/full-go-audit/2026-08-13/google-play-data-safety-proposed.csv` is a BOM-prefixed, unsubmitted proposal preserving all 782 source response records and changing only `Response value`. It is not evidence of a Console import/save/submission.
- Console status: `READ_ONLY_VERIFIED / NOT_CHANGED / BLOCKED_EXTERNAL`. Console access is available through C0, but the current answers are incomplete/inaccurate for cloud-enabled v21 and were not changed or saved. Capture versionCode, before/after form screenshots, review timestamp, and post-change public Data safety page after correction.

## Validation Method
- Check submitted Android release `BuildConfig.SHARED_TAG_CLOUD_ENABLED`.
- Check submitted iOS `Info.plist` values for `SharedTagCloudEnabled`, `SupabaseURL`, and `SupabaseAnonKey` without displaying values. For 1.0.17 (19), this was completed: cloud disabled and relevant values empty.
- Verify `PrivacyInfo.xcprivacy` in both iOS app and share extension.
- Verify release manifest has no AdMob app ID, ad permissions, or ad provider declarations.
- Verify the submitted Android/iOS build shows the same redacted preview used for the ZIP, warns that unknown secrets may remain, and blocks generation/share until explicit confirmation.
- Verify the live Privacy Policy and App Review notes match the manual handoff in the submitted binary. Review current Google Play/App Store form definitions and save the owner-approved rationale without exposing user data or secrets.

## Failure Handling
- If the submitted binary disables shared-tag cloud and related endpoints, do not use the cloud-enabled privacy answers; separately ensure Store feature claims do not advertise disabled functionality.
- If ads are re-enabled or billing behavior changes, stop and rewrite this draft before submission.
- If any production value is missing, mark the submission as blocked rather than guessing.
- If local manual-handoff disclosure, preview/archive redaction parity, or unknown-secret warning/confirmation regresses, keep `NO_GO_INTERNAL`. Public-policy deployment/current store-form owner review remain Manual steps, but the build must not be submitted until those steps and signed-binary checks are complete.
- If admin audit/support/moderation handling, ChatGPT sync boundaries, or media-resolver wording remain unresolved, keep release status `NO_GO_INTERNAL` rather than treating this draft as ready.

## Evidence From Current Repo

| Area | Evidence | Current finding | Status |
|---|---|---|---|
| Android backup | `app/src/main/AndroidManifest.xml` has `android:allowBackup="false"` and `android:fullBackupContent="false"`. | Local saved URLs are not configured for Android cloud backup. | DONE |
| Android network | `android.permission.INTERNET` is present. | Required for metadata fetch and invite/shared-tag flows. | DONE |
| Android ads | `app/build.gradle.kts` release sets `ADS_ENABLED=false` and empty AdMob IDs; `app/src/release/AndroidManifest.xml` removes ad IDs/providers. | Release can be no-ads if built with default release settings. | DONE |
| Android shared-tag cloud | Exact signed `1.0.17 (21)` AAB hash matched the C0-provided digest; read-only DEX inspection found enabled shared-tag cloud plus HTTPS-shaped Supabase/contact-support endpoints without recording values. | Cloud-enabled exact-binary premise and encrypted-endpoint shape are verified. Secret values were not printed or retained. | EXACT_AAB_VERIFIED / CLOUD_ENABLED / HTTPS_ENDPOINTS |
| iOS privacy manifest | `ios/URLSaveriOS/PrivacyInfo.xcprivacy` and share extension privacy manifest have empty tracking arrays. | Supports no-tracking posture. Cloud account/contact/user-content disclosure applies only to an exact binary that enables those flows. | DONE |
| iOS shared-tag cloud | Signed `URLSaveriOS-20260728-1.0.17-distribution.xcarchive`: version/build/bundle/Team match public 1.0.17 (19), cloud disabled, Supabase/contact-support configuration empty. | The distributed binary is local-only. Keep `Data Not Collected` unless deeper exact-binary review finds another collection flow; fix the listing's cloud claims instead. | ARCHIVE_VERIFIED / APP_PRIVACY_MAJOR_REBUTTED / LISTING_MAJOR |
| Account deletion | `docs/account-deletion.md` and `web/invite-link/account-deletion/index.html` exist; current public-web deployment is `dpl_52Tc5XaiiLeV9F1EkxTLPXssqRmq`. | Public route contract is verified by `scripts/verify_public_web_release.sh`; enter/verify it in both consoles. | DONE_PUBLIC_CONTRACT / CONSOLE_RECHECK_REQUIRED |
| Public privacy policy | `web/invite-link/privacy/index.html` and `https://miyamibu.xyz/privacy/`; current deployment `dpl_52Tc5XaiiLeV9F1EkxTLPXssqRmq`. | Live policy discloses cloud account/shared-tag/support/purchase processing. | PUBLIC_VERIFIED / STORE_DECLARATIONS_CONFLICT |
| App Store public declaration | Official product URL plus matching signed distribution archive, direct 2026-08-13 check. | App Privacy `no data collected` is consistent with the local-only archive; the public cloud/sync/invite description is not. | PRIVACY_MAJOR_FALSE_POSITIVE / LISTING_ACCURACY_MAJOR / BLOCKED_EXTERNAL |
| Google Play public declaration and Console form | Official product URL plus C0's authenticated read-only Console check on 2026-08-13. | Cloud sync described; public/Step 5 says no data collected; Step 2 says collect/share yes; account creation is incorrectly `does not allow`; deletion is unanswered. No save/change was made. | CONFIRMED_MAJOR / BLOCKED_EXTERNAL / CONSOLE_READ_ONLY_VERIFIED |
| iOS localization | Current source, signed distribution archive, and public Store. | Source declares Japanese; archive contains development language English with no localization bundle; public Store correctly reports English for that archive. | CONFIRMED_MINOR / NEW_ARCHIVE_AND_PUBLIC_RECHECK_REQUIRED |

## Data Safety / App Privacy Input Matrix

| Release mode | Platform form item | Recommended answer | Evidence / caveat |
|---|---|---|---|
| Cloud-sharing | Tracking | No | No tracking SDK found in active app sources. |
| Cloud-sharing | Contact info | Collected: email for auth; name/email for optional support. Linked to account for auth/support unless the platform's exact criteria prove otherwise. | Supabase Auth and contact-support flow. |
| Cloud-sharing | User content | Collected: shared-tag names/URLs/collaboration records; optional support inquiry body. | Shared-tag cloud code, Supabase migrations, and support delivery. |
| Cloud-sharing | Identifiers | Collected and linked: user/account identifier/session used for auth and access control. | Supabase Auth/session. |
| Cloud-sharing | Device or other IDs | Collected: persistent shared-tag sync `client_id` and hashed support source IP. | `SharedTagSyncCoordinator`, `SharedTagSyncOperation`, `applied_client_ops`, and contact-support hash/audit paths. |
| Cloud-sharing | Other actions | Collected: durable shared-tag/group/member mutation types and outcomes. | Shared-tag sync operation contract and applied operation ledger. |
| Cloud-sharing | Purchases | Collected/processed: store product, purchase token/transaction, entitlement grant state. | Google Play Billing, StoreKit, and entitlement grant code. |
| Cloud-sharing | Data sharing | Shared-tag URLs visible to participants of the same shared tag; support email content sent through configured email delivery provider. | Product behavior and support delivery. |
| Cloud-sharing | Security | HTTPS is used for Supabase/contact-support and normal metadata endpoints; user-provided HTTP URLs may be opened/fetched as provided. | Do not overstate all data encrypted in transit. |
| Cloud-sharing | Account deletion | In-app account deletion plus public HTTPS deletion URL required. | `web/invite-link/account-deletion/index.html` must be deployed. |
| Any mode exposing manual ChatGPT handoff | User-directed external share | Review the current platform form definitions and exact binary; record the owner-approved answer rather than presuming developer collection/sharing. | Local ZIP + OS share, no app API/OAuth/MCP. Public policy and App Review disclosure are required before submission. |
| iOS 1.0.17 (19) local-only | App Privacy | Keep `Data Not Collected` unless final exact-binary review finds collection not covered by the archive configuration evidence. | Matching signed archive disables shared-tag cloud and has empty Supabase/contact-support configuration. |
| iOS 1.0.17 (19) local-only | Product description | Remove cloud/sync/invite claims, or replace the binary with a cloud-enabled build and use the cloud-sharing privacy rows above. | Public listing currently promises unavailable functionality. |

## Console Completion Evidence Required

- App Store Connect: exact app/version/build, confirm the local-only privacy answer, correct product-page feature claims, inspect Japanese localization, and capture the resulting public description/language. If a future build enables cloud, then complete every cloud data type/purpose/linkage answer.
- Play Console: exact package/versionCode/release, each collected/shared category, purpose, required/optional, encryption/deletion answers, SDK review, save/review state, and resulting public Data safety section.
- Store screenshots must hide account identifiers and personal data. Do not record credentials, cookies, tokens, or user records.
- Exit condition: Google Play no longer says no data is collected for the cloud-enabled Android release; App Store feature claims and localization match the local-only iOS archive (or a replacement accurately disclosed cloud build); both public pages identify the exact signed binaries reviewed.
