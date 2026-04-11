# 1. Resolved Target
- Absolute path: `/Users/mimac/Desktop/🍎`
- Resolution method: `python3` and `ls -la ~/Desktop` were used to enumerate Desktop entries including hidden names. A directory with an exact name match `🍎` exists, so no fallback path selection was needed.

# 2. Executive Summary
結論は `H2. Device-side primary`、confidence は `High` です。`/Users/mimac/Desktop/🍎` 側には、Apple Watch / iPhone の接続不能を直接説明できる決定打は見つかりませんでした。一方で、ローカルの `devicectl`、`xctrace`、`watchOS DeviceSupport`、`remotepairingd` の証拠は一貫して、Watch 側の developer services / tunnel / control channel が成立していないことを示しています。フォルダー側で見つかった有意な問題は `WATCHOS_DEPLOYMENT_TARGET = 11.0` と実機 `watchOS 10.6.2` の不一致ですが、これは接続そのものよりも「接続後のデプロイ互換性」に効く種類の不整合で、今回の `Transport error`、`Disconnected`、`ddiServicesAvailable: false`、`Devices Offline` を主因として説明するには弱いです。

# 3. Evidence Table
| Evidence ID | Source path / command | What it shows | Supports H1/H2/H3/H4 | Strength |
| --- | --- | --- | --- | --- |
| E1 | `python3` listing of `~/Desktop`, `ls -la ~/Desktop` | Exact target resolves to `/Users/mimac/Desktop/🍎` | H2 | medium |
| E2 | [`apple_folder_inventory.tsv`](/Users/mimac/Documents/New%20project/apple_folder_inventory.tsv) | Folder is small and clean: 36 entries, 25 files, 9 dirs, 2 packages, 1 hidden file, 0 symlinks, 0 read failures | H2 | medium |
| E3 | `xcodebuild -list -project /Users/mimac/Desktop/🍎/LapTimer.xcodeproj` | Xcode project parses; targets/schemes `LapTimeriOS` and `LapTimerWatch` are present | H2 | strong |
| E4 | `plutil -p` on plists, `rg` on [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj) | Valid watch companion structure and embed phase exist | H2 | strong |
| E5 | `xcodebuild -showBuildSettings -target LapTimerWatch` | Project minimum watchOS is `11.0`, but observed watch is `10.6.2` | H3 | medium |
| E6 | `plutil -p` on entitlements, `rg DEVELOPMENT_TEAM` on [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj) | Automatic signing and consistent team/bundle IDs; no malformed entitlement key found | H2 | medium |
| E7 | `find`, `ls`, `stat`, `du` under `/Users/mimac/Library/Developer/Xcode/watchOS DeviceSupport` | Watch DeviceSupport is partial and lock-file-stuck, not finalized | H2 | strong |
| E8 | `find`, `ls`, `stat`, `du`, `plutil -p` under `/Users/mimac/Library/Developer/Xcode/iOS DeviceSupport` | iPhone DeviceSupport is finalized and healthy | H2 | strong |
| E9 | `xcrun devicectl list devices` | iPhone is `connected`; watch is `unavailable` | H2 | strong |
| E10 | `xcrun devicectl device info details` on iPhone | iPhone has `ddiServicesAvailable: true` and `tunnelState: connected` | H2 | strong |
| E11 | `xcrun devicectl device info details` on watch | Watch has `pairingState: paired` but `ddiServicesAvailable: false`, `tunnelState: unavailable`, and connection-establish failure | H2 | strong |
| E12 | `xcrun xctrace list devices` | Watch is under `Devices Offline` while iPhone remains online | H2 | strong |
| E13 | `/usr/bin/log show --last 2d --predicate 'process == "remotepairingd"'` | `kAMDPasswordProtectedError` and `Failed to listen for watches paired with ...` prove intermittent iPhone lock/trust blockage | H2 | medium |
| E14 | same `log show` query | Bonjour/RSD resolves watch endpoint, then connect attempts fail with `Connection refused` | H2 | strong |
| E15 | same `log show` query | Repeated `Transport error` and `Control channel connection timed out while in state preparing` for watch UDID | H2 | strong |
| E16 | `/Users/mimac/Desktop/CoreDevice-diagnose/devicectl-diagnose-watch-iphone.zip` | Saved diagnose bundle itself reports partial collection after diagnose failure | H2 | weak |
| E17 | `/Users/mimac/Desktop/CoreDevice-backup/db.sqlite.20260404-162608.bak`, `/Users/mimac/Desktop/CoreDevice-backup/db.sqlite.20260404-162628.bak` | Backup CoreDevice DB snapshots are stale/broken and do not show a stable watch row | H2 | weak |
| E18 | Folder search for `.env*`, `*.mobileprovision`, `Package.swift`, `Podfile`, `Cartfile`, README/log notes | No project-local artifact was found that plausibly explains watch transport failure | H2 | medium |

