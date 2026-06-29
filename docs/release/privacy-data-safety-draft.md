# Privacy And Data Safety Draft

## Goal
提出版 `1.0.11` の privacy / Data safety 回答を、cloud 有効 binary と照合できる状態にする。

## Release Assumption
- Ads: disabled.
- Billing: enabled for Standard / Pro subscriptions through Google Play Billing and StoreKit.
- Third-party analytics: not used.
- Third-party crash reporting: not used.
- Shared-tag cloud: enabled for `1.0.11`.
- Account sign-in: enabled for shared-tag sync and collaboration.

## Data Stored Locally
- Saved URL, normalized URL, display URL.
- User title, memo, local tags.
- Fetched metadata such as page title, description, thumbnail URL, host, and service classification.
- Record state such as active, archived, pending delete.

## Network Access
- Metadata fetch may access user-provided pages and public oEmbed endpoints.
- Shared-tag URL data and shared-tag metadata are synced to Supabase when the user signs in and uses shared tags.
- If in-app contact support is enabled, the app sends the user's contact email, name, and inquiry body to the configured support endpoint for email delivery. The support log stores only minimal audit metadata such as request ID, hashed email/IP/user identifiers, platform, app version, build type, delivery provider/message ID, and delivery status; it does not store the raw inquiry body, raw email, or name in the database.

## Account Data
- Email address and authentication session are handled through Supabase Auth.
- Shared tag membership, role, invite, and owner transfer records are stored in Supabase.
- Account deletion is available in app and through a public deletion request page.

## App Store Privacy Draft
- Tracking: No.
- Data linked to user: email address for account authentication; shared-tag URLs and shared-tag metadata for sync/collaboration; contact-support email/name/inquiry body when the user submits support.
- Data not linked to user: minimal hashed support delivery/audit metadata may be retained for abuse prevention and delivery diagnostics.
- Required reason APIs: no app-owned categories currently declared in `PrivacyInfo.xcprivacy`.
- Account deletion: available in app and by public web request.

## Google Play Data Safety Draft
- Data collected: email address, user-provided shared-tag URLs, shared-tag metadata.
- Contact support data: email address, name, and inquiry body are transmitted for support email delivery when the user submits the contact form. Raw inquiry content is not retained in the support log table.
- Purchase data: store subscription product ID, purchase token / transaction ID, and entitlement grant state are processed to unlock paid plan features.
- Purpose: app functionality, account management, sync, purchase processing, entitlement management.
- Sharing: shared-tag URLs are visible to participants of the same shared tag.
- Delete account URL: use the public account deletion route from the deployed `web/invite-link/account-deletion/index.html`.

## Validation Method
- Check submitted Android release `BuildConfig.SHARED_TAG_CLOUD_ENABLED`.
- Check submitted iOS `Info.plist` values for `SharedTagCloudEnabled`, `SupabaseURL`, and `SupabaseAnonKey`.
- Verify `PrivacyInfo.xcprivacy` in both iOS app and share extension.
- Verify release manifest has no AdMob app ID, ad permissions, or ad provider declarations.

## Failure Handling
- If the submitted binary disables shared-tag cloud, do not use the cloud-enabled privacy answers.
- If ads are re-enabled or billing behavior changes, stop and rewrite this draft before submission.
- If any production value is missing, mark the submission as blocked rather than guessing.

## Evidence From Current Repo

| Area | Evidence | Current finding | Status |
|---|---|---|---|
| Android backup | `app/src/main/AndroidManifest.xml` has `android:allowBackup="false"` and `android:fullBackupContent="false"`. | Local saved URLs are not configured for Android cloud backup. | DONE |
| Android network | `android.permission.INTERNET` is present. | Required for metadata fetch and invite/shared-tag flows. | DONE |
| Android ads | `app/build.gradle.kts` release sets `ADS_ENABLED=false` and empty AdMob IDs; `app/src/release/AndroidManifest.xml` removes ad IDs/providers. | Release can be no-ads if built with default release settings. | DONE |
| Android shared-tag cloud | `app/build.gradle.kts` release reads `release.shared.tag.cloud.enabled`; current local release config sets it true. | Android submitted build is cloud-enabled when built from this machine's release config. | DONE |
| iOS privacy manifest | `ios/URLSaveriOS/PrivacyInfo.xcprivacy` and share extension privacy manifest have empty tracking arrays. | App Store privacy labels must disclose cloud account/contact/user-content data even though no tracking SDK is present. | DONE |
| iOS shared-tag cloud | `ios/URLSaveriOS.xcodeproj/project.pbxproj` app Debug/Release use `ios/Config/URLSaverSecrets.xcconfig`; current Release build settings show cloud true and production Supabase/contact-support values. | iOS submitted build is cloud-enabled on this machine. | DONE |
| Account deletion | `docs/account-deletion.md` and `web/invite-link/account-deletion/index.html` exist for cloud release; `https://miyamibu.xyz/account-deletion/` returned HTTP 200 on 2026-06-29. | Public deletion route is reachable; enter the URL in store consoles and keep it aligned with account features. | DONE_PUBLIC_200 |
| Public privacy policy | `web/invite-link/privacy/index.html` source discloses Standard / Pro subscriptions, Google Play Billing, and StoreKit. | `./scripts/verify_public_web_release.sh` passed on 2026-06-29; live `https://miyamibu.xyz/privacy/` returns HTTP 200 and no longer contains stale no-real-billing wording. | DONE_PUBLIC_VERIFIED |

## Data Safety / App Privacy Input Matrix

| Release mode | Platform form item | Recommended answer | Evidence / caveat |
|---|---|---|---|
| Cloud-sharing | Tracking | No | No tracking SDK found in active app sources. |
| Cloud-sharing | Contact info | Email address collected for account authentication; email/name/inquiry body transmitted for contact support. | Supabase Auth and contact-support function. |
| Cloud-sharing | User content | Shared-tag names and shared-tag URLs collected for sync/collaboration. | Shared-tag cloud code and Supabase migrations. |
| Cloud-sharing | Identifiers | User/account identifier/session used for auth and access control. | Supabase Auth/session. |
| Cloud-sharing | Purchases | Store subscription product ID and purchase token / transaction ID are processed for entitlement grants. | Google Play Billing, StoreKit, and entitlement grant code. |
| Cloud-sharing | Data sharing | Shared-tag URLs visible to participants of the same shared tag; support email content sent through configured email delivery provider. | Product behavior and support delivery. |
| Cloud-sharing | Security | HTTPS is used for Supabase/contact-support and normal metadata endpoints; user-provided HTTP URLs may be opened/fetched as provided. | Do not overstate all data encrypted in transit. |
| Cloud-sharing | Account deletion | In-app account deletion plus public HTTPS deletion URL required. | `web/invite-link/account-deletion/index.html` must be deployed. |
