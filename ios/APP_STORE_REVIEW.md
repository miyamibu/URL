# URLSaver iOS App Store Review Notes

## Current release stance

- Release target is the local Phase 1a/1b URL saver only.
- Public iOS UI now keeps Main / Archive / Detail / manual add / Share Extension intact and adds one shared-tag cloud sheet entry point from Main plus a Detail 画面の shared tag 編集シート.
- Those additive sheets can expose sign in, sign up, invite acceptance, shared-tag create/detail/edit, shared-tag sync, and account deletion when cloud config is present.
- If a release enables cloud config, the account deletion requirement applies and the in-app delete button plus web deletion route must remain available.

## Build and simulator evidence

- Regenerate project: `ruby ios/generate_xcodeproj.rb`
- Discover destinations: `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -showdestinations`
- Verified simulator on 2026-04-23:
  - name: `iPhone 17 Pro Max`
  - id: `2021719A-3EE0-4D14-8EFF-39758C799300`
- Verified test command:
  - `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -destination 'platform=iOS Simulator,id=2021719A-3EE0-4D14-8EFF-39758C799300' test`
- Verified release-style build command:
  - `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`

## App Privacy source of truth

- Apple official references:
  - App Privacy details: <https://developer.apple.com/app-store/app-privacy-details/>
  - Privacy manifest: <https://developer.apple.com/documentation/bundleresources/adding-a-privacy-manifest-to-your-app-or-third-party-sdk>
  - Third-party SDK requirements: <https://developer.apple.com/support/third-party-SDK-requirements/>

- Repo evidence shows no ads, analytics SDKs, or tracking SDKs in `ios/`.
- Repo evidence shows no third-party SDK dependencies in the generated Xcode project; the iOS targets compile only app code plus Apple frameworks such as `BackgroundTasks`, `Security`, and `libsqlite3`.
- `PrivacyInfo.xcprivacy` is included in both:
  - `ios/URLSaveriOS/PrivacyInfo.xcprivacy`
  - `ios/URLSaverShareExtension/PrivacyInfo.xcprivacy`
- Current manifests declare:
  - tracking: `false`
  - collected data types: none declared
  - required-reason API categories: none declared

## Account deletion applicability

- Apple official reference:
  - <https://developer.apple.com/support/offering-account-deletion-in-your-app/>

- Apple requires in-app account deletion for apps that support account creation.
- Current repo state includes an additive shared-tag cloud sheet with sign-in/sign-up capability behind configuration.
- When cloud config is enabled for a shipped build, the account deletion requirement is triggered.
- This repo now includes an in-app deletion path in that sheet and the cross-platform source-of-truth remains `docs/account-deletion.md`.
- Repo source-of-truth for the cross-platform deletion policy now lives in `docs/account-deletion.md`.

## Third-party SDK requirement audit

- Apple requires privacy manifests for listed third-party SDKs when those SDKs are present.
- Current iOS repo state does not include listed third-party SDKs as binary or source dependencies.
- Therefore, the third-party SDK manifest/signature requirement does not currently add extra repo work beyond the app-owned privacy manifests.

## Upcoming submission requirement audit

- Apple official reference:
  - <https://developer.apple.com/news/upcoming-requirements/>

- Apple lists an upcoming requirement starting 2026-04-28: uploads to App Store Connect must be built with Xcode 26 or later using the iOS 26 SDK or later.
- Current validation environment uses Xcode 26.4.1 and iOS 26.4/26.4.1 simulator runtime, so the repo-side toolchain/SDK gate is satisfied as of 2026-04-23.

## External-only work that remains outside the repo

- App Store Connect privacy answers and privacy policy URL entry.
- Signing certificates, provisioning, archive export, and notarized submission artifacts.
- App Store screenshots, descriptions, age rating answers, and any public support/privacy URLs.
