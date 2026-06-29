---
name: iphone-device-install
description: Use this skill whenever installing りんばむ on a physical iPhone. Prefer the previously signed Debug-iphoneos .app route with devicectl before trying a fresh Xcode device build.
---

# iPhone Device Install

## Goal
Install りんばむ on the paired physical iPhone without getting blocked by Xcode account or provisioning setup when a usable signed device build already exists.

## Context
This repo has an iOS project at `ios/URLSaveriOS.xcodeproj`. A direct `xcodebuild` device build can fail with missing Xcode account or provisioning profiles, even when a previously signed `Debug-iphoneos/URLSaveriOS.app` is still installable.

The canonical current app identity is:
- Bundle ID: `com.mibu.codebridge.ios`.
- Share extension Bundle ID: `com.mibu.codebridge.ios.share`.

The historical working route from 2026-05-01 used an old development bundle ID and is no longer the user-facing target:
- Device: iPhone 12 shown by `devicectl` as paired/available.
- Historical Bundle ID: `jp.mimac.urlsaver.ios`.
- Reinstall a signed `.app` from Xcode DerivedData with `xcrun devicectl device install app`.
- Confirm with `xcrun devicectl device info apps`.
- For a fresh current-source physical build, passing `DEVELOPMENT_TEAM=8R3B5675ZJ -allowProvisioningUpdates` allowed Xcode to create/use the local development profile without editing project signing settings.

## Constraints
- Do not edit signing settings, bundle IDs, entitlements, project files, or release configuration just to install on the iPhone unless the user explicitly asks.
- Do not delete DerivedData, provisioning profiles, app data, or old build outputs as part of this flow.
- Prefer an existing signed `Debug-iphoneos` app before attempting a fresh physical-device `xcodebuild`.
- Simulator builds under `Debug-iphonesimulator` cannot be installed on the physical iPhone.
- If multiple signed device apps exist, choose the newest signed `Debug-iphoneos/URLSaveriOS.app`.

## Workflow
1. Find the paired iPhone device identifier:

```bash
xcrun devicectl list devices
```

2. Check whether りんばむ is already installed:

```bash
xcrun devicectl device info apps --device <DEVICE_ID> 2>/dev/null | rg -i 'りんばむ|com\.mibu\.codebridge\.ios' || true
```

3. Find existing physical-device builds:

```bash
find "$HOME/Library/Developer/Xcode/DerivedData" \
  -path '*/Build/Products/Debug-iphoneos/URLSaveriOS.app' \
  -type d 2>/dev/null
```

4. Pick the newest candidate and verify it is signed for iPhoneOS:

```bash
for app in "$HOME"/Library/Developer/Xcode/DerivedData/URLSaveriOS-*/Build/Products/Debug-iphoneos/URLSaveriOS.app; do
  [ -d "$app" ] && stat -f '%Sm %N' -t '%Y-%m-%d %H:%M:%S' "$app"
done | sort

/usr/bin/codesign -dv "<APP_PATH>" 2>&1 | sed -n '1,24p'
```

Expected signs:
- `Identifier=com.mibu.codebridge.ios`
- `Format=app bundle with Mach-O thin (arm64)`
- `TeamIdentifier=...`

5. Install the signed app directly:

```bash
xcrun devicectl device install app --device <DEVICE_ID> "<APP_PATH>"
```

6. Confirm installation:

```bash
xcrun devicectl device info apps --device <DEVICE_ID> 2>/dev/null | rg -i 'りんばむ|com\.mibu\.codebridge\.ios'
```

## Fresh build fallback
Only if no usable signed `Debug-iphoneos` app exists, try a fresh build. First inspect local signing identity:

```bash
security find-identity -p codesigning -v
```

Then try `xcodebuild` with explicit team only when the team/account is known and available:

```bash
xcodebuild \
  -project ios/URLSaveriOS.xcodeproj \
  -scheme URLSaveriOS \
  -configuration Debug \
  -destination 'id=<DEVICE_ID>' \
  DEVELOPMENT_TEAM=8R3B5675ZJ \
  -allowProvisioningUpdates \
  build
```

If this succeeds, install the current signed device build:

```bash
xcrun devicectl device install app \
  --device <DEVICE_ID> \
  "$HOME/Library/Developer/Xcode/DerivedData/<CURRENT_DERIVED_DATA>/Build/Products/Debug-iphoneos/URLSaveriOS.app"
```

If it fails with `No Account for Team` or `No profiles for ... were found`, stop and report that Xcode account/provisioning must be repaired instead of changing project signing settings automatically.

## Done when
- `devicectl device install app` reports `App installed` for `com.mibu.codebridge.ios`.
- `devicectl device info apps` lists `りんばむ    com.mibu.codebridge.ios`.
- Final response says which app path was installed and whether Android was also installed if that was part of the user request.

## Output format
- Install result
- Device ID used
- App path used
- Verification result
- Any remaining signing/profile issue, if relevant

## Failure handling
- If no paired iPhone is available, ask the user to connect/unlock/trust the iPhone and rerun `devicectl list devices`.
- If no signed `Debug-iphoneos` app exists, report that a fresh signed build is needed and include the exact Xcode signing error.
- If install fails despite a signed app, preserve the error output and do not delete or reset DerivedData without explicit approval.
