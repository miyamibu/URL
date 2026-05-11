# Link And Signing Placeholders

## Goal
契約後に差し込む App Links / Universal Links / signing 値を明確にし、契約前に推測で固定しない。

## Android App Links
- File: `web/invite-link/.well-known/assetlinks.json`
- Current package: `jp.mimac.urlsaver`
- Contract-dependent value: Google Play App Signing SHA-256 certificate fingerprint.
- Current SHA-256 is beta/debug evidence only until Play Console confirms the final app signing certificate.

## iOS Universal Links
- File: `web/invite-link/.well-known/apple-app-site-association`
- Current bundle ID: `jp.mimac.urlsaver.ios`
- Contract-dependent value: Apple Team ID.
- Current Team ID is beta/provisioning evidence only until the paid Apple Developer team is confirmed.

## Android Signing
- Contract-dependent values:
  - Play App Signing enrollment.
  - Upload key and upload certificate fingerprint.
  - Production/release track access.
- Contract-free prep:
  - Keep package name stable.
  - Keep `assetlinks.json` structurally valid.
  - Keep release artifact inspection documented.

## iOS Signing
- Contract-dependent values:
  - Apple Developer Team ID.
  - App ID with App Groups and Associated Domains.
  - App and Share Extension provisioning profiles.
  - Distribution certificate.
- Contract-free prep:
  - Keep bundle identifiers stable.
  - Keep entitlements checked in.
  - Keep release-style generic build passing with `CODE_SIGNING_ALLOWED=NO`.

## Supabase
- Contract-dependent values:
  - Production project URL.
  - Publishable / anon key.
  - Production migration state.
  - Auth provider settings.
- Never put `service_role`, `sb_secret`, Apple private keys, Google private keys, or passwords in tracked files.

## Done When
- After contracts are complete, replace only the explicit placeholders with issued values.
- Re-run App Links / Universal Links verification on real devices.
- Re-run release build and store privacy/Data safety checks against the final binary.