# 4. Folder Audit
## Top-level structure
- [`LapTimer.xcodeproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj): valid Xcode project package.
- [`LapTimerIOS`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimerIOS): iPhone app source folder with SwiftUI views and app entry point.
- [`LapTimerWatch`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimerWatch): watch app source folder with SwiftUI views and watch app entry point.
- [`LapTimerIOS-Info.plist`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimerIOS-Info.plist): iOS app plist.
- [`LapTimerWatch-Info.plist`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimerWatch-Info.plist): watch app plist.
- [`LapTimerIOS.entitlements`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimerIOS.entitlements), [`LapTimerWatch.entitlements`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimerWatch.entitlements): both present and empty.
- [`.DS_Store`](/Users/mimac/Desktop/%F0%9F%8D%8E/.DS_Store): Finder metadata only.

## Xcode and watch-specific findings
- [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj) declares two targets: `LapTimeriOS` and `LapTimerWatch`.
- [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj) includes `Embed Watch Content`, which is the expected iPhone-to-watch packaging relation.
- [`LapTimerWatch-Info.plist`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimerWatch-Info.plist) contains `WKApplication = true` and `WKCompanionAppBundleIdentifier = com.mimac.LapTimeriOS`, so the watch companion linkage is present.
- [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj) uses automatic signing with `DEVELOPMENT_TEAM = 8R3B5675ZJ` for both targets.
- Bundle IDs are structurally coherent: iOS `com.mimac.LapTimeriOS`, watch `com.mimac.LapTimeriOS.watchapp`.
- No `.mobileprovision`, custom signing script, CocoaPods, Carthage, SwiftPM manifest, Tuist, or xcodegen file was found.

## Folder-side cause candidates
- [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj): `WATCHOS_DEPLOYMENT_TARGET = 11.0`.
  - Why it matters: the observed physical watch OS in local environment evidence is `watchOS 10.6.2 (21U594)`.
  - What it can explain: deploy or run incompatibility after a healthy connection is established.
  - What it does not explain well: `Devices Offline`, `A connection to this device could not be established`, `Transport error`, `ddiServicesAvailable: false`, `tunnelState: unavailable`, or `Connection refused` in `remotepairingd`.

## Folder-side items that look non-causal
- Empty entitlements files do not show a malformed capability that would break transport before app installation.
- [`UserInterfaceState.xcuserstate`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate) contains local-user state and identifiers, but that affects IDE state, not watch tunnel bring-up.
- No broken symlink, no 0-byte file, no unreadable path, no unfinished download, no project-local log proving watch-side failure.

# 5. Local Environment Audit
## watchOS DeviceSupport
- Path inspected: `/Users/mimac/Library/Developer/Xcode/watchOS DeviceSupport/Watch5,1 10.6.2 (21U594)`
- Evidence:
  - residual `.copying_lock`
  - residual `.processing_lock`
  - only partial `.tmp/System/Library/Caches/com.apple.dyld/...`
  - total size about `6.6M`
- Interpretation: watch symbol/device-support extraction never completed successfully.

## iOS DeviceSupport
- Path inspected: `/Users/mimac/Library/Developer/Xcode/iOS DeviceSupport/iPhone13,2 26.4 (23E246)`
- Evidence:
  - `.finalized` exists
  - `Symbols` exists
  - large size about `5.5G`
  - `Info.plist` parses
- Interpretation: host-side Xcode support for the iPhone is healthy at the same time the watch support is not.

## CoreDevice
- Current live path `~/Library/Developer/CoreDevice` was absent at inspection time.
- Backup evidence exists at `/Users/mimac/Desktop/CoreDevice-backup`.
- Backup SQLite snapshots were readable but stale and incomplete, showing CoreDevice churn rather than a stable watch record.
- A diagnose archive at `/Users/mimac/Desktop/CoreDevice-diagnose/devicectl-diagnose-watch-iphone.zip` reports diagnose failure and partial collection only.

## devicectl and xctrace
- `xcrun devicectl list devices`:
  - iPhone: `connected`
  - watch: `unavailable`
- `xcrun devicectl device info details`:
  - iPhone: `ddiServicesAvailable: true`, `tunnelState: connected`
  - watch: warning that complete info cannot be retrieved, `A connection to this device could not be established`, `ddiServicesAvailable: false`, `pairingState: paired`, `tunnelState: unavailable`
- `xcrun xctrace list devices`:
  - watch appears in `Devices Offline`
- Interpretation: the host can see the watch identity, but cannot promote it into a usable developer-services connection.

## remotepairingd and unified logs
- Query used: `/usr/bin/log show --style compact --last 2d --predicate 'process == "remotepairingd"'`
- Observed:
  - `kAMDPasswordProtectedError`
  - `Failed to listen for watches paired with ...`
  - Bonjour/RSD resolution toward the watch endpoint
  - repeated `Connection refused`
  - repeated `Transport error`
  - repeated `Control channel connection timed out while in state preparing`
- Interpretation: the Mac discovers the watch and attempts the control channel, but the watch-side developer channel does not complete tunnel/control-channel bring-up. This matches the supplied case symptoms directly.

## Match to supplied symptoms
- Symptoms 1, 2, 8, 9, 10, 11, 12, 17, 18, 19, 20 are directly consistent with the current local command and log evidence.
- Symptoms 5, 6, 7 are supported by the partial `watchOS DeviceSupport` tree and lock remnants.
- Symptom 16 is supported by current `remotepairingd` log hits containing `kAMDPasswordProtectedError`.

# 6. Symptom-to-Cause Mapping
| Symptom | Folder-side explainable? | Device-side explainable? | Higher explanatory power |
| --- | --- | --- | --- |
| 1. Xcode shows Disconnected / Waiting to reconnect / Connecting | weak | strong | Device-side |
| 2. `A connection to this device could not be established.` / `Transport error` | weak | strong | Device-side |
| 3. Watch may need to be unlocked to recover from preparation errors | no direct folder evidence | strong | Device-side |
| 4. Same LAN / unlocked requirement warning | no | strong | Device-side |
| 5. Shared cache symbol copy reached then failed with HTTP 400 | weak | medium-strong | Device-side |
| 6. watchOS DeviceSupport left partial folder with lock files | no | strong | Device-side |
| 7. Xcode symbol cache / CoreDevice state broken for watch support | weak | strong | Device-side |
| 8. `xctrace` sees iPhone but watch offline | no | strong | Device-side |
| 9. `devicectl` shows iPhone connected, watch only available/paired or unavailable | no | strong | Device-side |
| 10. iPhone `ddiServicesAvailable: true`, watch `false` | no | strong | Device-side |
| 11. iPhone tunnel connected, watch tunnel disconnected/unavailable | no | strong | Device-side |
| 12. Pairing says paired but state unavailable | no | strong | Device-side |
| 13. `Control channel connection timed out while in state preparing` | no | strong | Device-side |
| 14. Bonjour resolution then `Connection refused` on watch control port | no | strong | Device-side |
| 15. Watch developer control service not accepting connections | no | strong | Device-side |
| 16. `kAMDPasswordProtectedError` after iPhone reboot | no | strong | Device-side |
| 17. Xcode temporarily shows `watchOS 0`, serial unknown | no | strong | Device-side |
| 18. `devicectl device reboot --device MIBUWatch` fails with transport error | no | strong | Device-side |
| 19. Manual pairing reset still returns only to paired/available, not connected | no | strong | Device-side |
| 20. DDI/CoreDevice/Xcode cache cleanup did not clear root symptom | weak | strong | Device-side |

補足:
- Folder-side で確認された `WATCHOS_DEPLOYMENT_TARGET = 11.0` は、上表の 1〜20 を主因としてはほぼ説明しません。これは接続後の実行互換性リスクです。

# 7. Final Attribution
## Primary cause
- `B. Apple Watch / iPhone / CoreDevice / remote pairing / developer services side`
- More precisely: watch-side developer-services/control-channel establishment failure, with intermittent iPhone lock/trust interference as an additional device-side destabilizer.

## Secondary contributors
- [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj) sets `WATCHOS_DEPLOYMENT_TARGET = 11.0`, which is higher than the observed watch `10.6.2`.
- This is a real project-side issue, but it is secondary because it explains deployment compatibility, not the observed transport/pairing failures.

## Non-causes
- Lack of `.git`, README, or package-manager files.
- Empty entitlements by themselves.
- `.DS_Store` and `xcuserdata` artifacts.
- Mac-to-iPhone connectivity in general, because the iPhone is currently `connected` with `ddiServicesAvailable: true`.
- Xcode age by itself, because host DDIs exist and the iPhone side works under the same Xcode.

## Unknowns
- The live watch UI state was not directly observed during this audit, so watch lock-screen state is inferred from logs, not visually confirmed.
- The specific reason the watch-side control service refuses connections cannot be fully determined from host-side logs alone.
- Current `~/Library/Developer/CoreDevice` was absent, so only backup snapshots were reviewable.
- No local file directly preserved the earlier `HTTP 400` symbol-download message; that point remains supported mainly by the supplied case context.

# 8. Auditor Notes
- The strongest possible counterargument to `H2` is the watchOS deployment-target mismatch, but that mismatch does not explain why the watch is offline in `xctrace`, unavailable in `devicectl`, and timing out in `remotepairingd` before app install.
- A different project folder would still hit the same class of failure if the watch stays `ddiServicesAvailable: false` and `tunnelState: unavailable`.
- What could overturn this conclusion: a second project on the same Mac connecting cleanly to the same watch, or a newly surfaced project-local signing/configuration file that directly breaks device-preparation, neither of which was found here.
- Residual uncertainty remains around the exact watch-side failure mode: locked watch, broken developer-services daemon, bad remote pairing state, or watch-side network/control service refusal. Those are all device-side subclasses, not folder-side primary causes.

# 9. Next Actions
1. Re-test device-side only, outside this project, by checking whether the same watch appears usable from `Devices and Simulators` with no project open.
2. Confirm the watch is unlocked, on-wrist trust state is satisfied, and the paired iPhone is unlocked before reconnect attempts.
3. Re-check live device state with `xcrun devicectl list devices` and watch details, looking specifically for `ddiServicesAvailable: true` and `tunnelState: connected`.
4. If device-side recovery is attempted, prioritize Watch/iPhone remote pairing recovery and developer-services recovery before touching project files.
5. After the watch is actually connectable, lower the watch project minimum version in [`project.pbxproj`](/Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj/project.pbxproj) to match supported test hardware if `watchOS 10.6.2` is a target device.
6. Keep folder-side cleanup separate from connection repair. Removing `xcuserdata` or `.DS_Store` is optional hygiene, not a fix for the current transport issue.
7. If the watch still refuses control-channel setup, capture a fresh host-side `remotepairingd` log window during a single clean reconnect attempt and compare whether `Connection refused` persists.
8. If available, compare with another Apple Watch or another Mac to isolate whether the failure follows the watch hardware/pairing state or this host environment.
