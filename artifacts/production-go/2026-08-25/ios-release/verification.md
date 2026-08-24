# iOS 1.0.19 (21) release artifact verification

Verified on 2026-08-25 JST from the current repository source. This record contains no account email, password, passkey, two-factor code, private key, token, or provisioning-profile payload.

## Source identity

- App bundle: `com.mibu.codebridge.ios`
- Share Extension bundle: `com.mibu.codebridge.ios.share`
- App and Share Extension version: `1.0.19 (21)`
- Apple Team: `8R3B5675ZJ`
- App Group: `group.jp.mimac.urlsaver`

## Archive, export, and exact artifact

- Release archive: `/tmp/rinbam-1.0.19-21-team.xcarchive`
- App Store export: `/tmp/rinbam-app-store-export-1.0.19-21/りんばむ.ipa`
- IPA SHA-256: `105ebe7263f8e87e56fffee109370feb3c9c38f04249edf8ba707635608a5b89`
- `xcodebuild archive` result: `** ARCHIVE SUCCEEDED **`
- App Store distribution export result: `** EXPORT SUCCEEDED **`

The exported IPA was unpacked and inspected. Its app and Share Extension have the canonical bundle IDs and version above, use Apple Distribution signing, include the expected App Group, and both have `get-task-allow=false`.

The exported app configuration is the approved local-only release mode:

- Shared-tag cloud: disabled
- ChatGPT personal-link sync: disabled
- AI transparency feature: disabled
- Supabase URL and anonymous key: empty
- Contact-support endpoint: empty
- Media-resolver backend: empty

## App Store Connect upload

The same archive was exported with App Store Connect upload destination. `xcodebuild -exportArchive` reported `Upload succeeded`, `Uploaded URLSaveriOS`, and `** EXPORT SUCCEEDED **`.

The archive distribution record identifies App Store item `6771251450`, uploaded build `21`, destination `upload`, event state `success`, and event title `Uploaded to Apple` at `2026-08-24T21:56:36Z` (`2026-08-25 06:56:36 JST`). This proves binary acceptance for processing, not version creation, build attachment, review submission, approval, or public propagation.

## Evidence boundaries

- The `.xcarchive` product is the pre-export development-signed archive and has `get-task-allow=true`; this is not the distribution entitlement state.
- The exported IPA is the distribution artifact and has `get-task-allow=false` for both app and Share Extension.
- At `2026-08-25 07:25 JST`, the canonical `1.0.19 (21)` development-signed app from the archive was overwrite-installed on physical iPhone 12 CoreDevice `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E` and launched as `com.mibu.codebridge.ios`. No uninstall, app-data reset, or user-data deletion was performed. This is install/launch evidence for the current version, not proof that the distribution IPA was installed.
- App Store Connect Web authentication remained at the Apple Account sign-in screen. No credential, passkey, or two-factor action was performed by the coordinator.
- Public App Store version remained `1.0.18` when checked. Version `1.0.19` had not been created, build 21 had not been attached, and review had not been submitted.
- Exact version `1.0.19 (21)` was not operated through Appium/WebDriverAgent on the physical iPhone. Appium port 4723 and RemoteXPC tunnel port 42314 were both unavailable; administrator access is required to start the tunnel. Install/launch and archive/export/upload success are not physical-device UI proof.
