# Privacy And Data Safety Draft

## Goal
契約前に privacy / Data safety 回答の下書きを作り、提出直前に binary と公開方針へ照合できる状態にする。

## Initial Release Assumption
- Ads: disabled.
- Billing: disabled.
- Third-party analytics: not used.
- Third-party crash reporting: not used.
- Shared-tag cloud: disabled for initial v1.0 release. Production Supabase/shared-tag cloud is v1.1-or-later scope.

## Data Stored Locally
- Saved URL, normalized URL, display URL.
- User title, memo, local tags.
- Fetched metadata such as page title, description, thumbnail URL, host, and service classification.
- Record state such as active, archived, pending delete.

## Network Access
- Metadata fetch may access user-provided pages and public oEmbed endpoints.
- Saved URL data is not synced to Supabase in the initial v1.0 release.

## Account Data
Not applicable for local-only v1.0 because account creation/login is not exposed.

Only applicable for a future shared-tag cloud release:
- Email address and authentication session are handled through Supabase Auth.
- Shared tag membership, role, invite, and owner transfer records are stored in Supabase.
- Account deletion is available in app and through a public deletion request page.

## App Store Privacy Draft
For initial local-only release:
- Tracking: No.
- Data linked to user: None declared for local-only use.
- Data not linked to user: Consider whether diagnostics are collected by Apple platform services only; no app-owned third-party diagnostics are integrated.
- Required reason APIs: no app-owned categories currently declared in `PrivacyInfo.xcprivacy`.

For shared-tag cloud release:
- Contact info: email address, used for account authentication.
- User content: URLs inside shared tags and shared-tag names, used for cloud sync and collaboration.
- Identifiers: Supabase user ID/session, used for authentication and access control.
- Account deletion: available in app and by public web request.

## Google Play Data Safety Draft
For initial local-only release:
- Data collection: No app-owned external collection for saved URLs.
- Data sharing: No app-owned third-party sharing.
- Security practices: data is stored locally; Android backup is disabled.
- Delete account URL: not applicable because account creation is not enabled.

For shared-tag cloud release:
- Data collected: email address, user-provided shared-tag URLs, shared-tag metadata.
- Purpose: app functionality, account management, sync.
- Sharing: shared-tag URLs are visible to participants of the same shared tag.
- Delete account URL: use the public account deletion route from the deployed `docs/account-deletion-request.html`.

## Validation Method
- Check submitted Android release `BuildConfig.SHARED_TAG_CLOUD_ENABLED`.
- Check submitted iOS `Info.plist` values for `SharedTagCloudEnabled`, `SupabaseURL`, and `SupabaseAnonKey`.
- Verify `PrivacyInfo.xcprivacy` in both iOS app and share extension.
- Verify release manifest has no AdMob app ID, ad permissions, or ad provider declarations.

## Failure Handling
- If the submitted binary enables shared-tag cloud, do not use the local-only privacy answers.
- If ads or billing are re-enabled, stop and rewrite this draft before submission.
- If any production value is missing, mark the submission as blocked rather than guessing.

## Evidence From Current Repo

| Area | Evidence | Current finding | Status |
|---|---|---|---|
| Android backup | `app/src/main/AndroidManifest.xml` has `android:allowBackup="false"` and `android:fullBackupContent="false"`. | Local saved URLs are not configured for Android cloud backup. | DONE |
| Android network | `android.permission.INTERNET` is present. | Required for metadata fetch and invite/shared-tag flows. | DONE |
| Android ads | `app/build.gradle.kts` release sets `ADS_ENABLED=false` and empty AdMob IDs; `app/src/release/AndroidManifest.xml` removes ad IDs/providers. | Release can be no-ads if built with default release settings. | DONE |
| Android shared-tag cloud | `app/build.gradle.kts` release reads `release.shared.tag.cloud.enabled`; default is false. | Android release can be local-only unless release cloud flag is set. | DONE |
| iOS privacy manifest | `ios/URLSaveriOS/PrivacyInfo.xcprivacy` and share extension privacy manifest have empty collection arrays and tracking false. | Fits local-only posture, but does not fit cloud-sharing release unless App Store privacy answers disclose cloud data collection. | DONE |
| iOS shared-tag cloud | `ios/URLSaveriOS.xcodeproj/project.pbxproj` app Debug/Release set `URLSAVER_SHARED_TAG_CLOUD_ENABLED=false`, `URLSAVER_SUPABASE_URL=""`, `URLSAVER_SUPABASE_ANON_KEY=""`. | iOS submitted build is local-only unless these build settings are intentionally changed for v1.1. | DONE |
| Account deletion | `docs/account-deletion.md` and `docs/account-deletion-request.html` exist for future cloud release. | Not applicable for local-only v1.0 because no account creation/login is exposed. | NOT_APPLICABLE |
| Public privacy policy | `web/invite-link/privacy/index.html` exists and names URL Saver plus contact address. | Needs final public deployment verification before store entry. | NEEDS_USER_ACTION |

## Data Safety / App Privacy Input Matrix

| Release mode | Platform form item | Recommended answer | Evidence / caveat |
|---|---|---|---|
| Local-only | Tracking | No | No tracking SDK found in active app sources. |
| Local-only | Data collected | No app-owned external collection for saved URLs. | URLs, memo, tags remain local; metadata requests may contact user-provided URLs. |
| Local-only | Data shared | No app-owned sharing. | No cloud sync when shared-tag cloud is disabled. |
| Local-only | Security | Data in transit is encrypted for HTTPS metadata endpoints; user-provided HTTP URLs may be opened/fetched as provided. | Do not overstate "all data encrypted in transit" if HTTP user URLs are supported. |
| Local-only | Account deletion | Not applicable. | Account creation must not be exposed. |
| Cloud-sharing | Contact info | Email address collected for account authentication. | Supabase Auth. |
| Cloud-sharing | User content | Shared-tag names and shared-tag URLs collected for sync/collaboration. | Shared-tag cloud code and Supabase migrations. |
| Cloud-sharing | Identifiers | User/account identifier/session used for auth and access control. | Supabase Auth/session. |
| Cloud-sharing | Data sharing | Shared-tag URLs visible to participants of that shared tag. | Product behavior; disclose as app functionality. |
| Cloud-sharing | Account deletion | In-app account deletion plus public HTTPS deletion URL required. | `docs/account-deletion-request.html` must be deployed. |
