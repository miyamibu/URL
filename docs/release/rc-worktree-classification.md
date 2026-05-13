# RC Worktree Classification

## Date
2026-05-13

## Current commit
`7a580e4 Document local-only launch prep and miyamibu.xyz readiness`

## Scope
This report classifies the remaining dirty worktree after committing the narrow
local-only launch prep, `miyamibu.xyz` readiness, and iOS ignored xcconfig guard.

The classification baseline was created on 2026-05-11 and reviewed for this
standalone report commit on 2026-05-13. No app runtime, UI, shared-tag,
artifact, or deletion-candidate files were deleted, restored, moved, staged,
committed, or pushed as part of this report commit.

## Category summary

| Category | Count / scope | Recommended next action |
|---|---:|---|
| RC_INCLUDE_CANDIDATE | Android/iOS local-only runtime, tag UI/data, export, metadata text, app icon/profile image, tests, schemas, and launch evidence | Review as one or more separate RC commits only after owner accepts the product/runtime scope. |
| RC_EXCLUDE_CANDIDATE | Non-launch repo instruction churn and web-local Vercel ignore file | Keep out of RC unless separately justified. |
| CONTRACT_DEPENDENT | Associated domains / placeholder config that depends on paid Apple/Google values | Do not finalize until Apple Team ID and Play App Signing SHA-256 are issued. |
| EVIDENCE_ONLY | Store assets and UI review artifacts | Keep as evidence or explicitly stage selected final assets only after owner approval. |
| DESTRUCTIVE_OR_RESTORE_NEEDS_APPROVAL | Deleted files under `CODEX_INSTRUCTIONS.md`, `archive/root-unrelated/**`, old `artifacts/ui-review/2026-04-30/**`, and `tmp/laptimer-*.png` | Do not restore or delete-finalize without explicit approval. |
| UNKNOWN_NEEDS_REVIEW | Large regenerated iOS project file and broad app implementation changes whose intended final scope is not isolated yet | Inspect diffs and split before staging. |

## Path classification

