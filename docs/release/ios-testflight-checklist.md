# iOS TestFlight Checklist

## Scope
Prepare iOS TestFlight without Codex uploading to App Store Connect.

## Apple Developer Team

- [ ] Confirm the Apple Developer Team is selected in Xcode or xcodebuild settings.
- [ ] Confirm app bundle ID `com.mibu.codebridge.ios`.
- [ ] Confirm share extension bundle ID `com.mibu.codebridge.ios.share`.
- Stop if signing points to a non-canonical bundle or wrong team.

## Signing Certificate and Provisioning

- Distribution certificate and provisioning profiles must be managed outside the repo.
- Do not commit `.mobileprovision`, `.p12`, certificates, passwords, or App Store Connect API keys.
- Stop if signing material appears in git or chat.

## Unsigned Code Build

```bash
xcodebuild \
  -project ios/URLSaveriOS.xcodeproj \
  -scheme URLSaveriOS \
  -configuration Release \
  CODE_SIGNING_ALLOWED=NO \
  clean build
```

Expected: code compiles without signing.

## Signed Archive Command

Manual owner command example. Fill signing values in the shell or Xcode, not repo files.

```bash
xcodebuild \
  -project ios/URLSaveriOS.xcodeproj \
  -scheme URLSaveriOS \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  archive \
  -archivePath build/archives/URLSaveriOS-<version>-<build>.xcarchive
```

If cloud-sharing production config is needed, pass the ignored secret xcconfig explicitly. For local-only release validation, use the tracked local-only config.

## App Store Connect Upload Steps

Codex does not upload to App Store Connect. Manual owner steps:

1. Open Organizer or use approved `xcodebuild -exportArchive` / Transporter flow.
2. Upload the archive.
3. Confirm version/build and bundle IDs.
4. Wait for processing.
5. Add build to internal TestFlight group.
6. Save screenshots of build metadata and processing status.

## TestFlight Internal Testing Steps

- Install the TestFlight build on a physical iPhone.
- Run `docs/release/manual-qa-matrix.md` iOS section.
- Include Share Extension, Export, Dynamic Type, feature flag off, local/account deletion, and crash-free smoke.

## App Privacy

- Confirm Privacy Nutrition Labels match `docs/release/privacy-policy-and-store-disclosure-checklist.md`.
- Confirm no undisclosed SDK or data category was added.
- Confirm AI/MCP disclosures mention selected saved URLs only and local-only Receipt/Draft storage.

## Feature Flag Default Off

- Expected: normal TestFlight UI does not expose unapproved AI entry.
- Stop if AI transparency UI is public without explicit release approval.

## AI-safe Export

- Inspect export payload for `publicSafeId`, `aiEligible`, redaction/excerpt fields, and `savedSnapshotNotice`.
- Stop if raw `fetchedBody`, prompt, token, refresh token, attachment, or raw DB id appears.

## Rollback / Expire Build

Manual App Store Connect action:

1. Stop testing the bad build or expire it.
2. Keep previous known-good build active for testers if available.
3. Disable MCP/AI flags if backend related.
4. Add incident note and user communication draft from `docs/release/rollback-plan.md`.

## Stop Conditions

- Crash on launch, Share Extension, save, or export.
- Data loss after upgrade.
- Wrong bundle/team/signing.
- Privacy manifest/App Privacy mismatch.
- Raw body/prompt/token exposure.
- Normal UI exposes AI while flag off.