| Category | Paths | Reason | Recommended next action |
|---|---|---|---|
| RC_INCLUDE_CANDIDATE | `app/build.gradle.kts`, `local.properties.example`, `ios/Config/URLSaverSecrets.xcconfig.example` | Local-only release defaults, invite base URL, and cloud-disabled examples appear launch-relevant. | Review together with runtime changes; stage only if still required after final RC decision. |
| RC_INCLUDE_CANDIDATE | `app/src/main/AndroidManifest.xml`, `app/src/main/java/jp/mimac/urlsaver/ads/AdsConfig.kt` | Ads/local-only behavior appears launch-relevant. | Keep candidate; verify no release ads or billing before staging. |
| RC_INCLUDE_CANDIDATE | `app/src/main/java/jp/mimac/urlsaver/data/**`, including `LocalTagCollectionEntryRef.kt` and `app/schemas/jp.mimac.urlsaver.data.AppDatabase/13.json` | Tag/local collection schema and repository changes affect saved URL behavior. | Treat as app-runtime RC candidate; stage only with migration/test review. |
| RC_INCLUDE_CANDIDATE | `app/src/main/java/jp/mimac/urlsaver/ui/**`, `app/src/main/java/jp/mimac/urlsaver/ui/components/**` | Main list, tag, detail, export, archive, and local-only UI behavior. | Needs focused UI/runtime review and device evidence before RC inclusion. |
| RC_INCLUDE_CANDIDATE | `app/src/test/java/jp/mimac/urlsaver/**`, `app/src/androidTest/java/jp/mimac/urlsaver/Phase1aFlowTest.kt` | Tests for export, metadata text, migrations, tag behavior, shared-tag auth/sync. | Include only with matching runtime changes; do not stage tests alone if implementation remains unstaged. |
| RC_INCLUDE_CANDIDATE | `app/src/main/res/drawable-*/ic_launcher_foreground.png`, `app/src/main/res/mipmap-*/ic_launcher*.png`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml`, `app/src/main/res/values/colors.xml`, `app/src/main/res/drawable-nodpi/default_profile_pig.png` | App icon/profile visual assets. | Owner visual approval required before RC inclusion. |
| RC_INCLUDE_CANDIDATE | `ios/URLSaveriOS/Assets.xcassets/**`, `ios/URLSaveriOS/DefaultProfilePig.png` | iOS app icon and default profile visual assets. | Owner visual approval required before RC inclusion. |
| RC_INCLUDE_CANDIDATE | `ios/URLSaverShared/**`, `ios/URLSaveriOS/App/**`, `ios/URLSaveriOS/UI/**`, `ios/URLSaveriOSTests/**`, `ios/generate_xcodeproj.rb` | iOS local-only/shared-tag/profile/export UI and tests. | Split into coherent iOS RC commit only after physical or simulator visual review. |
| UNKNOWN_NEEDS_REVIEW | `ios/URLSaveriOS.xcodeproj/project.pbxproj`, `ios/URLSaveriOS.xcodeproj/xcshareddata/xcschemes/URLSaveriOS.xcscheme` | Large generated project/scheme churn and added test references. | Review generated diff carefully; stage only if it matches accepted source/test additions. |
| CONTRACT_DEPENDENT | `ios/URLSaveriOS/URLSaveriOS.entitlements` | Associated Domains and App Groups can depend on final paid Team/App ID setup. | Keep pending until paid Apple Team ID and final app IDs are confirmed. |
| RC_INCLUDE_CANDIDATE | `docs/account-deletion.md` | Future cloud/account deletion docs changed, but local-only v1.0 has no account creation. | Review wording; likely defer unless needed to avoid store review ambiguity. |
| RC_EXCLUDE_CANDIDATE | `AGENTS.md` | Repo instruction churn is not part of launch binary or public web readiness. | Keep out of RC unless separately requested. |
| RC_EXCLUDE_CANDIDATE | `.agents/skills/iphone-device-install/SKILL.md` | Local Codex workflow helper; not app/store artifact. | Keep out of RC unless user wants repo-local skill committed. |
| RC_EXCLUDE_CANDIDATE | `web/invite-link/.gitignore` | Web deployment helper ignore file; not needed for already committed public readiness unless reviewed. | Keep out unless there is a concrete deploy hygiene reason. |
| EVIDENCE_ONLY | `artifacts/store-assets/**` | Feature graphic and partial screenshots/evidence. | Stage only selected final store assets after owner approval. |
| EVIDENCE_ONLY | `artifacts/ui-review/2026-05-01/**`, `artifacts/ui-review/2026-05-11/**` | UI/device evidence, including release-equivalent Android evidence from this run. | Keep uncommitted unless evidence policy requires selected artifacts in RC. |
| EVIDENCE_ONLY | `artifacts/app-icon/**` | App icon source/master evidence. | Stage only if chosen as canonical source asset. |
| DESTRUCTIVE_OR_RESTORE_NEEDS_APPROVAL | `CODEX_INSTRUCTIONS.md` | Tracked deletion candidate. | Do not restore or delete-finalize without explicit approval. |
| DESTRUCTIVE_OR_RESTORE_NEEDS_APPROVAL | `archive/root-unrelated/**` | Tracked deletions of archived audit/inventory files. | Do not restore or delete-finalize without explicit approval. |
| DESTRUCTIVE_OR_RESTORE_NEEDS_APPROVAL | `artifacts/ui-review/2026-04-30/**` | Tracked deletion of prior UI evidence screenshots. | Do not restore or delete-finalize without explicit approval. |
| DESTRUCTIVE_OR_RESTORE_NEEDS_APPROVAL | `tmp/laptimer-ios*.png`, `tmp/laptimer-watch*.png` | Tracked deletion of unrelated LapTimer screenshots. | Do not restore or delete-finalize without explicit approval. |

## Must not delete or restore without approval

- `CODEX_INSTRUCTIONS.md`
- `archive/root-unrelated/**`
- `artifacts/ui-review/2026-04-30/**`
- `tmp/laptimer-ios-2.png`
- `tmp/laptimer-ios.png`
- `tmp/laptimer-watch-2.png`
- `tmp/laptimer-watch.png`
- Any existing app data on physical Android or iPhone devices
- Any ignored local config under `ios/Config/URLSaverSecrets*.xcconfig`

## Remaining blockers before final store submission

- Apple Developer Program / final paid Team ID.
- App Store distribution certificate and provisioning profiles for app and share extension.
- App Store Connect app record and SKU.
- Google Play Console app record.
- Play App Signing enrollment and final SHA-256 for `assetlinks.json`.
- Final Universal Links / App Links verification with issued Apple/Google values.
- Final approved store screenshot set.
- Owner decision on whether the remaining Android/iOS runtime, UI, icon, profile, and evidence changes are part of the launch RC.
