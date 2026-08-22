# Sol MAX S1 raw record — Android connected-test safety guard

This is the uncollapsed S1 record requested by C0 for the 2026-08-13 safety remediation. It preserves the earlier device incident, the current fail-closed guard implementation, the host-only evidence, and the remaining unverified boundary. No secret values are included. The prior record at `artifacts/full-go-audit/2026-08-11/sol-max-s1-raw.md` was neither deleted nor overwritten.

## 1. Execution ledger

- Role: S1 mobile implementation owner
- Parent / representative questioner: C0
- Parent C0 task ID (delegation source): `019fe6c5-f82e-7261-9e0c-5397d17a4695`
- S1 real agent / thread ID: `019fee58-b16d-7642-9c7d-a61f1b0a93b6`
- Requested model / effort: `gpt-5.6-sol`, effort `max`
- Runtime model attestation: unavailable; `MODEL_ASSIGNMENT_UNVERIFIED`
- Independent-agent execution attestation: this worker was delegated by C0, but the runtime did not expose cryptographic attestation; `INDEPENDENT_AGENT_EXECUTION_UNVERIFIED`
- Original 2026-08-11 S1 phase start: exact first-start timestamp unavailable after thread compaction; `START_TIME_PARTIALLY_UNVERIFIED`
- 2026-08-13 visual-remediation start preserved in the prior raw record: 2026-08-13 15:11:30 +09:00 at the latest
- Current connected-guard remediation start: exact delegation-receipt timestamp was not persisted; first persisted edit was 2026-08-13 15:50:27 +09:00 (`AGENTS.md`), so the exact start is `START_TIME_PARTIALLY_UNVERIFIED`
- Current host-validation completion: 2026-08-13 15:59:44 +09:00
- Raw-record close: 2026-08-13 16:13:04 +09:00
- Repository root: `/Users/mimac/Desktop/りんばむ`
- Starting/current HEAD: `965d4d0cdd8fd916bc5adc996fc682b9875022d3`
- Current branch observed: `codex/full-go-mobile-s1-20260811`
- Shared worktree: dirty with C0/S1/S2 changes before and throughout this remediation; unrelated changes were preserved
- Git/external operations in this remediation: no branch operation, checkout, restore, reset, rebase, stash, stage, commit, push, deploy, release, or production mutation
- Device operations in this remediation: no ADB command, connected/device/instrumentation Gradle task, install, uninstall, reinstall, `pm`, package query, emulator operation, or physical Android operation
- Other-agent primary review visibility: S2's primary report was not read during S1's initial independent S2 diff review. Later, C0/user supplied S2 rebuttal conclusions and findings during the earlier remediation. No S2 primary report was opened or relied on for this connected-guard implementation.

## 2. Current independent scope

### In scope

- Replace the former two-environment-variable connected-test guard with a fail-closed target guard.
- Require exact `true` for `URLSAVER_ALLOW_CONNECTED_ANDROID_TESTS`.
- Require exact `true` for `URLSAVER_APPROVE_ANDROID_APP_DATA_RESET`.
- Require nonblank `URLSAVER_CONNECTED_ANDROID_SERIAL`.
- Only after those environment checks pass, run `adb devices` in the runtime guard.
- Require exactly one parsed ADB entry total, including offline/unauthorized entries in the count.
- Require that sole entry to have state exactly `device`.
- Require its serial to exactly equal `URLSAVER_CONNECTED_ANDROID_SERIAL`.
- Fail closed on zero/multiple/duplicate entries, mixed ready/offline entries, non-ready state, mismatch, malformed output, ADB process failure, and missing approval values.
- Wire the runtime guard as a real prerequisite of connected/device/install-androidTest Gradle tasks.
- Test the decision logic and task wiring on the host without starting ADB.
- Update the repository Android Device Data Guard contract.
- Run only permitted host unit/lint/build, mobile contract, and diff checks.

### Out of scope / explicitly prohibited

- Running the runtime guard against actual ADB state.
- Any `connectedDebugAndroidTest`, connected/device/instrumentation task, ADB invocation, install/uninstall/reinstall, `pm`, physical Pixel operation, or AVD operation.
- Production, Store, signing, upload, deploy, or external-service validation.
- Editing S2 Web/docs tracker or implementation files outside the minimal root `AGENTS.md` safety-contract hunk.

## 3. Confirmed cause and current finding state

### S1-SAFETY-ANDROID-001 — connected test selected every attached ready target

- Severity: Major data-safety/process defect
- Confidence: High
- Prior implementation: root `build.gradle.kts` used `gradle.taskGraph.whenReady` to require only two approval environment values. It did not require a target serial, inspect ADB topology, or reject multiple attached targets.
- Confirmed consequence: when an AVD and a physical Pixel were both visible, the same `connectedDebugAndroidTest` invocation ran on both.
- Current state:
  - Guard decision logic and actual Gradle task dependency wiring: `FIXED_AND_VERIFIED_HOST_ONLY`
  - Actual fail-closed behavior with a live ADB/device topology: `FIXED_NOT_VERIFIED`, because all device/ADB/connected execution is expressly prohibited in this phase
- Direct source evidence:
  - `build.gradle.kts:11-146`: pure parser, environment/device decisions, strict task classifier, safe failure messages, ADB executable resolution
  - `build.gradle.kts:148-183`: runtime guard; approval values are checked before `adb devices`
  - `build.gradle.kts:185-266`: 13 host-only decision cases plus actual task-wiring and host-isolation checks
  - `build.gradle.kts:268-277`: formal prerequisite wiring
  - `AGENTS.md:51-59`: three approvals, exact-one-device rule, and `ANDROID_SERIAL` defense-in-depth contract

## 4. Implementation details and actual Gradle wiring

### Pure decision path

- `parseAdbDeviceRows` accepts only output containing the canonical `List of devices attached` header and rows with serial/state fields.
- `evaluateConnectedAndroidEnvironment` rejects missing/non-exact approval values and blank target serial before the runtime task can start ADB.
- `evaluateConnectedAndroidDeviceGuard` rejects ADB failure, malformed output, row count other than one, state other than `device`, and serial mismatch.
- Device listings and stderr are not logged. Success/failure messages name only the approved target serial.

### Runtime task

- Root task: `:verifyConnectedAndroidDeviceTarget`
- It invokes `adb devices` only after all three environment requirements pass.
- It is a prerequisite of the actual tasks matched from the current Android Gradle Plugin task model.

### Actual guarded task set verified on this snapshot

- `:app:connectedAndroidTest`
- `:app:connectedCheck`
- `:app:connectedDebugAndroidTest`
- `:app:deviceAndroidTest`
- `:app:deviceCheck`
- `:app:installDebugAndroidTest`

For each of these six actual tasks, the host test confirmed that `:verifyConnectedAndroidDeviceTarget` is in `taskDependencies`. The same host test confirmed that `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` do not depend on the ADB-invoking runtime guard.

### Host-only guard test integration

- Root task: `:testConnectedAndroidDeviceGuard`
- `testDebugUnitTest` depends on this pure host task, so ordinary host unit validation continuously checks the guard contract without invoking ADB.
- Covered decision cases: zero devices, one exact match, one serial mismatch, two ready devices, unauthorized-only, offline-only, duplicate serial rows, ready plus offline, empty target serial, missing allow flag, missing reset approval, ADB failure, and invalid ADB output.

## 5. Complete prior Android device incident record

This section intentionally repeats the complete safety facts from the preserved 2026-08-11 raw report so the 2026-08-13 record is independently usable.

### Earlier dedicated-AVD-only run before the stop

- Approximate command start: 2026-08-13 15:20:43 +09:00, derived from task duration/result timestamp; the exact shell-start timestamp was not persisted.
- Complete command:

  `URLSAVER_ALLOW_CONNECTED_ANDROID_TESTS=true URLSAVER_APPROVE_ANDROID_APP_DATA_RESET=true JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=jp.mimac.urlsaver.EntryCardContrastComposeTest`

- Observed target at that first run: dedicated AVD `rinbam_api36_pixel9a(AVD) - 16`, serial `emulator-5554`, API 36.
- Executed class/test: `jp.mimac.urlsaver.EntryCardContrastComposeTest.renderedCardsExposeThemeAdaptiveColorsForEveryThemeAndSelectionState`
- Result: 1 test, 1 PASS, 0 failure, 0 skip; `BUILD SUCCESSFUL in 25s`.
- Evidence limitation: the later two-device run overwrote Gradle's standard connected-test XML directory, so the first-run XML is no longer independently present. The result survives in the task transcript and prior raw report; it must not be represented as a currently retained standalone XML artifact.
- It was a semantic/Compose test and produced no post-fix screenshot.

### Unintended two-target run

- Complete mistaken command:

  `URLSAVER_ALLOW_CONNECTED_ANDROID_TESTS=true URLSAVER_APPROVE_ANDROID_APP_DATA_RESET=true JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=jp.mimac.urlsaver.EntryCardContrastComposeTest`

- Device execution began at approximately 2026-08-13 15:24:20 +09:00.
- Physical result XML timestamp: 2026-08-13 15:24:23 +09:00.
- AVD result XML timestamp: 2026-08-13 15:24:31 +09:00.
- Physical target: serial `55211JEBF16639`, model `Pixel 9a`, report label `Pixel 9a - 17`, API 37 in `device-info.pb`.
- AVD target: serial `emulator-5554`, model `sdk_gphone64_arm64`, report label `rinbam_api36_pixel9a(AVD) - 16`, API 36.
- Exactly one class and one test ran on each target:
  - Class: `jp.mimac.urlsaver.EntryCardContrastComposeTest`
  - Test: `renderedCardsExposeThemeAdaptiveColorsForEveryThemeAndSelectionState`
  - Physical Pixel: 1/1 PASS, 0 failure, 0 skip
  - AVD: 1/1 PASS, 0 failure, 0 skip
- Retained evidence:
  - `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel 9a - 17-_app-.xml`
  - `app/build/outputs/androidTest-results/connected/debug/TEST-rinbam_api36_pixel9a(AVD) - 16-_app-.xml`
  - Adjacent per-device `testlog/test-results.log` and logcat files

### DB, app-data, and package effects

- The test source constructs only in-memory `UrlEntryEntity` fixtures and Compose content.
- Test-body `clearAllTables()`: no.
- Test-body `pm clear`: no.
- Test-body uninstall/install call: no.
- Test-body repository/Room access: no.
- Test-body save or DB write: no.
- Explicit S1 `adb install`, `adb uninstall`, `pm clear`, app-launch, or DB command: no.
- However, `connectedDebugAndroidTest` is an Android Gradle Plugin install/run workflow. Retained physical-device logs directly show:
  - target package launch: `Displayed jp.miyamibu.urlalbum/androidx.activity.ComponentActivity`
  - test APK install/update handling: `Handling installed or updated package` for `jp.miyamibu.urlalbum.test`
- A distinct retained line explicitly saying the target APK itself was installed/updated was not found. Therefore:
  - target launch: directly observed
  - test APK install/update: directly observed
  - target APK install/update: `INFERRED_FROM_CONNECTED_TEST_WORKFLOW`
  - explicit target/test APK uninstall: not observed in retained logs and not explicitly commanded
  - test APK post-run presence: `UNVERIFIED`
- Immediately after the run and before C0's command stop, a read-only package-list check returned no matching canonical/development target package on either target. The physical log proves the canonical package existed and launched during the run, but it was absent by the post-run query.
- Gradle teardown is a plausible explanation for disappearance, but concurrent shared-environment activity was not excluded. Attribution is `CAUSE_UNVERIFIED`; this report does not assert that S1's command uniquely caused the removal.
- C0 independently observed that `jp.miyamibu.urlalbum` disappeared from the emulator. That corroborates absence, not causality.
- Physical Pixel state that may remain or may have changed:
  - target debug APK version `1.0.17`, versionCode 21, may have replaced an existing installation
  - test APK was installed/updated and may have been removed or may remain
  - package metadata/update time and app process/UI state changed
  - canonical target package was absent at the post-run query
  - if a pre-existing canonical installation with user data existed and was removed, that data may have been deleted; there is no pre-run inventory or backup proof to prove preservation or loss
- No physical-device UI-quality conclusion is drawn from this accidental run. It proves only that the semantic test executed.

### Response and recurrence prevention

- After C0's safety update, all ADB, connected/device/instrumentation Gradle, install/uninstall, and `pm` commands stopped.
- S1 did not reinstall the missing emulator package or perform any later device inspection.
- The previous process-only recommendation (`ANDROID_SERIAL`) was insufficient by itself. The current Gradle guard now requires the new approved serial and exactly one ADB entry before any guarded task can start.
- `ANDROID_SERIAL` remains documented only as defense in depth and cannot replace the new Gradle gate.

## 6. Current change files

Files edited/created in this connected-guard remediation:

- `build.gradle.kts`
  - Replaced the old two-value task-graph check with the fail-closed pure decision logic, runtime guard task, host contract test, and formal task dependency wiring.
- `AGENTS.md`
  - Added only the Android Device Data Guard requirements for the new serial environment variable, exact-one-ready-device rule, failure cases, and `ANDROID_SERIAL` defense in depth. Other existing shared-tree hunks in this file were preserved and are not attributed to this guard change.
- `artifacts/full-go-audit/2026-08-13/sol-max-s1-raw.md`
  - New raw record. The 2026-08-11 raw record remains intact.

Earlier S1 Android contrast files and broader mobile remediation files remain documented in `artifacts/full-go-audit/2026-08-11/sol-max-s1-raw.md`. They were not changed for this guard-only remediation.

## 7. Command and validation ledger

### Implementation-time host guard validation

1. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testConnectedAndroidDeviceGuard`
   - Exit 0.
   - Pure decisions: 13/13 PASS.
   - Actual guarded task wiring: 6/6 PASS.
   - Host-only task isolation checks: PASS.
   - Logged actual guarded tasks: `:app:connectedAndroidTest`, `:app:connectedCheck`, `:app:connectedDebugAndroidTest`, `:app:deviceAndroidTest`, `:app:deviceCheck`, `:app:installDebugAndroidTest`.
   - This task did not invoke ADB.

2. `python3 scripts/verify_mobile_ui_contract.py`
   - Exit 0.
   - Result: `Mobile UI contract check passed.`

3. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest lintDebug assembleDebug`
   - Exit 0.
   - `:testConnectedAndroidDeviceGuard` executed as a dependency and again reported 13/13 pure decisions, six actual task-wiring checks, and host isolation PASS.
   - Gradle result: `BUILD SUCCESSFUL`; 58 actionable tasks, 3 executed, 55 up-to-date.
   - Unit XML aggregate: 45 suites, 400 tests, 400 PASS, 0 failure, 0 error, 0 skip.
   - Lint: PASS; report `app/build/reports/lint-results-debug.html`.
   - Debug build: PASS; APK `app/build/outputs/apk/debug/app-debug.apk`, 26,186,683 bytes, modified 2026-08-13 15:28:39 +09:00.

4. XML result aggregation via read-only `python3` over `app/build/test-results/testDebugUnitTest/TEST-*.xml`
   - Exit 0.
   - Result: `suites=45 tests=400 pass=400 failures=0 errors=0 skipped=0`.

5. `git diff --check`
   - Exit 0; no whitespace errors.

### Read-only inspection commands

The following command groups were used only to inspect repository state/evidence and did not operate a device:

- `pwd`, `git branch --show-current`, `git rev-parse HEAD`, `git status --short`
- `git diff -- build.gradle.kts AGENTS.md`
- `nl -ba build.gradle.kts`, `nl -ba AGENTS.md`, `sed`, `rg`, `ls`, `stat`, `date`
- `mkdir -p artifacts/full-go-audit/2026-08-13` created only the new evidence directory; it deleted or replaced nothing
- Source/report edits were applied as narrow patches, not by destructive Git or filesystem operations

### Prohibited commands not run in the current remediation

- No `adb` command, including even read-only `adb devices`.
- No Gradle task with `connected`, `device`, or instrumentation execution semantics.
- No `connectedDebugAndroidTest`.
- No app/test APK install, uninstall, reinstall, or `pm clear`.
- No physical Pixel or AVD interaction.

## 8. Evidence paths

- New raw record: `artifacts/full-go-audit/2026-08-13/sol-max-s1-raw.md`
- Preserved prior raw record: `artifacts/full-go-audit/2026-08-11/sol-max-s1-raw.md`
- Guard implementation and pure tests: `build.gradle.kts`
- Device-safety contract: `AGENTS.md:51-59`
- Unit XML directory: `app/build/test-results/testDebugUnitTest/`
- Lint report: `app/build/reports/lint-results-debug.html`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Retained accidental two-device connected XML:
  - `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel 9a - 17-_app-.xml`
  - `app/build/outputs/androidTest-results/connected/debug/TEST-rinbam_api36_pixel9a(AVD) - 16-_app-.xml`
- Connected HTML report from the prior accidental run: `app/build/reports/androidTests/connected/debug/index.html`

## 9. Confirmed problem-free scope

- The guard approval checks are strict string comparisons and reject unset, differently cased, or otherwise non-exact values.
- Missing approvals/serial are rejected before the runtime task reaches the ADB execution block.
- Offline and unauthorized rows count as attached entries and cannot be ignored to make a multi-entry topology look safe.
- Duplicate rows and mixed ready/offline rows are rejected by the exact-one-entry requirement.
- The approved serial is compared exactly, not by prefix or substring.
- ADB process failure and malformed output fail closed.
- Actual AGP connected/device/install-androidTest tasks discovered on this snapshot depend on the guard.
- Host unit/lint/assemble tasks do not depend on the ADB-invoking guard and completed without device access.
- No secrets, user data, production data, or device state were read or changed in this remediation.
- The prior raw report and all unrelated dirty-tree changes were preserved.

## 10. Unverified scope and residual risks

- Actual live ADB behavior of `:verifyConnectedAndroidDeviceTarget`: `UNVERIFIED` by explicit safety prohibition.
- Actual failure of a connected task under zero, one-mismatch, multiple, offline, unauthorized, or ADB-failure device topologies: `UNVERIFIED_LIVE`; covered only by pure host decision tests and task dependency introspection.
- Actual success of a connected task with exactly one approved disposable AVD: `UNVERIFIED_WITH_NEW_GUARD`.
- A device topology could theoretically change after the prerequisite guard finishes and before downstream AGP device interaction begins. The exact-one-device check materially closes the confirmed multi-target setup defect at task start, but this local host review does not prove immunity to hot-plug timing after the guard. Operationally, physical devices must remain disconnected and `ANDROID_SERIAL` should match the approved serial.
- Existing Gradle task names outside the six tasks present on this AGP snapshot are covered only when they match the classifier. A future AGP that introduces differently named device-install/test tasks requires a contract-test update.
- The physical Pixel's prior user-data preservation/loss remains unknown because no pre-run inventory or backup proof exists and later device inspection is prohibited.
- The emulator package disappearance is confirmed but causal attribution remains `CAUSE_UNVERIFIED`.
- Production, signed release, Store, external services, physical iPhone, and full-project snapshot/release gates remain outside this record.

## 11. Independent release judgment

- Connected-guard source/host scope: `RELEASE_READY_WITHIN_DECLARED_HOST_GUARD_SCOPE`.
- Finding S1-SAFETY-ANDROID-001:
  - source and pure decision logic: `FIXED_AND_VERIFIED`
  - actual Gradle prerequisite wiring: `FIXED_AND_VERIFIED_HOST_ONLY`
  - live connected behavior: `FIXED_NOT_VERIFIED`
- Full mobile release: `INCOMPLETE_REVIEW` because live connected behavior, physical-device safety outcome, signing/distribution, and production/external gates are not closed here.
- Full-project unconditional release: not asserted.
- `事実上100%確認済み: NO`.

## 12. Final status

- Host guard cases: 13 PASS / 0 FAIL.
- Actual Gradle task-wiring checks: 6 PASS / 0 FAIL.
- Android host unit: 400 PASS / 0 FAIL / 0 SKIP across 45 suites.
- Mobile UI contract: PASS.
- Android lintDebug: PASS.
- Android assembleDebug: PASS.
- Diff check: PASS.
- New-guard connected/device proof: not run, as required.
- Physical Android operation in current remediation: none.
- Stage: none.
- Commit: none.
- Push/deploy: none.
- Remaining gate: C0-controlled verification on an isolated disposable AVD with no physical device attached, if and only if a later explicit safety phase permits connected execution.

## 13. Final independent privacy / Store cross-rebuttal (2026-08-13)

### 13.1 Execution evidence and independence

- Audit role: S1 independent mobile/privacy rebuttal reviewer under C0.
- Parent C0 task ID: `019fe6c5-f82e-7261-9e0c-5397d17a4695`.
- Actual S1 agent / thread ID: `019fee58-b16d-7642-9c7d-a61f1b0a93b6`.
- Requested model / effort: `gpt-5.6-sol`, effort `max`.
- Runtime model attestation was not exposed: `MODEL_ASSIGNMENT_UNVERIFIED`.
- Independent-agent runtime attestation was not exposed: `INDEPENDENT_AGENT_EXECUTION_UNVERIFIED`.
- Review start: `2026-08-13T16:43:47+0900`.
- Review end / result freeze: `2026-08-13T17:08:06+0900`.
- Reviewed snapshot: branch `codex/full-go-mobile-s1-20260811`, HEAD `965d4d0cdd8fd916bc5adc996fc682b9875022d3`; shared worktree remained dirty and was not a frozen release snapshot.
- Review mode: source, public policy, release documents, official platform definitions, and C0-supplied Store/archive observations were read only. The only write was this explicitly required raw-record append. No source code, Store form, external service, device, ADB, credential, secret, branch, stage, commit, push, deploy, or production state was changed.
- S2 independence: S2's unfinished raw report and S2's final conclusions were not opened. Current repository documents were reviewed as primary target data, not accepted as conclusions. Earlier in the broader remediation, C0/user had supplied selected S2 rebuttal findings; those were not used as proof for this privacy-matrix conclusion.
- User, designer, administrator, and adversarial/rebuttal perspectives were applied. No user question was issued.

### 13.2 Official decision rules used

- Google Play Data Safety: `Collect` includes user data transmitted off device, including to third-party servers. Ephemeral data must still be entered in the form, although it can be omitted from the public collected-data display only when it is held only in memory and no longer than necessary for the real-time request. On-device transfer to another app is sharing, subject to the service-provider, legal, user-initiated/prominent-consent, and anonymous-data exceptions. Source: <https://support.google.com/googleplay/android-developer/answer/10787469>.
- Apple App Privacy: `Collect` requires off-device transmission that lets the developer or an integrated third-party partner access readable data longer than needed to service the real-time request. Apple states that developers do not disclose data collected by Apple itself, and on-device-only processing is not collected. Source: <https://developer.apple.com/app-store/app-privacy-details/>.

### 13.3 Apple — exact public 1.0.17 (19) archive rebuttal

#### Exact-binary evidence

C0 directly inspected the signed distribution archive at `/Users/mimac/Library/Developer/Xcode/Archives/URLSaveriOS-20260728-1.0.17-distribution.xcarchive` without displaying secret values. C0 reported:

- version `1.0.17`, build `19`;
- Bundle ID `com.mibu.codebridge.ios`;
- Team `8R3B5675ZJ`;
- `SharedTagCloudEnabled=false`;
- `SupabaseURL=EMPTY`;
- `SupabaseAnonKey=EMPTY`;
- `ContactSupportEndpoint=EMPTY`.

The public App Store version/build also reads `1.0.17 (19)`, so this is strong distribution-artifact evidence for the public binary. S1 did not reopen the archive and did not inspect any secret-bearing file; the archive facts are therefore `C0_DIRECT_ARCHIVE_EVIDENCE / NOT_INDEPENDENTLY_REEXTRACTED_BY_S1`.

Current source independently supports that interpretation:

- `ios/Config/URLSaverSecrets.local-only.xcconfig:1-11` selects local-only mode, false cloud/AI flags, and empty Supabase/support values.
- `ios/URLSaveriOS.xcodeproj/project.pbxproj:886-917` assigns that local-only xcconfig to the app Release configuration and canonical bundle.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:27-60` reads the bundle/config values and regards cloud as configured only when enabled and both Supabase values are nonempty.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:87-101` regards support as configured only when its endpoint is nonempty.
- `ios/URLSaveriOS/App/StoreKitPurchaseService.swift:36-50` returns `notConfigured` before product lookup/purchase when cloud configuration is absent.
- `ios/URLSaveriOS/App/StoreKitPurchaseService.swift:79-103` requires a stored signed-in session before developer-side purchase verification; that session cannot be created through the disabled/empty cloud configuration.
- `ios/URLSaveriOS/PrivacyInfo.xcprivacy:5-18` and `ios/URLSaverShareExtension/PrivacyInfo.xcprivacy:5-18` declare no tracking/collected-data types and only the UserDefaults required-reason API.

#### Apple App Privacy matrix for the exact public archive

| Apple data type / flow | Exact 1.0.17 (19) answer | Linked | Tracking | Purpose / evidence | Final state |
|---|---|---|---|---|---|
| Local saved URL, title, memo, local tags, fetched metadata | Not collected by developer/partner | N/A | No | Public policy says normal saved data stays on-device at `web/invite-link/privacy/index.html:78-82,95-101`; on-device-only processing is outside Apple's collection definition. | `VERIFIED_NO_COLLECTION_WITHIN_BINARY_SCOPE` |
| Cloud account email/session and Supabase user ID | Not collected in this binary | N/A | No | Archive flag false and URL/key empty; runtime requires enabled plus nonempty URL/key at `ios/URLSaverShared/Data/SharedTagCloud.swift:27-60`. | `VERIFIED_DISABLED_BY_ARCHIVE` |
| Shared-tag names, URLs, membership, invite, owner-transfer, display name | Not collected in this binary | N/A | No | Same unreachable cloud gate. The implementation and broad policy (`web/invite-link/privacy/index.html:83,95-96`) describe a different cloud-enabled mode, not proof that this archive transmitted data. | `VERIFIED_DISABLED_BY_ARCHIVE` |
| Customer-support name, email, message, audit metadata | Not collected in this binary | N/A | No | Archive support endpoint is empty; `ContactSupportConfig.isConfigured` requires nonempty endpoint at `ios/URLSaverShared/Data/SharedTagCloud.swift:87-101`. The policy's conditional flow is at `web/invite-link/privacy/index.html:113-116`. | `VERIFIED_DISABLED_BY_ARCHIVE` |
| Purchase History / entitlement verification visible to developer | Not collected in this binary | N/A | No | Purchase exits before StoreKit product lookup when cloud is unconfigured (`StoreKitPurchaseService.swift:36-50`), and developer verification requires a cloud session (`:79-103`). Apple may process StoreKit data itself, but Apple says developers do not disclose data collected by Apple. Payment-card information is never available to the developer. | `VERIFIED_NO_DEVELOPER_COLLECTION` |
| Metadata request to target page / public oEmbed | No confirmed Apple collection; residual retention interpretation remains | N/A | No evidence | `ios/URLSaverShared/Data/MetadataFetcher.swift:42-80,192-219,261-277,343-370` performs real-time requests. There is no source evidence that Rinbam receives or retains those request logs off-device. Arbitrary destination sites are not integrated code. If a deliberately integrated metadata vendor retains the requested URL/IP beyond the request and qualifies as an Apple third-party partner, Browsing History/App Functionality would need declaration; no such retention evidence was available. | `DISPUTED_LOW_CONFIDENCE_RESIDUAL`, not a confirmed Major |
| Manual JSON/ZIP/ChatGPT OS share | Not collected by Rinbam | N/A | No | The app creates a local file, obtains explicit confirmation, and presents the OS share surface (`ios/URLSaveriOS/UI/ExportSheet.swift:910-992`; policy `web/invite-link/privacy/index.html:104-110`). Recipient processing starts only after user choice and is not developer receipt/retention. | `VERIFIED_USER_DIRECTED_EXTERNAL_HANDOFF` |
| Analytics, crash SDK, ads, cross-app tracking | Not collected / No tracking | N/A | No | Policy `web/invite-link/privacy/index.html:73-74,119-121`; release privacy manifests `NSPrivacyTracking=false`. | `VERIFIED_WITHIN_SOURCE_AND_ARCHIVE_SCOPE` |

#### Reclassification of the former Apple privacy Major

- Finding: `APPLE-PRIVACY-NO-COLLECTION-MISMATCH` (the earlier claim that the public App Privacy label must be wrong merely because cloud/support/purchase code and a broad policy exist).
- Previous status/severity: `CONFIRMED_MAJOR` in current release drafts.
- Independent rebuttal: the exact public `1.0.17 (19)` archive is local-only and has empty Supabase/support configuration. Source capability, privacy-policy coverage of conditional modes, and Store marketing copy do not prove collection by the exact binary.
- Final status: `FALSE_POSITIVE_AS_APP_PRIVACY_MAJOR`.
- Final severity: no confirmed privacy defect for the exact archive. Retention classification for metadata providers remains `HYPOTHESIS / LOW-MEDIUM CONFIDENCE`, not Major.
- Separate issue preserved: if the public product description promises cloud sync/invitations while the exact archive disables them, that is a Store-listing/functionality accuracy issue, not proof that the App Privacy label is wrong. It remains a `MAJOR_CANDIDATE` until the public copy and actual UI availability are captured together.
- Source conflict: `docs/release/privacy-data-safety-draft.md:21-29,105-110` and `docs/release/privacy-policy-and-store-disclosure-checklist.md:32` over-generalize the cloud-enabled implementation to the public iOS binary and must not be used as current Apple privacy proof without incorporating the archive evidence.

### 13.4 Google Play — cloud-enabled Android 1.0.17 / versionCode 21

#### Binary/source evidence boundary

- Current Android source is canonical package `jp.miyamibu.urlalbum`, version `1.0.17`, versionCode `21` at `app/build.gradle.kts:114-119`.
- The release can embed cloud mode and endpoint values in `BuildConfig` at `app/build.gradle.kts:57-76,88-100,142-157`; runtime wiring uses those values at `app/src/main/java/jp/mimac/urlsaver/app/AppContainer.kt:121-166,223-227`.
- C0/user supplied the Store-side premise for this phase that the relevant Google v21 distribution is cloud-enabled, plus authenticated read-only Console observations: Step 2 collect/share `Yes`, encryption `Yes`, account creation `does not allow`, deletion unanswered; Step 3 only Web browsing; Web browsing collected+shared, ephemeral `Yes`, required, App functionality; Step 5 public preview says no collected data and shared Web browsing.
- S1 did not extract the submitted AAB's `BuildConfig` in this cross-rebuttal. Thus `cloud-enabled v21` is `C0_SUPPLIED_DISTRIBUTION_EVIDENCE`; the source capability/version is independently verified, while exact-AAB re-extraction remains an explicit unverified item.

#### Google Data Safety matrix for cloud-enabled v21

| Google category / field | Correct cloud-enabled answer | Collection details | Sharing treatment | 1-based source evidence |
|---|---|---|---|---|
| Does the app collect or share user data? | Yes | Multiple off-device flows exist. | Yes at least for destination/oEmbed delivery unless a recorded exception applies. | `app/src/main/java/jp/mimac/urlsaver/worker/MetadataFetcher.kt:45-76,94-111,1274-1325`; cloud wiring `AppContainer.kt:121-166,223-227`. |
| Web browsing history / saved URL used for metadata | Collected: Yes; Required/automatic; purpose App functionality | A saved HTTPS URL or encoded target URL is sent to the destination/oEmbed services. The app has no user opt-out for automatic metadata scheduling. | Conservatively Shared: Yes for target/oEmbed recipients. Automatic metadata enrichment is not the same as a specific user-selected OS recipient. | Endpoint builders `MetadataFetcher.kt:45-68`; GET network transmission `:1274-1325`; public disclosure `web/invite-link/privacy/index.html:99-101`. |
| Ephemeral processing for Web browsing | Do not answer Yes without provider-retention proof | Google's ephemeral rule covers only memory-only use no longer than the real-time request. The client source proves local request handling, not that arbitrary target/oEmbed providers do not retain URL/IP/request logs. | Same caveat. | `MetadataFetcher.kt:1274-1325`; Google definition URL above. |
| Email address | Collected: Yes; Optional feature; App functionality/account management | Supabase signup/signin/recovery transmits email. | Supabase may qualify as a service provider if contractual facts support it; do not mark third-party sharing solely for that processor without reviewing the exception. | `app/src/main/java/jp/mimac/urlsaver/data/SharedTagAuthRemoteDataSource.kt:24-38,107-166`. |
| User IDs | Collected: Yes; Optional feature; App functionality/account management | Supabase user ID/session identifies the account; pseudonymous IDs still require disclosure. | Service-provider exception may apply to Supabase. | `SharedTagAuthRemoteDataSource.kt:169-198`; cloud RPC bearer/session use `SharedTagSyncRemoteDataSource.kt:406-446`. |
| Name | Collected: Yes when profile/support used; Optional; App functionality | Shared profile display name and optional support name are transmitted. | Service-provider exception may apply; support delivery vendor must be assessed. | `SharedTagSyncRemoteDataSource.kt:96-99,366-373`; `ContactSupportClient.kt:20-31,46-67`. |
| Other user-generated content | Collected: Yes; Optional cloud feature; App functionality | Shared-tag names, shared saved URLs, groups, membership, roles, invitations, and ownership operations are sent/stored. | Participant visibility may qualify for the user-initiated action exception if the UI makes it expected; otherwise mark shared. Supabase service-provider exception requires contractual support. | `SharedTagSyncRemoteDataSource.kt:22-109,138-210,213-404`; policy `web/invite-link/privacy/index.html:80-83,95-96`. |
| Customer-support content / other user-generated content | Collected: Yes when submitted; Optional; App functionality/customer support | Email, name, message, platform, app version, build type, and idempotency key are sent. Audit handling is described publicly. | Support/email processor may be a service provider; document the basis. | `ContactSupportClient.kt:20-31,38-67`; policy `web/invite-link/privacy/index.html:113-116`. |
| Purchase history | Collected: Yes when purchase/entitlement feature used; Optional; App functionality/fraud prevention/account management | Product ID, purchase token, transaction/order ID, linked account hash, and entitlement verification are processed. | Google Play itself and the verification backend must be assessed under platform/service-provider treatment. | `app/src/main/java/jp/mimac/urlsaver/billing/GooglePlayBillingService.kt:60-95,170-183,224-227`; `StorePurchaseRemoteDataSource.kt:41-86,110-125`. |
| Device/other IDs, location, diagnostics | No based on inspected app-owned source | IP may be visible to network recipients, but no evidence shows the app derives location/device identity or collects diagnostic telemetry. Reclassify if a provider uses IP for such a purpose. | N/A unless provider facts change. | Main manifest only declares Internet at `app/src/main/AndroidManifest.xml:1-16`; release removes ad identifiers/components at `app/src/release/AndroidManifest.xml:5-41`. |
| Ads / tracking | No | Release hard-disables ads and empties IDs. No analytics/crash SDK was identified. | No. | `app/build.gradle.kts:142-157,214-226`; `app/src/release/AndroidManifest.xml:5-41`; policy `web/invite-link/privacy/index.html:119-121`. |
| Encryption in transit | Yes is supported by inspected production paths, subject to exact-AAB endpoint verification | External URL policy only permits HTTPS outside test loopback; release media resolver must be HTTPS; Supabase/support configuration should be HTTPS. | N/A. | `app/src/main/java/jp/mimac/urlsaver/network/NetworkUrlPolicy.kt:24-53`; `app/build.gradle.kts:102-105`. |
| Account creation | Yes | Cloud-enabled source provides sign-up, OAuth/sign-in, recovery, and session refresh. | N/A. | `SharedTagAuthRemoteDataSource.kt:24-38,54-166`. |
| Account deletion | Yes; provide the public deletion URL and answer the deletion questions | In-app deletion RPC and public request route exist. | N/A. | `SharedTagSyncRemoteDataSource.kt:107-109,396-403`; `web/invite-link/account-deletion/index.html:73-98`. |
| Manual OS export / ChatGPT ZIP | Transfer exists, but Google sharing exception is supportable when user initiates and reasonably expects the chosen recipient | Local generation is not collection by Rinbam. | The OS handoff is an on-device transfer, but Google's explicit user-initiated/prominent-consent exception applies to the preview, confirmation, and chosen recipient flow. Record the rationale; do not count it as automatic developer sharing. | `app/src/main/java/jp/mimac/urlsaver/ui/ExportScreen.kt:566-769,777-866,1300-1331,1464-1484`; policy `web/invite-link/privacy/index.html:104-110`. |

#### Rebuttal of the observed Console state

- Step 2 `collect/share=Yes` versus Step 5 public `Collected: no data collected` is not intrinsically contradictory: Google explicitly allows an ephemeral category entered in the form to be omitted from the public collected-data display.
- However, `ephemeral=Yes` for arbitrary target/oEmbed recipients is not established by client code. No provider-retention evidence was supplied. Therefore the current Step 5 `no collected data` cannot be treated as accurate for a cloud-enabled v21 release.
- `account creation=does not allow` is directly incompatible with the cloud-enabled v21 authentication implementation.
- `deletion unanswered` is incomplete for that account-enabled mode despite the app/public deletion mechanisms existing.
- Step 3 `Web browsing only` omits Email address, User IDs, Name where used, Other user-generated content/shared-tag collaboration, optional support content, and Purchase history for a cloud-enabled release.

### 13.5 Final finding ledger and severities

| Finding ID | Finding | Severity | Confidence | Rebuttability / final state |
|---|---|---:|---:|---|
| `APPLE-PRIV-001` | Public App Privacy `データの収集なし` allegedly conflicts with cloud/support/purchase source. | Former Major -> no confirmed defect | High | `FALSE_POSITIVE_AS_PRIVACY_MAJOR` for exact 1.0.17 (19), rebutted by matching local-only signed archive. |
| `APPLE-META-001` | Metadata recipient may retain requested URL/IP and qualify as a partner. | Minor/Hypothesis | Low-Medium | `DISPUTED`; requires provider retention/contract facts, not source inference. |
| `APPLE-LISTING-001` | Public description reportedly advertises cloud features while matching public binary has cloud disabled. | Major candidate | Medium-High | Separate from privacy; directly rebuttable by capture of current listing plus runtime UI/feature availability. |
| `PLAY-DS-001` | Cloud-enabled v21 form lists only Web browsing and says account creation is unavailable. | Major | High under C0's cloud-enabled-v21 premise | `CONFIRMED_EXTERNAL_DECLARATION_MISMATCH`; exact AAB flag extraction remains the strongest final provenance check. |
| `PLAY-DS-002` | Web browsing marked ephemeral without retention evidence, allowing public `no collected data`. | Major | Medium-High | `CONFIRMED_EVIDENCE_GAP / LIKELY_INCORRECT`; rebuttable only with documented recipient memory-only/no-retention evidence for every endpoint. |
| `PLAY-DS-003` | Account deletion answer is incomplete while cloud accounts exist. | Major | High under cloud-enabled premise | `CONFIRMED_INCOMPLETE_FORM`; public deletion mechanisms already exist, so this is a Console-answer gate, not missing app functionality. |
| `REL-PRIV-001` | Shared dirty source is not a frozen artifact and the Android submitted AAB was not independently re-extracted by S1. | Major release-evidence gap | High | `INCOMPLETE_EVIDENCE`; resolve by hashing and inspecting the exact submitted AAB without exposing values. |

- Unresolved Blocker: `0` confirmed in this focused privacy review.
- Unresolved Critical: `0` confirmed in this focused privacy review.
- Unresolved Major: Google `PLAY-DS-001`, `PLAY-DS-002`, `PLAY-DS-003`; release-evidence gap `REL-PRIV-001`; Apple listing `APPLE-LISTING-001` remains a Major candidate rather than a privacy-label Major.

### 13.6 Confirmed problem-free scope

- Exact public iOS 1.0.17 (19) archive is local-only according to C0's direct archive inspection and matching source defaults.
- No evidence shows developer-side Supabase auth/shared-tag/support/purchase retention in that exact iOS binary.
- StoreKit/Apple-owned processing is not automatically developer collection; Apple explicitly says developers do not disclose data collected by Apple.
- Manual JSON/ZIP/ChatGPT transfer is user-directed OS sharing with preview/confirmation and no Rinbam API send.
- Google Step 2 versus Step 5 is explainable by Google's ephemeral-public-display rule and is not, by itself, proof of an internal Console contradiction.
- Release ads/tracking are disabled in inspected source/manifests.
- Public privacy policy accurately explains local storage, conditional cloud/support/purchase modes, metadata requests, OS handoff, and deletion; its broader conditional coverage does not prove all modes are active in every binary.

### 13.7 Unverified scope

- S1 did not independently reopen the signed iOS archive; C0's non-secret direct inspection is the archive evidence.
- S1 did not inspect the App Store Connect privacy questionnaire itself, purchase receipt history, server logs, provider contracts, or target/oEmbed retention practices.
- S1 did not independently extract the submitted/public Android v21 AAB's actual `BuildConfig` flags/endpoints. The cloud-enabled-v21 premise is C0/user-supplied Store/distribution evidence plus independently verified source capability/version.
- S1 did not open or change Google Play Console/App Store Connect, save any answer, or verify post-save public propagation.
- No production traffic capture, Supabase database inspection, support delivery inspection, billing transaction, account creation/deletion, or real-device test was performed.
- No S2 unfinished raw report or conclusion was read.

### 13.8 Independent release judgment

- Apple App Privacy label for exact public `1.0.17 (19)` local-only archive: `RELEASE_READY_WITHIN_EXACT_BINARY_PRIVACY_SCOPE`; the former privacy Major is closed as false positive. This does not approve cloud-enabled future iOS builds.
- Apple overall Store listing: `CONDITIONAL_NO_GO` until the reported cloud-feature marketing versus local-only binary is directly reconciled; this is a functionality/listing gate, not an App Privacy collection finding.
- Google Play cloud-enabled Android v21: `NO_GO` until Data Safety categories, account-creation/deletion answers, ephemeral rationale, exact AAB flags, and public post-save display are corrected and captured.
- Full project: `NO_GO / PARTIALLY_VERIFIED` in this focused review. Unconditional GO is not supportable while Google external declarations and exact Android binary provenance remain unresolved.
- `事実上100%確認済み: NO`.

## 14. Google Play Data Safety complete CSV proposal (2026-08-13)

### 14.1 Execution ledger and independence

- Focused assignment: independently reconcile C0's read-only Google Play Console export with the exact signed cloud-enabled Android `1.0.17 (versionCode 21)` artifact, current Android/source backend behavior, public privacy/deletion pages, and current Google Play Data Safety definitions; create an import-review-only CSV without changing Play Console.
- Parent / representative questioner: C0.
- Parent C0 task ID: `019fe6c5-f82e-7261-9e0c-5397d17a4695`.
- Actual S1 agent / thread ID: `019fee58-b16d-7642-9c7d-a61f1b0a93b6`.
- Requested model / effort: `gpt-5.6-sol`, effort `max`.
- Runtime model attestation: unavailable; `MODEL_ASSIGNMENT_UNVERIFIED`.
- Independent-agent runtime attestation: unavailable; `INDEPENDENT_AGENT_EXECUTION_UNVERIFIED`.
- Input-export timestamp: 2026-08-13 17:15:55 +09:00 (filesystem mtime of C0's downloaded export).
- Focused worker start: exact delegation-receipt timestamp was not persisted; the first durable output was created at 2026-08-13 17:40:08 +09:00, so `START_TIME_PARTIALLY_UNVERIFIED`.
- Focused phase end: 2026-08-13 17:46:43 +09:00 for the initial draft; final validation/report close timestamp is recorded in section 14.12 below.
- Other-agent report visibility: S1 did not read S2's unfinished raw report or S2's primary conclusion for this focused phase. Earlier in the parent task, C0 supplied selected S2 rebuttal findings; those were already known context, not a source for the CSV response matrix. The matrix was derived from the source, exact AAB checks, public policy, C0's raw Console export, and current official Google definitions.
- Mutations not performed: no Play Console import/save/submit, Store edit, browser interaction, production request, device/ADB operation, stage, commit, push, deploy, branch switch, secret-file read, or secret-value output.

### 14.2 Inputs and immutable source facts

- Original Console export: `/Users/mimac/Downloads/data_safety_export.csv`; read-only and unchanged.
- Original SHA-256: `4d716520df8c26c81bc9262cb84f2d5b6d47b621e9c1190dc0f21e2434aceed4`.
- Original bytes: `243858`.
- Original encoding/line facts: no UTF-8 BOM, CRLF separators, no terminal newline, `782` newline bytes.
- CSV parser fact: `783` logical records = one header + `782` response records. Therefore `wc -l=782` on the source is a missing-terminal-newline artifact, not evidence that one response record should be dropped.
- Header: exactly five columns: Question ID, Response ID, Response value, Answer requirement, Human-friendly question label.
- All source records had width five. Requirements were preserved: `655 MULTIPLE_CHOICE`, `79 SINGLE_CHOICE`, `43 MAYBE_REQUIRED`, `4 OPTIONAL`, `1 REQUIRED`.
- Source state had only Web browsing selected and account creation set to no account creation; that state is not correct for the exact cloud-enabled v21 artifact.

### 14.3 Exact signed AAB verification without secret disclosure

- Exact signed AAB inspected read-only: `/Users/mimac/.urlsaver-signing/app-release-1.0.17-21-play-upload-signed-20260805.aab`.
- Computed SHA-256: `e88c8e978bea63b8d811c12569fb8e6b28c62111da4bca39c500705ccc6f9605`; matches the C0-provided known digest.
- Archive structure: `1025` ZIP entries, one DEX entry, one base manifest entry.
- Non-secret DEX predicates independently confirmed: shared-tag remote configuration type present; cloud mode enabled per C0's direct exact-AAB decode; public invite base marker present; Supabase endpoint has HTTPS Supabase-host shape; contact-support endpoint has HTTPS function-path shape.
- No endpoint value, key, token, user data, or other secret-bearing string was printed, copied into the CSV, or retained in this report.
- Reclassification: the exact-AAB portion of `REL-PRIV-001` is `RESOLVED_BY_EXACT_ARTIFACT`. The shared dirty working tree remains unsuitable as a frozen source snapshot, but it is no longer the sole evidence for cloud-enabled v21 or HTTPS endpoint shape.
- Encryption conclusion for this exact AAB: `PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT=true` is supported by the exact HTTPS endpoint predicates plus `NetworkUrlPolicy.kt:24-53`, `MetadataFetcher.kt:2420-2431`, and the fixed HTTPS support-delivery endpoint at `supabase/functions/contact-support-outbox/index.ts:252-266`. This conclusion is scoped to the inspected AAB and current paths; no production packet capture was performed.

### 14.4 Classification rules applied

- Official Google source: `https://support.google.com/googleplay/android-developer/answer/10787469`, read 2026-08-13.
- Google defines collect as transmitting data off device, requires pseudonymous data to be disclosed, and permits ephemeral treatment only when data is memory-only and retained no longer than the real-time request.
- Google defines Device or other IDs as identifiers related to a device, browser, or app. It separately says an identifier tied only to a specific event and not reasonably tied to a device/browser/app is not a Device ID.
- Google says a cloud provider hosting data on the developer's behalf typically qualifies as a service provider; a third party processing for its own cross-customer purposes does not.
- Google excludes a transfer from sharing when it is based on a specific user-initiated action that the user reasonably expects.
- Google calls signed-in-only collection optional when all users can use the app without signing in; it is required if any currently distributed version/user cannot opt out of that data type.
- Applied exception rationale, not a blanket assumption:
  - Supabase stores/authenticates/synchronizes first-party Rinbam data at developer-configured endpoints and is treated in this proposal as a cloud hosting service provider acting on developer instructions.
  - Resend receives a developer-composed support email from a developer-configured worker and is treated as a support-delivery service provider.
  - Shared-tag participant visibility follows the explicit tag/group/invite/member action and is treated as a specific user-initiated transfer.
  - Manual JSON/ZIP/ChatGPT handoff requires preview, independent confirmation, and user-selected recipient and is treated as a specific user-initiated transfer.
  - Automatic metadata fetch is not covered by those exceptions in this proposal; Web browsing remains shared to destination/oEmbed services.
- Pre-import owner gate: confirm the current Supabase/Resend contractual roles and that every supported UI/region preserves the expected user-initiated shared-tag/OS-share behavior. If not, the relevant sharing rows must be changed to `true` before import. This is not a claim that a contract was inspected.

### 14.5 Device/other IDs and Other actions correction

#### `PLAY-DS-004` — persistent sync client ID and hashed source IP were omitted

- Severity: Major external disclosure mismatch.
- Confidence: High.
- Previous S1 conclusion: Device/other IDs absent based on a narrower manifest/SDK review.
- Direct source evidence:
  - `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt:96-105` generates a UUID `clientId` and persists it in sync state.
  - `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncEntities.kt:121-149` persists `clientId` with outbox and per-user sync state.
  - `app/src/main/java/jp/mimac/urlsaver/domain/SharedTagSyncContracts.kt:6-20` sends `client_id` in each operation.
  - `supabase/migrations/20260420120000_shared_tag_sync.sql:134-140,246-258,465-466` receives and retains `client_id` alongside the user and applied operation.
  - `supabase/functions/contact-support/index.ts:102-118,299-343` hashes source IP and retains the hash in the support audit enqueue path.
- Rebuttal result: `client_id` is stable for the account/install sync state and reasonably relates to the app installation/client, so it is Device or other IDs. The support IP hash is pseudonymous and used for security/rate limiting; it reinforces the same selected category. Hashing does not make it anonymous under Google's pseudonymous-data rule.
- `op_id`, support `requestId`, and `idempotencyKey` are event/transaction identifiers. They are not independently classified as Device IDs because they do not reasonably identify a device/browser/app across events; this prevents over-declaration.
- Final state: prior “Device IDs none” conclusion is `FALSE_POSITIVE`; current classification is `CONFIRMED_COLLECTED / NOT_SHARED_IN_PROPOSAL / OPTIONAL / NOT_EPHEMERAL`, purposes App functionality and Fraud prevention/security.

#### `PLAY-DS-005` — durable shared-tag operation types are Other actions

- Severity: Major external disclosure mismatch as part of the current Web-browsing-only Console form.
- Confidence: High.
- Direct source evidence: `SharedTagSyncContracts.kt:7-48` sends create/rename/delete/URL assignment/invite/member-role/remove operations; `SharedTagSyncRemoteDataSource.kt:138-150` transmits the operation list; `shared_tag_sync.sql:246-265,465-468` processes it and retains durable applied-operation results.
- Rebuttal result: tag/group names, URL values, member/display-name/collaboration fields and support inquiry text remain Other user-generated content. The mutation type/status itself is a user action, not merely content, and is therefore also Other actions. Both categories are required to avoid collapsing distinct Google data types.
- Final state: `CONFIRMED_COLLECTED / NOT_SHARED_IN_PROPOSAL / OPTIONAL / NOT_EPHEMERAL`, purpose App functionality.

### 14.6 Complete proposed data-category matrix

| Category | Collected | Shared | Ephemeral | Required / optional | Collection purposes | Sharing purposes | Primary source evidence |
|---|---|---|---|---|---|---|---|
| Name | Yes | No | No | Optional | App functionality; Account management | N/A | Shared profile `SharedTagSyncRemoteDataSource.kt:366-373`; support `ContactSupportClient.kt:20-31,46-67` |
| Email address | Yes | No | No | Optional | App functionality; Account management | N/A | Auth signup/signin/recovery `SharedTagAuthRemoteDataSource.kt:107-166`; support path above |
| User IDs | Yes | No | No | Optional | App functionality; Fraud prevention/security; Account management | N/A | Auth session `SharedTagAuthRemoteDataSource.kt:169-198`; bearer/RPC `SharedTagSyncRemoteDataSource.kt:406-425` |
| Purchase history | Yes | No | No | Optional | App functionality; Fraud prevention/security; Account management | N/A | `GooglePlayBillingService.kt:60-95,170-183,224-227`; `StorePurchaseRemoteDataSource.kt:41-86,110-125` |
| Web browsing history | Yes | Yes | No | Required | App functionality | App functionality | `MetadataFetcher.kt:45-76,94-111,1274-1325`; no provider no-retention proof |
| Other user-generated content | Yes | No | No | Optional | App functionality | N/A | shared names/URLs/groups/membership operations and support inquiry payload |
| Other actions | Yes | No | No | Optional | App functionality | N/A | durable shared-tag operation types/results, evidence in 14.5 |
| Device or other IDs | Yes | No | No | Optional | App functionality; Fraud prevention/security | N/A | persistent sync `client_id`; hashed source IP, evidence in 14.5 |

Why only Web browsing is marked shared in the proposal:

- Supabase and Resend are treated as developer-directed service providers based on the observed integration role; this treatment must be owner-confirmed before import.
- Shared-tag participant access and manual OS share are tied to specific expected user actions.
- Destination/oEmbed metadata requests are automatic after URL saving; no service-provider role or user-action exception was established for all possible recipients.

### 14.7 Account creation, deletion, and security responses

- Account creation methods selected: User ID/password and OAuth. Source: `SharedTagAuthRemoteDataSource.kt:24-38,54-166`.
- “Does not allow account creation” cleared.
- Outside-app account login selected with explicit Google OAuth description; this captures login through a pre-existing Google identity. C0 should confirm the current Console's interpretation of this optional field in the import preview because the Supabase app account is provisioned during OAuth.
- Account deletion URL: `https://miyamibu.xyz/account-deletion/`.
- Data deletion: Yes, with the same public URL.
- In-app deletion source: `SharedTagSyncRemoteDataSource.kt:396-403`; public instructions: `web/invite-link/account-deletion/index.html:73-98`.
- Encryption in transit: Yes, scoped to the exact signed v21 artifact and inspected paths as documented in 14.3.
- Family badge, independent validation badge, and UPI badge remain blank because no qualifying evidence was supplied.

### 14.8 Proposal artifact and exact response summary

- Proposed file: `artifacts/full-go-audit/2026-08-13/google-play-data-safety-proposed.csv`.
- State: `UNSUBMITTED_IMPORT_REVIEW_DRAFT`; no Store import/save/submit occurred.
- Encoding: UTF-8 with BOM.
- Proposed SHA-256 before final raw close: `a98bbb4d8ffa09f264a54cf1efc04248f3dd51faedc47b8738cb1ba5422a91c8`.
- Logical structure: one header + `782` response records = `783` parser records; five columns on every record.
- Physical newline count: proposal has `783` LF separators and a terminal newline, while source has `782` CRLF separators and no terminal newline. This formatting difference does not add/drop a CSV record; field-level validation proves all 782 source response records are present.
- Non-response columns: every row's Question ID, Response ID, Answer requirement, and human label exactly equal the corresponding source fields.
- Response summary: `59` non-empty values = `48 true`, `8 false`, `3 text/URL`.
- Selected data types: Name, Email address, User IDs, Purchase history, Web browsing history, Other user-generated content, Other actions, Device or other IDs.
- Every selected type has exactly one collected=true row, exactly one ephemeral=false answer, exactly one required/optional answer, at least one collection purpose, and sharing-purpose rows if and only if shared=true.
- Only Web browsing has shared=true and sharing purpose App functionality.
- Source file remained unchanged with its original SHA-256.

### 14.9 Validation ledger

1. Source CSV inventory and parse
   - Command: Python `csv.reader`/`csv.DictReader`, byte/BOM/newline/SHA checks against `/Users/mimac/Downloads/data_safety_export.csv`.
   - Exit: `0`.
   - Result: `PASS`; 783 logical records, 782 response records, width five, source hash above.
2. Source/code evidence searches
   - Commands: focused `rg`, `nl -ba`, and `sed` over auth, sync, billing, metadata, contact-support, migrations, public privacy/deletion pages, and release docs.
   - Exit: `0` except one initial broad `rg` named a nonexistent `supabase/functions/store-purchase-verify` path while also returning valid results; corrected to `verify-store-purchase` and exact files. No conclusion relies on the nonexistent path.
   - Result: `PASS` after focused correction.
3. Official Google definitions
   - Read-only official Help Center lookup; no Console/browser state mutation.
   - Result: `PASS`; collect, ephemeral, pseudonymous, service-provider, user-action, data-type, purpose, payment, encryption, and deletion definitions recorded.
4. Exact signed AAB
   - Command: Python read-only SHA/ZIP/DEX predicate inspection of the exact signed AAB; endpoint values never emitted.
   - Exit: `0`.
   - Result: `PASS`; hash matched, structure valid, cloud config/HTTPS predicates present.
5. Proposal creation
   - Method: Python generated a patch to stdout; `apply_patch` created the CSV. No direct Python/file copy write was used.
   - Result: `PASS`.
6. Structural equivalence and answer completeness
   - Command: Python CSV comparator and invariant assertions.
   - Exit: `0`.
   - Result: `PASS`; all non-response fields equal, selected-category completeness checks pass, hashes/counts above.
7. Spreadsheet import validation
   - First attempt: bundled Node plus package-name import failed with `ERR_MODULE_NOT_FOUND`; no file changed.
   - Corrected attempt: direct bundled `artifact_tool.mjs` import and `Workbook.fromCSV` succeeded; `A1:E5` parsed as 5x5 and Device-ID rows were discoverable through row 783. The visible BOM character in the in-memory first header cell is the expected UTF-8 BOM marker, while Python `utf-8-sig` import yields the exact header.
   - Result: `PASS_AFTER_RETRY`.
8. Diff check
   - `git diff --check -- artifacts/full-go-audit/2026-08-13/google-play-data-safety-proposed.csv`.
   - Exit: `0`.
   - Result: `PASS`.

### 14.10 Finding reclassification

| Finding ID | Previous state | New state | Severity / rationale |
|---|---|---|---|
| `PLAY-DS-001` | Current Console lists only Web browsing and no account creation | Still `CONFIRMED_MAJOR_EXTERNAL`; proposal covers eight categories and correct account methods, but it is unsubmitted | Major until Console import/save/review/public propagation |
| `PLAY-DS-002` | Web browsing ephemeral=yes without provider-retention proof | `FIX_PROPOSED`; proposal sets ephemeral=false | Major remains external until applied |
| `PLAY-DS-003` | Account/data deletion unanswered | `FIX_PROPOSED`; proposal supplies yes + public URL | Major remains external until applied |
| `PLAY-DS-004` | Device/other IDs previously assessed absent | `CONFIRMED / PRIOR_FALSE_POSITIVE_CORRECTED`; proposal selects Device or other IDs | Major disclosure omission; direct client-ID/IP-hash evidence |
| `PLAY-DS-005` | Other actions not separately classified | `CONFIRMED`; proposal selects Other actions | Major disclosure omission as part of Web-browsing-only form |
| `PLAY-DS-006` | Exact Android AAB cloud/HTTPS evidence missing from S1 | `RESOLVED_BY_EXACT_ARTIFACT` | No remaining exact-AAB cloud-mode gap; no secret values retained |
| `PLAY-DS-007` | Public policy does not explicitly name persistent sync `client_id` or operation ledger | `PARTIALLY_DISCLOSED / DOC_DRAFT_FIXED / PUBLIC_DEPLOY_PENDING` | Current policy discloses hashed IP/user IDs and shared cloud content, but not this device/app identifier and durable action ledger with the same precision; release draft now records them. Public-policy owner review/update remains before Store submission. |
| `PLAY-DS-008` | No complete import artifact | `FIX_PROPOSED_AND_STRUCTURALLY_VERIFIED` | Proposal CSV exists and validates; external import intentionally not performed |
| `REL-PRIV-001` | Exact submitted AAB not independently re-extracted | `PARTIALLY_CLOSED`: exact-AAB cloud/HTTPS premise closed; dirty shared source snapshot remains | Not a reason to reject the exact-AAB privacy classification, but source integration still needs freeze before a new build |

### 14.11 Confirmed problem-free scope

- Original Console export was not modified.
- Proposal preserves all 782 response records and every non-response field.
- No ad/analytics/crash category was added without source evidence.
- Payment-card/bank information was not selected; the app does not receive that data, while Purchase history is selected because the app/backend receives product/token/transaction/entitlement data.
- Event-level `op_id`, request ID, and idempotency key were not misclassified as persistent Device IDs.
- Device or other IDs is now selected for the actual stable app client identifier and pseudonymous hashed IP use.
- No selected category is marked ephemeral.
- No analytics, advertising, personalization, or developer-communications purpose is selected without implementation evidence.
- No Console, Store, production, device, stage, commit, push, deploy, or secret mutation occurred.

### 14.12 Remaining unverified scope, external gates, and independent judgment

- Unverified: Play Console import parser acceptance of this exact CSV, because import was prohibited.
- Unverified: owner/legal confirmation that current Supabase and Resend arrangements satisfy Google's service-provider definition for all processing performed.
- Unverified: current Console interpretation of the optional outside-app-account field for Supabase provisioning through Google OAuth.
- Unverified: whether any other app version/region presently distributed under the package has stricter required collection; Google aggregates practices globally. If so, optional rows may need to become required.
- Unverified: production traffic, retention/deletion execution, provider logs, contracts, support delivery, real billing/account flows, and post-submission public propagation.
- Unverified: public privacy-policy deployment of the more explicit persistent sync identifier and durable-operation disclosure. The local release draft was corrected; no web deploy occurred.
- Current confirmed Blocker in this focused task: `0`.
- Current confirmed Critical in this focused task: `0`.
- Current unresolved Major external findings: `PLAY-DS-001`, `PLAY-DS-002`, `PLAY-DS-003`, `PLAY-DS-004`, and `PLAY-DS-005` remain open on the live/Console declaration until the owner-reviewed proposal is imported, reviewed, saved/submitted, and public propagation is captured. `PLAY-DS-007` is an external disclosure/documentation gate.
- Proposal artifact judgment: `READY_FOR_C0_AND_OWNER_PRE_IMPORT_REVIEW`.
- Google Play release judgment: `NO_GO_EXTERNAL` until the CSV answer matrix is owner-reviewed, any necessary sharing/optional/outside-account adjustments are made, the public policy is aligned, and Console import/save/submit/public verification succeeds.
- Internal classification judgment for exact signed cloud-enabled v21: `FULLY_MAPPED_WITHIN_DECLARED_SOURCE_AND_BINARY_SCOPE`; no known category remains unmapped in the proposal.
- Full project judgment remains `NO_GO / PARTIALLY_VERIFIED`; this CSV artifact cannot by itself create unconditional release approval.
- `事実上100%確認済み: NO`.
- Final focused report close: `2026-08-13T17:52:34+0900`; final validator and hash pass completed.

### 14.13 Finalization addendum (2026-08-13T17:52:34+0900)

- Scope was frozen to existing artifacts; no further product, Store, browser, device, or external-service exploration was performed during finalization.
- Original export: `/Users/mimac/Downloads/data_safety_export.csv`; SHA-256 `4d716520df8c26c81bc9262cb84f2d5b6d47b621e9c1190dc0f21e2434aceed4`.
- Proposed unsubmitted draft: `artifacts/full-go-audit/2026-08-13/google-play-data-safety-proposed.csv`; SHA-256 `a98bbb4d8ffa09f264a54cf1efc04248f3dd51faedc47b8738cb1ba5422a91c8`.
- CSV structural validator: PASS. Both files parse as 783 logical rows (one header plus 782 response records), every row has five columns, and the exact header/schema is preserved. The proposed draft is UTF-8 BOM encoded for Excel compatibility.
- Identity validator: PASS. `Question ID`, `Response ID`, `Answer requirement`, and human-readable label are value-equivalent row-for-row after CSV parsing; only `Response value` differs, on 52 response records.
- Final selected Google Play data-type IDs: `PSL_NAME`, `PSL_EMAIL`, `PSL_USER_ACCOUNT`, `PSL_PURCHASE_HISTORY`, `PSL_WEB_BROWSING_HISTORY`, `PSL_USER_GENERATED_CONTENT`, `PSL_OTHER_APP_ACTIVITY`, and `PSL_DEVICE_ID`.
- Reclassification is final for this draft: persistent/sent shared-tag `client_id` and hashed support-origin identifier require `Device or other IDs`; durable shared-tag mutations require `Other actions`. Prior S1 statements that excluded Device IDs are superseded and classified `FALSE_POSITIVE`.
- Exact signed Android artifact gap: CLOSED for privacy-behavior reachability. C0's direct, non-secret extraction from the signed Android 1.0.17/versionCode 21 AAB, combined with read-only digest verification (`e88c8e978bea63b8d811c12569fb8e6b28c62111da4bca39c500705ccc6f9605`), confirms cloud-enabled remote configuration and configured HTTPS service endpoints. No endpoint value, key, token, or other secret is reproduced here.
- Documentation hash after final draft update: `docs/release/privacy-data-safety-draft.md` SHA-256 `dd4a205eee70579d22e8bad9080580567608b9ae136c1316412ab228f9b40ca4`.
- `git diff --check` over the proposal CSV, release privacy/data-safety draft, and this raw report: PASS before this addendum; rerun after this addendum is recorded in the final handoff.
- External state remains unchanged: the proposed CSV was not imported, saved, or submitted to Google Play Console; no public privacy-policy deployment occurred; no device command was executed; no Git stage/commit/push/deploy occurred.
- Independent release decision for cloud-enabled Android v21 remains `NO_GO_EXTERNAL` until the owner reviews the eight-category proposal, confirms the two policy/Console interpretation gates documented above, updates the public privacy disclosure as needed, and explicitly saves/submits matching Console answers. The local draft itself is complete and structurally import-ready within the declared evidence scope.

## 15. 2026-08-21 account-deletion local cleanup addendum

### 15.1 Execution identity, independence, and time window

- Worker / thread ID: `019fee58-b16d-7642-9c7d-a61f1b0a93b6`.
- Parent representative / C0 task ID: `019fe6c5-f82e-7261-9e0c-5397d17a4695`.
- Requested model / effort: `gpt-5.6-sol` / `max`.
- Runtime model attestation was not exposed to this worker: `MODEL_ASSIGNMENT_UNVERIFIED`.
- Evidence-backed implementation start: `2026-08-21T23:34:22+0900`, the earliest modification time among the new account-cleanup production files in this resumed phase.
- Validation close: `2026-08-21T23:58:51+09:00`.
- Raw addendum persistence confirmation: `2026-08-22T00:01:15+09:00`.
- Source snapshot: root HEAD `965d4d0cdd8fd916bc5adc996fc682b9875022d3`, branch observed as `codex/full-go-mobile-s1-20260811`, shared dirty working tree. No branch operation, stage, commit, push, deploy, Store operation, or production operation was performed.
- Independence: S2's primary report body was not read for this phase. The parent-provided finding summary that the public privacy draft had exposed a local account-deletion cleanup gap was read before implementation. Source, tests, and the currently rendered public privacy HTML were then checked directly.
- Device boundary: no ADB command, Android connected/instrumentation task, install/uninstall, `pm clear`, physical Android operation, physical iPhone operation, Appium/WDA operation, or production account deletion was performed. iOS execution used only Simulator `Rinbam-S1-20260811`, UDID `876E2C29-9B8A-4C5F-9691-72929AE1975F`, iPhone 17 Pro model, iOS Simulator 26.5. Android verification in this phase was host-only JVM/lint/build.

### 15.2 Independent scope and result

Reviewed and implemented the account-**deletion** path separately from ordinary sign-out on Android and iOS. The deleted account's auth user ID is retained in a persistent local marker while any local stage remains unfinished. The staged local work is:

1. clear the deleted session;
2. cancel and await account-specific shared-tag sync work;
3. remove the exact account's shared-tag cache, Android outbox/retry/dedup state, sync client state, groups, members, tags, and references;
4. clear the pending invite;
5. clear the exact account's entitlement cache;
6. clear local AI draft/diff/receipt data, independently of the account-linked stages.

Each completed stage is persisted before the next retry boundary. A retry reads the marker and performs only unfinished local work; it never invokes the remote account-delete endpoint. Ordinary sign-out clears only the authentication session and does not invoke the deletion-only cleaner.

Data-preservation contract:

- Android cleanup SQL is exact-user-scoped at `SharedTagSyncCoordinator.kt:104-118`, with account-specific DAO deletes at `SharedTagSyncDao.kt:235-245` and `TagDao.kt:465-469`.
- Android recomputes references from remaining active synced tag references at `UrlEntryDao.kt:327-354`; only rows with both `localProvenanceCount = 0` and `sharedReferenceCount = 0` are removed.
- iOS exact-user transaction is at `SharedTagCloud.swift:1912-1931`; cross-account reference counts are recomputed at `SharedTagCloud.swift:1934-1950` before removing only shared-only unreferenced cache rows.
- Saved local URLs, user titles, memos, self-created tags, and archive state are outside the deletion query and are explicitly preserved by Android `SharedTagSyncRepositoryTest.kt:370-489` and iOS `SharedTagStoreTests.swift:122-208`.
- Android has a durable account-specific outbox and client/sync state, so those rows are deleted for the target user. The current iOS implementation has persisted snapshot/sync-state tables but no Android-equivalent durable mutation outbox/client-ID ledger; it cancels and awaits its in-flight sync task before deleting the target account's persisted snapshot/sync state.

### 15.3 Production changes and 1-based anchors

Android:

- `app/src/main/java/jp/mimac/urlsaver/data/LocalAccountCleanupStore.kt:8-133` — expanded the persistent marker to six explicit stages plus target auth user ID; synchronous SharedPreferences commit; safe legacy-stage loading.
- `app/src/main/java/jp/mimac/urlsaver/data/AccountLinkedLocalDataCleaner.kt:3-43` — new deletion-only orchestration boundary for WorkManager cancellation, shared-data cleanup, pending-invite cleanup, and entitlement cleanup.
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultTagRepository.kt:701-812` — normal sign-out separation, marker creation only after remote delete success, local-only retry, ordered stage gates, and complete `LocalCleanupRequired` state.
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncScheduler.kt:13-52` — account-specific unique-work cancellation with `Operation.await()`.
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt:100-119` — mutex-serialized, transaction-scoped exact-account cleanup.
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncDao.kt:235-245` — exact-user outbox and sync-state deletion.
- `app/src/main/java/jp/mimac/urlsaver/data/TagDao.kt:465-469` — exact-user synced-reference and synced-tag deletion.
- `app/src/main/java/jp/mimac/urlsaver/data/UrlEntryDao.kt:327-354` — full remaining-reference recomputation and provenance-safe shared-only cache pruning.
- `app/src/main/java/jp/mimac/urlsaver/data/PendingInviteStore.kt:33-39` — synchronous, failure-reporting invite clear.
- `app/src/main/java/jp/mimac/urlsaver/data/EntitlementGrantStore.kt:37,74-113` — serialized exact-user cache clear.
- `app/src/main/java/jp/mimac/urlsaver/data/EntitlementGrantRepository.kt:21-68` — prevents an in-flight refresh/redeem for an old session from repopulating the deleted account's cache; preserves coroutine cancellation.
- `app/src/main/java/jp/mimac/urlsaver/app/AppContainer.kt:143-215,275-293` — production cleaner wiring with the same scheduler/coordinator/invite/entitlement instances.
- `app/src/main/java/jp/mimac/urlsaver/domain/TagModels.kt:249-261`, `app/src/main/java/jp/mimac/urlsaver/ui/SharedTagAuthViewModel.kt:120-150`, and `app/src/main/java/jp/mimac/urlsaver/ui/SharedTagCloudScreens.kt:711-724` — exposes every unfinished cleanup stage instead of hiding account-linked failures behind one generic boolean.

iOS:

- `ios/URLSaverShared/Domain/SharedTagCloudModels.swift:149-184` — durable cleanup state now carries target auth user ID and every independent stage.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:1260-1289` — exact-user entitlement-cache cleanup; refresh/redeem paths revalidate session ownership before cache writes.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:1912-1950` — exact-account transactional shared-cache cleanup and cross-account reference-count preservation.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:2111-2182` — persistent UserDefaults marker including target auth user ID and all stages.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:2185-2213` — in-flight sync cancellation and awaited quiescence with generation protection.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:2458-2460` — ordinary sign-out now clears only the auth session.
- `ios/URLSaverShared/Data/SharedTagCloud.swift:3114-3235` — remote delete once, persistent local marker, local-only retry, ordered stage gates, and exact per-stage result retention.
- `ios/URLSaveriOS/App/AppServices.swift:60-88` — one shared entitlement cache plus production pending-invite and entitlement cleanup closures.
- `ios/URLSaveriOS/App/URLSaverAppModel.swift:1312-1388` and `ios/URLSaveriOS/UI/SharedTagCloudSheet.swift:735-778` — retry action and an explicit user-facing list of every unfinished stage; states clearly that retry does not repeat cloud deletion.

Tests added or extended:

- Android: `TagRepositoryTest.kt:177-450`, `SharedTagSyncRepositoryTest.kt:370-489`, `EntitlementGrantStoreTest.kt:18-43`, `EntitlementResolverTest.kt`, `LocalAccountCleanupStoreTest.kt:15-36`, and `SharedTagAuthViewModelTest.kt`.
- iOS: `AiTransparencyTests.swift:303-568`, `SharedTagStoreTests.swift:122-246`, and `URLRulesTests.swift:383-406`.

### 15.4 Behavioral evidence and failure history

Confirmed behavior:

- Remote account deletion failure leaves session and all local data intact.
- Remote account deletion success creates a marker containing the target auth user ID before local stages run.
- AI cleanup, session clear, sync cancellation, shared-data cleanup, pending-invite cleanup, and entitlement cleanup can fail independently; each unfinished flag remains visible and retryable.
- Session-clear failure blocks sync cancellation and all account-linked deletion. Sync-cancellation failure blocks the shared/invite/entitlement stages.
- Local retry does not repeat remote deletion: Android and iOS request counters remain exactly one across failure plus retry.
- Marker recreation tests recover target auth user ID and every pending stage.
- Exact-user deletion removes Android client/sync state and outbox for user A while preserving user B.
- Local URL, memo, local tag, archive state, and another account's shared cache survive account A deletion on both platforms.
- Normal sign-out does not call deletion-only cleanup; iOS verifies the shared cache remains queryable after sign-out.
- An entitlement network response for a session cleared while the request is in flight cannot repopulate that account's local cache.

Initial failure and correction:

- First command: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew compileDebugKotlin compileDebugUnitTestKotlin`.
- Initial result: `FAIL`; Kotlin compilation reported an unresolved `androidx.datastore.preferences.core.remove` import in `EntitlementGrantStore.kt`. The preferences receiver already provides `remove`, so the invalid import was removed without changing behavior.
- Same command rerun: `PASS`.

Focused Android test command:

`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.TagRepositoryTest --tests jp.mimac.urlsaver.SharedTagSyncRepositoryTest --tests jp.mimac.urlsaver.EntitlementResolverTest --tests jp.mimac.urlsaver.EntitlementGrantStoreTest --tests jp.mimac.urlsaver.LocalAccountCleanupStoreTest --tests jp.mimac.urlsaver.SharedTagAuthViewModelTest`

- Exit: `0`; `60/60 PASS`, `0 failure`, `0 error`, `0 skip`, recomputed from the final six JUnit XML suites.
- The build-level connected-device guard's pure host logic also reported `13/13` decision cases and `6/6` task-wiring checks PASS. No connected task or ADB process was started.

iOS build-for-testing command:

`xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/rinbam-s1-account-cleanup-derived build-for-testing CODE_SIGNING_ALLOWED=NO`

- Exit: `0`; `** TEST BUILD SUCCEEDED **`.

Focused iOS command:

`xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -destination 'platform=iOS Simulator,id=876E2C29-9B8A-4C5F-9691-72929AE1975F' -derivedDataPath /tmp/rinbam-s1-account-cleanup-derived -resultBundlePath /tmp/rinbam-s1-account-cleanup-focused-20260821-2353.xcresult test-without-building -only-testing:URLSaveriOSTests/AiTransparencyTests -only-testing:URLSaveriOSTests/SharedTagStoreTests -only-testing:URLSaveriOSTests/URLRulesTests`

- Exit: `0`; `60/60 PASS`, `0 failure`, `0 skip`.
- Evidence: `/tmp/rinbam-s1-account-cleanup-focused-20260821-2353.xcresult`.

Full Android host-only command:

`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest lintDebug assembleDebug`

- Exit: `0`; `BUILD SUCCESSFUL in 1m 41s`.
- JUnit XML aggregation: `432/432 PASS`, `0 failure`, `0 error`, `0 skip`, across 47 suite files.
- `lintDebug`: PASS. Report: `app/build/reports/lint-results-debug.html`.
- `assembleDebug`: PASS. No install task ran.

Full iOS Simulator command:

`xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -destination 'platform=iOS Simulator,id=876E2C29-9B8A-4C5F-9691-72929AE1975F' -derivedDataPath /tmp/rinbam-s1-account-cleanup-derived -resultBundlePath /tmp/rinbam-s1-account-cleanup-full-20260821-2354.xcresult test-without-building`

- Exit: `0`; `214` total, `211 PASS`, `0 failure`, `3 skip`.
- Skips are the three explicitly live-cloud integration cases: `testOwnerCanSyncAndroidMigratedSharedTagFromLiveCloud()`, `testInviteFlowLetsSecondIOSClientJoinAndReadSharedURL()`, and `testCreateInviteForAndroidDeviceAcceptance()`.
- Evidence: `/tmp/rinbam-s1-account-cleanup-full-20260821-2354.xcresult`.

Static/final guards:

- `python3 scripts/verify_mobile_ui_contract.py`: exit `0`, `Mobile UI contract check passed.`
- `git diff --check`: exit `0`, no output.
- No source format command, dependency update, migration, database on a real device, or deletion command was run.

### 15.5 Finding ledger

| Finding ID | Severity | Final state | Direct evidence |
|---|---|---|---|
| `S1-ACCDEL-001` account deletion could leave account-linked Android sync ID/outbox/dedup/cache and analogous iOS local shared state | Major | `FIXED_AND_VERIFIED` in isolated local scope | Exact-user transactional cleaners; Android `SharedTagSyncRepositoryTest.kt:370-489`; iOS `SharedTagStoreTests.swift:122-208` |
| `S1-ACCDEL-002` local-stage failure lacked a complete durable per-stage retry contract with target user ID | Major | `FIXED_AND_VERIFIED` | Android persistent marker `LocalAccountCleanupStore.kt:8-133`; iOS marker `SharedTagCloud.swift:2111-2182`; recreation and fail-once tests |
| `S1-ACCDEL-003` a retry after successful remote deletion could risk repeating remote deletion or hide unfinished work | Major | `FIXED_AND_VERIFIED` | Android `DefaultTagRepository.kt:705-812`; iOS `SharedTagCloud.swift:3114-3235`; request-count tests prove one remote call across retry |
| `S1-ACCDEL-004` ordinary sign-out and account deletion cleanup were not consistently separated; iOS sign-out cleared all local shared cache | Major | `FIXED_AND_VERIFIED` | Android `DefaultTagRepository.kt:701-703` plus `TagRepositoryTest.kt:357-369`; iOS `SharedTagCloud.swift:2458-2460` plus `SharedTagStoreTests.swift:210-246` |
| `S1-ACCDEL-005` entitlement fetch could repopulate a deleted account cache after session removal | Major | `FIXED_AND_VERIFIED` | Session revalidation in Android `EntitlementGrantRepository.kt:21-68` and iOS entitlement service; Android race regression test and full suites PASS |
| `S1-PRIV-009` public privacy paragraph still says Android sync identifiers/records may remain after account deletion and require app-data deletion | Major documentation accuracy gate | `FIX_PROPOSED_NOT_APPLIED` because this worker was prohibited from editing web/docs | Current source: `web/invite-link/privacy/index.html:101-104`; replacement text below |

### 15.6 Public privacy wording proposal for C0 / web owner

The current public paragraph at `web/invite-link/privacy/index.html:104` is no longer accurate after this source change because it says Android users must delete all app data to remove the local sync identifier/outbox. Replace that Android-only sentence, and preferably the whole paragraph, with wording equivalent to:

> 共有タグクラウドのアカウントを削除すると、そのユーザーに結び付くクラウド側の同期元識別子と重複防止用の操作記録は削除処理の対象になります。他の参加者が引き続き利用する共有タグ、URL、グループなどは、必要な所有者変更やユーザーとの結び付けの解除を行ったうえで残る場合があります。アプリ内のアカウント削除が成功すると、端末では進行中の共有タグ同期を停止し、削除対象アカウントに結び付く同期識別子、送信待ち・再試行・重複防止状態、共有データのキャッシュ、保留中の招待、利用権限キャッシュを消去します。消去が中断した場合は、クラウドの削除を繰り返さず端末内の消去を再試行するため、対象ユーザーの識別子と未完了項目を端末内に保持し、完了後に消去します。通常のサインアウトはログイン情報だけを消去し、このアカウント削除専用の端末内消去は行いません。端末内に保存した通常のURL、タイトル、メモ、自作タグ、アーカイブ状態はアカウント削除の対象外です。

The account-deletion page at `web/invite-link/account-deletion/index.html:93-98` should also add the same distinction in shorter form: account deletion clears account-linked sync state/cache on-device, retry may temporarily retain the target identifier and unfinished-stage marker, ordinary saved local content remains, and ordinary sign-out is not account deletion.

No web/docs file was edited by S1 in this phase.

### 15.7 Unverified scope, residual risk, and independent release decision

- Not verified: production Supabase account deletion, provider-side cascades/retention, live WorkManager cancellation against an actual queued worker, live StoreKit/Google Play entitlement state, real pending invite state, process-kill timing on a physical device, physical Android/iPhone behavior, or migration from any previously released cleanup marker. All destructive behavior tests used isolated JVM/temporary SQLite/UserDefaults state or the dedicated iOS Simulator.
- A power/process loss in the narrow interval after the remote service has committed deletion but before the first local success marker is persisted cannot be proven away solely by client tests; closing that distributed-systems uncertainty requires an idempotent/status-queryable server deletion contract. Once the marker is persisted, all tested retries are local-only and remote delete is not repeated.
- The public privacy page remains stale until the web owner applies, validates, deploys, and captures the replacement wording. This is an external documentation/release gate, not an unresolved mobile source defect.
- The shared working tree contains many unrelated uncommitted changes from C0/S2 and prior S1 phases. This worker did not stage, commit, revert, reformat, or delete them. Snapshot freeze/integration ownership remains with C0.
- Confirmed Blocker in this focused mobile scope: `0`.
- Confirmed Critical in this focused mobile scope: `0`.
- Confirmed unresolved Major mobile implementation findings in this focused scope: `0`.
- Mobile implementation judgment for the declared local/test scope: `RELEASE_READY_WITHIN_DECLARED_LOCAL_SCOPE` / `FULLY_VERIFIED_WITHIN_ISOLATED_TEST_SCOPE`.
- Full public release judgment: `NO_GO_EXTERNAL` until the public privacy/account-deletion wording is aligned and deployed, production/provider deletion behavior is verified, and C0 freezes and revalidates the integrated snapshot.
- `事実上100%確認済み: NO`; production, physical-device, external-provider, live Store, and final integrated-snapshot evidence remain outside this addendum.

## 16. 2026-08-22 account scope, operation fence, and atomic iOS cleanup-marker follow-up

This section is a later, self-contained S1 addendum. Where it conflicts with section 15, this section supersedes section 15. In particular, the section 15.6 wording that said an unowned pending invite is removed is withdrawn: the implemented contract preserves an unbound or other-account invite because the existing pending-invite store has no authenticated owner binding.

### 16.1 Execution ledger, independence, and snapshot

- Role: S1 mobile implementation and independent rebuttal owner under representative questioner C0.
- Parent C0 task ID: `019fe6c5-f82e-7261-9e0c-5397d17a4695`.
- S1 real agent / thread ID: `019fee58-b16d-7642-9c7d-a61f1b0a93b6`.
- Requested model / effort: `gpt-5.6-sol`, effort `max`.
- Runtime model attestation: unavailable; `MODEL_ASSIGNMENT_UNVERIFIED`.
- Independent-agent runtime attestation: unavailable; `INDEPENDENT_AGENT_EXECUTION_UNVERIFIED`.
- Follow-up start: `2026-08-22 00:19:30 JST`, the first independently retained timestamp for this change set (`AccountOperationFence.kt` mtime). Exact delegation-receipt time was not retained, so `START_TIME_PARTIALLY_UNVERIFIED` applies to any earlier analysis.
- Implementation and final test completion: `2026-08-22 01:18:14 JST`.
- Raw addendum close: `2026-08-22 01:22:07 JST`.
- Repository root: `/Users/mimac/Desktop/りんばむ`.
- Snapshot inspected at close: branch `codex/full-go-mobile-s1-20260811`, HEAD `10b2e3e332dc5dc606e59cfc767712fb7d0a9ff3`.
- Worktree: shared and dirty before this follow-up; unrelated C0/S2/prior-S1 changes were not reverted, reformatted, staged, committed, moved, or deleted.
- Other-review visibility: S2's unfinished or final primary/raw report was not opened. S1 first checked the source and tests independently. C0/user later supplied specific S2 rebuttal findings (`S2-ACCDEL-002`, `S2-ACCDEL-010`, pending-invite ownership, entitlement TOCTOU, foreground-mutation fencing, and the server-success-to-marker gap); those supplied findings were then used as explicit rebuttal inputs. Thus `OTHER_PRIMARY_REVIEW_NOT_READ`, but `PARENT_SUPPLIED_REBUTTAL_FINDINGS_READ_AFTER_INDEPENDENT_SOURCE_CHECK`.
- Git/external/device operations: no branch operation, checkout, restore, reset, rebase, stash, stage, commit, push, deploy, Store operation, production call, secret access, ADB, connected Android task, install/uninstall, physical Android, physical iPhone, Appium, or real-device operation.

### 16.2 Final finding ledger

| Finding ID | Severity | Final state | Direct result |
|---|---:|---|---|
| `S1-ACC-SCOPE-001` Android/iOS ChatGPT personal-link enabled/last-sync/error state was not scoped to the authenticated user and ownerless legacy state could flow to another account | Major | `FIXED_AND_VERIFIED` | Both platforms now key settings by a one-way account-derived scope, preserve A for A relogin, show defaults for B, fail closed for ownerless legacy state, clear only deleted A, and preserve saved local URLs. |
| `S1-ENTITLEMENT-002` a stale entitlement request/cache instance could return or repersist account A grants after sign-out, deletion, or switch to B | Major | `FIXED_AND_VERIFIED` | Session revalidation surrounds fetch/fallback/use, UI entitlement state is refreshed on session/deletion transitions, and persistent invalidated-user fences reject late A writes atomically while leaving B available. |
| `S1-ACCDEL-FENCE-003` foreground shared-tag mutation/sync could recreate deleted A cache/outbox/state after cleanup | Major | `FIXED_AND_VERIFIED` | One account-operation gate/fence now serializes foreground operations with account deletion, waits already-started work, and rejects late A work using both in-memory deletion state and the durable cleanup marker. |
| `S1-PENDING-INVITE-004` account A deletion unconditionally cleared a global/unowned pending invite | Minor | `FIXED_AND_VERIFIED` | New deletion markers set invite cleanup false; legacy invite stages are retired without invoking the invite cleaner. Android and iOS behavior tests preserve the unbound invite. |
| `S1-IOS-MARKER-005` / `S2-ACCDEL-002` iOS target user ID and cleanup stages were persisted as multiple UserDefaults keys and could tear across process loss | Major | `FIXED_AND_VERIFIED` | One Codable state is encoded as one `Data` value under one v2 key; legacy partial keys migrate fail closed, corrupt blobs regenerate as fail-closed blobs, recreated stores recover the same state, and concurrent saves produce one complete state rather than mixed fields. |
| `S1-ACCDEL-SINGLEFLIGHT-007` simultaneous account deletion and local retry could duplicate remote deletion or race marker stages | Major | `FIXED_AND_VERIFIED` | Both platforms execute delete/retry through one exclusive account-operation critical section; tests hold the first delete and prove one remote request across concurrent retry. |
| `S2-ACCDEL-010` Android foreground Room transaction then sync mutex versus cleanup sync mutex then Room transaction could deadlock | Major hypothesis, statically credible | `FIXED_AND_VERIFIED` | Account fence is acquired before DB work; all reviewed `ensureClientId` calls are before their Room transactions; already-gated code calls the non-reentrant `syncForAuthUserWithinAccountOperation`; deterministic latch/timeout tests complete. |
| `S1-REMOTE-MARKER-006` process loss after the server commits `delete_my_account()` but before the first durable local marker is saved | Major | `CONFIRMED_UNRESOLVED_SERVER_CONTRACT` | In-process work is fenced immediately after remote success, but a process crash in that interval cannot be made status-queryable or fully idempotent by this client alone. The server currently exposes neither a durable request ID/status lookup nor a client-verifiable idempotent deletion result. Auth failure is intentionally not reclassified as success. |

No finding was closed by severity reduction alone. Every `FIXED_AND_VERIFIED` row above has source evidence and at least one behavior/race/recreation test. `S1-REMOTE-MARKER-006` remains open and prevents an unconditional full-release GO.

### 16.3 Android implementation evidence

- Shared account gate: `app/src/main/java/jp/mimac/urlsaver/data/AccountOperationFence.kt:6-51`. It serializes account-scoped work and deletion, uses the durable marker after recreation, and records remote-deleted user IDs before the first marker write.
- Shared dependency instance: `app/src/main/java/jp/mimac/urlsaver/app/AppContainer.kt:121-126,148-165,178-192,207-227,287-306`. Entitlement, personal-link sync, shared-tag sync, and deletion use the same fence/store instances.
- Account deletion and local-only retry: `app/src/main/java/jp/mimac/urlsaver/data/DefaultTagRepository.kt:758-885`. Remote deletion runs once; target user and each unfinished stage are saved; pending invite is not deleted; entitlement and personal-link cleanup are exact-user stages.
- Lock order: reviewed `ensureClientId` call sites at `DefaultTagRepository.kt:252,312,372,555,1256,1306,1369`; each resolves the sync-side identifier before its Room transaction. The already-fenced call uses `syncForAuthUserWithinAccountOperation` at `DefaultTagRepository.kt:1465` and `SharedTagSyncCoordinator.kt:44-109`, avoiding gate re-entry.
- Exact-user local cleaner: `app/src/main/java/jp/mimac/urlsaver/data/AccountLinkedLocalDataCleaner.kt:3-52`.
- Durable stage marker, including personal-link cleanup and forced preservation of an unowned pending invite: `app/src/main/java/jp/mimac/urlsaver/data/LocalAccountCleanupStore.kt:8-133`.
- Account-scoped ChatGPT settings and session checks: `app/src/main/java/jp/mimac/urlsaver/data/ChatGptPersonalLinkSync.kt:47-125,240-378`.
- Entitlement session checks and deletion invalidation fence: `app/src/main/java/jp/mimac/urlsaver/data/EntitlementGrantRepository.kt:7-89`; `app/src/main/java/jp/mimac/urlsaver/data/EntitlementGrantStore.kt:40-135`.
- UI state refresh and complete unfinished-stage disclosure: `app/src/main/java/jp/mimac/urlsaver/ui/SharedTagAuthViewModel.kt:62-89,131-160,196-232`; `app/src/main/java/jp/mimac/urlsaver/ui/SharedTagCloudScreens.kt:712-723`.

Behavior anchors:

- Account fence wait/block/recreation: `app/src/test/java/jp/mimac/urlsaver/AccountOperationFenceTest.kt:18-89`.
- Single-flight delete/retry, persistent marker, sign-out separation, failed-stage retry, invite preservation, and lock-order timeout: `app/src/test/java/jp/mimac/urlsaver/TagRepositoryTest.kt:292-587`.
- A sign-out to B, A relogin, ownerless legacy fail-closed, in-flight switch, exact-user deletion, and local URL preservation: `app/src/test/java/jp/mimac/urlsaver/ChatGptPersonalLinkSyncRepositoryTest.kt:181-244`.
- Entitlement late-write rejection after deletion: `app/src/test/java/jp/mimac/urlsaver/EntitlementGrantStoreTest.kt:18-75`.

### 16.4 iOS implementation evidence, including the atomic marker requirement

- Codable marker schema: `ios/URLSaverShared/Domain/SharedTagCloudModels.swift:150-188` contains the target auth user ID and every cleanup stage in one value.
- One-key blob store: `ios/URLSaverShared/Data/SharedTagCloud.swift:2154-2303`.
  - The only current-state key is `...state.v2` (`:2180`).
  - `load()` decodes one `Data` blob and rewrites a corrupt value to an explicit fail-closed state (`:2191-2201`).
  - Any surviving legacy key is treated as evidence of an interrupted old write. Missing account-owned stage keys therefore migrate as pending; the unowned pending-invite stage migrates false (`:2202-2220`).
  - `save()` encodes the whole target/stage value and writes the blob before removing legacy keys (`:2223-2232,2266-2290`). A process loss after the blob write but before old-key removal remains safe because reads prefer the blob.
  - A corrupt blob regenerates with all account-owned cleanup stages pending, no unowned invite deletion, and no fabricated target account (`:2293-2302`). A missing target causes account work to fail closed rather than guessing ownership (`SharedTagCloud.swift:2403-2415`).
- Shared account-operation gate: `SharedTagCloud.swift:2305-2417` waits started account work, serializes delete/retry, and blocks post-deletion A work.
- Account-scoped personal-link settings: `SharedTagCloud.swift:2419-2544`.
- Entitlement invalidation and session fencing: `SharedTagCloud.swift:1036-1143,1283-1375`.
- Deletion state machine and local-only retry: `SharedTagCloud.swift:3786-3930`. It marks the remote-deleted user in memory before saving the first blob, never deletes an unbound invite, and clears only exact-user shared/entitlement/personal-link state after sign-out and sync quiescence.
- Shared service construction: `ios/URLSaveriOS/App/AppServices.swift:60-95` injects one marker store and one gate into entitlement and shared-tag services.
- Entitlement/UI refresh and complete unfinished-stage copy: `ios/URLSaveriOS/App/URLSaverAppModel.swift:1302-1380`; `ios/URLSaveriOS/UI/SharedTagCloudSheet.swift:737-790`.

Atomic-marker behavior evidence:

- One-blob persistence and recreation: `ios/URLSaveriOSTests/AiTransparencyTests.swift:542-572`.
- Torn legacy multi-key migration with missing-stage fail-closed behavior and v2 recreation: `AiTransparencyTests.swift:574-607`.
- Corrupt blob regeneration into a decodable fail-closed blob, then recreation: `AiTransparencyTests.swift:609-642`.
- Two independently created store instances concurrently save 100 times; the result is exactly complete state A or complete state B, never a mixed stage/user combination: `AiTransparencyTests.swift:644-686`.
- Personal-link A/B/sign-out/deletion and URL survival: `AiTransparencyTests.swift:688-759`.
- Gate waits already-started work and blocks late deleted-account work: `AiTransparencyTests.swift:761-798`.
- Unbound invite survives account deletion: `AiTransparencyTests.swift:800-813`.
- Cross-instance entitlement clear/save and concurrent interleaving always end invalidated for A while B remains usable: `ios/URLSaveriOSTests/URLRulesTests.swift:406-479`.

The one-key design removes the confirmed torn-field failure mode. `UserDefaults.set` is still not a proof of physical-media `fsync`, and it does not close the independent server-success-to-first-marker crash interval described by `S1-REMOTE-MARKER-006`.

### 16.5 Files changed for this follow-up

Android production:

- `app/src/main/java/jp/mimac/urlsaver/app/AppContainer.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/AccountLinkedLocalDataCleaner.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/AccountOperationFence.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/ChatGptPersonalLinkSync.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultTagRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/EntitlementGrantRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/EntitlementGrantStore.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/LocalAccountCleanupStore.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/SharedTagSyncCoordinator.kt`
- `app/src/main/java/jp/mimac/urlsaver/domain/TagModels.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/SharedTagAuthViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/SharedTagCloudScreens.kt`

Android tests:

- `app/src/test/java/jp/mimac/urlsaver/AccountOperationFenceTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/ChatGptPersonalLinkSyncRepositoryTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/EntitlementGrantStoreTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/EntitlementResolverTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/LocalAccountCleanupStoreTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/SharedTagAuthViewModelTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/SharedTagSyncRepositoryTest.kt`
- `app/src/test/java/jp/mimac/urlsaver/TagRepositoryTest.kt`

iOS production:

- `ios/URLSaverShared/Domain/SharedTagCloudModels.swift`
- `ios/URLSaverShared/Data/SharedTagCloud.swift`
- `ios/URLSaveriOS/App/AppServices.swift`
- `ios/URLSaveriOS/App/URLSaverAppModel.swift`
- `ios/URLSaveriOS/UI/SharedTagCloudSheet.swift`

iOS tests:

- `ios/URLSaveriOSTests/AiTransparencyTests.swift`
- `ios/URLSaveriOSTests/URLRulesTests.swift`
- `ios/URLSaveriOSTests/SharedTagStoreTests.swift`

Evidence files added/updated only after code/test completion:

- `artifacts/full-go-audit/2026-08-13/sol-max-s1-raw.md`
- `artifacts/full-go-audit/2026-08-13/sol-max-s1-raw.md.sha256` (sibling checksum; used because embedding a file's own final hash in that file is self-referential)

No web/docs production text was edited in this follow-up.

### 16.6 Commands, failures, final results, and evidence paths

Baseline/static guards:

- `python3 scripts/verify_mobile_ui_contract.py` — exit `0`, `Mobile UI contract check passed.`
- `git diff --check` — exit `0`, no output before and after implementation; final post-report check is recorded in the sibling checksum validation handoff.

Android focused command:

`env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:/usr/bin:/bin:/usr/sbin:/sbin ./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.TagRepositoryTest --tests jp.mimac.urlsaver.SharedTagSyncRepositoryTest --tests jp.mimac.urlsaver.AccountOperationFenceTest --tests jp.mimac.urlsaver.EntitlementGrantStoreTest --tests jp.mimac.urlsaver.ChatGptPersonalLinkSyncRepositoryTest --tests jp.mimac.urlsaver.EntitlementResolverTest --tests jp.mimac.urlsaver.LocalAccountCleanupStoreTest --tests jp.mimac.urlsaver.SharedTagAuthViewModelTest --no-daemon`

- Exit `0`; `BUILD SUCCESSFUL in 23s`; focused class XML aggregation `76/76 PASS`, `0 failure`, `0 error`, `0 skip`.

Android full host-only command:

`env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:/usr/bin:/bin:/usr/sbin:/sbin ./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon`

- Exit `0`; `BUILD SUCCESSFUL in 1m 32s`.
- Final XML aggregation from `app/build/test-results/testDebugUnitTest/`: `48` suites, `443` tests, `443 PASS`, `0 failure`, `0 error`, `0 skip`.
- `lintDebug`: PASS; report `app/build/reports/lint-results-debug.html`.
- `assembleDebug`: PASS. No install or connected task ran.
- The pure host connected-test guard remained `13/13` decision cases plus `6/6` task-wiring checks PASS; it did not start ADB.

Android failure history retained rather than hidden:

- One initial focused invocation used the default/JDK 17 environment and failed in Robolectric Android API 36 setup; all formal reruns used the repository-required JDK 21 path above.
- The first deterministic lock-order test attempt waited indefinitely and was manually interrupted (exit `130`). Root cause was an isolated fake/test fixture leaving the first phase's outbox record pending, so phase two never reached its intended pull latch. The test setup was narrowed to remove only its own isolated test outbox between phases; the production lock order was unchanged. The focused and full suites then passed.

iOS focused history:

- `/tmp/rinbam-s1-account-scope-focused-20260822-1.xcresult`: compile failure from three awaited expressions placed inside XCTest autoclosures. Awaited values were captured before assertions; no assertion was removed or weakened.
- `/tmp/rinbam-s1-account-scope-focused-20260822-2.xcresult`: `24/24 PASS`.
- `/tmp/rinbam-s1-account-scope-focused-20260822-3.xcresult`: `60/60 PASS`.
- `/tmp/rinbam-s1-account-scope-focused-20260822-4.xcresult`: `37/37 PASS`.
- `/tmp/rinbam-s1-account-scope-focused-20260822-5.xcresult`: `25/25 PASS`, `0 failure`, `0 skip`; this is the final narrow account-scope/marker result.

iOS full Simulator command:

`xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -sdk iphonesimulator -destination 'platform=iOS Simulator,id=876E2C29-9B8A-4C5F-9691-72929AE1975F' -derivedDataPath /tmp/rinbam-s1-account-scope-final-derived -resultBundlePath /tmp/rinbam-s1-account-scope-full-20260822-019fee58-1.xcresult CODE_SIGNING_ALLOWED=NO test`

- Exit `0`; result `Passed`; `223` total, `220 PASS`, `0 failure`, `3 skip`.
- Simulator evidence: `Rinbam-S1-20260811`, iPhone 17 Pro, iOS 26.5, UDID `876E2C29-9B8A-4C5F-9691-72929AE1975F`.
- Three skips are the intentionally live-cloud integration tests: `testCreateInviteForAndroidDeviceAcceptance()`, `testInviteFlowLetsSecondIOSClientJoinAndReadSharedURL()`, and `testOwnerCanSyncAndroidMigratedSharedTagFromLiveCloud()`.
- Evidence bundle: `/tmp/rinbam-s1-account-scope-full-20260822-019fee58-1.xcresult`.

iOS generic Simulator build:

`xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/rinbam-s1-account-scope-final-build-derived CODE_SIGNING_ALLOWED=NO build`

- Exit `0`; `BUILD SUCCEEDED`.

Device and environment boundary:

- Android: host-only unit/lint/assemble. No emulator, AVD, ADB, instrumentation, connected task, APK install, physical Pixel, or app-data operation in this follow-up.
- iOS: dedicated Simulator tests/build only. No physical iPhone, signed-device build, install, Appium/WDA, or real app data.
- Production Supabase, live WorkManager queue behavior, StoreKit, Google Play, deployed privacy pages, provider cascades/retention, and live account deletion were not exercised.

### 16.7 Corrected public privacy wording proposal for C0 / web owner

Section 15.6 is superseded. The public text must not claim that account deletion removes an unbound pending invite. Suggested implementation-accurate wording:

> 共有タグクラウドのアカウント削除が成功すると、アプリは対象アカウントに結び付く共有タグ同期識別子、送信待ち・再試行・重複防止状態、共有データの端末内キャッシュ、利用権限キャッシュ、ChatGPT連携設定を消去します。通常のサインアウトでは同じユーザーが再ログインした際に必要なアカウント別設定やキャッシュを保持する場合がありますが、別のアカウントへ引き継ぎません。端末内に保存した通常のURL、タイトル、メモ、自作タグ、アーカイブ状態、および所有アカウントを特定できない保留中の招待は削除しません。端末内消去が中断した場合は、クラウド削除を繰り返さず再試行できるよう、対象ユーザー識別子と未完了項目だけを端末内に保持し、完了後に消去します。

The server-contract residual must be documented separately in the release/runbook, not disguised in consumer copy: a process loss after remote commit but before the first local marker needs a server-side idempotency/status contract to be fully recoverable.

### 16.8 Unverified scope, remaining risk, Git state, and independent release judgment

- Confirmed Blocker in this follow-up: `0`.
- Confirmed Critical in this follow-up: `0`.
- Fixed and verified Major findings in this follow-up: `6` (`S1-ACC-SCOPE-001`, `S1-ENTITLEMENT-002`, `S1-ACCDEL-FENCE-003`, `S1-IOS-MARKER-005`/`S2-ACCDEL-002`, `S1-ACCDEL-SINGLEFLIGHT-007`, `S2-ACCDEL-010`).
- Fixed and verified Minor findings in this follow-up: `1` (`S1-PENDING-INVITE-004`).
- Unresolved Major: `1` (`S1-REMOTE-MARKER-006`, server idempotency/status contract required).
- Account-owned local cleanup, account separation, late-write fences, lock order, retry stages, marker migration, corrupt/recreated marker behavior, and non-destructive local-URL preservation are `FIXED_AND_VERIFIED` within isolated host/Simulator tests.
- Production provider deletion/cascade/retention, live queue cancellation, live Store entitlement behavior, physical devices, deployed privacy text, and final integrated-snapshot freeze remain unverified.
- Git stage: none. Commit: none. Push/deploy/Store operation: none.
- Independent mobile client judgment for the fixed local/test scope: `RELEASE_READY_WITHIN_DECLARED_LOCAL_SCOPE`.
- Independent full public release judgment: `NO_GO` while `S1-REMOTE-MARKER-006` remains Major, public wording is not yet aligned/deployed, production behavior is unverified, and the shared integrated snapshot is not frozen/revalidated.
- Review completion state: `PARTIALLY_VERIFIED`; `事実上100%確認済み: NO`.
- Final raw-file SHA-256 is stored in the sibling `sol-max-s1-raw.md.sha256`; a sibling is used because placing the final file hash inside the hashed file would change that hash.

## 17. 2026-08-22 OpenCode continuation addendum — server deletion idempotency implemented (S1-REMOTE-MARKER-006)

- Runtime: OpenCode; implementation by `opencode/x-preview-f-free` (Ox Alpha Free); independent audit + rebuttal executed as separate `opencode run --model opencode/nemotron-3-ultra-free` sessions (routing VERIFIED, not MODEL_ROUTING_BLOCKED). Snapshot: branch `codex/full-go-mobile-s1-20260811`, HEAD `10b2e3e332dc5dc606e59cfc767712fb7d0a9ff3`, shared dirty tree preserved (no reset/restore/clean/branch ops).
- `S1-REMOTE-MARKER-006`: `FIXED_AND_VERIFIED` in isolated local scope. Server contract added by NEW forward-only migration `supabase/migrations/20260822090000_account_deletion_idempotency.sql`: `account_deletion_requests` (RLS on, zero policies, all direct roles revoked), `create_account_deletion_request()` (auth-only, returns one-time 256-bit hex token, stores sha256 hash only), single canonical `delete_my_account(p_request_id default null)` replacing the zero-arg definition via drop+replace inside the new migration (legacy `{}` PostgREST bodies resolve unambiguously), completed-request replay returns identical success with no redone effects, `owner_transfer_required` leaves the request retryable-pending, legacy no-argument callers converge their own pending rows on committed deletion, and anon-callable `get_account_deletion_status(request_id, token)` returning generic `not_found` on any mismatch. No applied migration file was modified.
- Client integration: Android (`AccountDeletionRequestStore.kt` new durable record saved BEFORE remote delete; request-aware protocol in `SharedTagSyncRemoteDataSource`; convergence/replay/fail-closed paths in `DefaultTagRepository.deleteAccountLocked/convergePendingAccountDeletion/finishRemoteAccountDeletion/retryLocalAccountCleanup`) and iOS (record store + protocol methods in `SharedTagCloud.swift`, same ordering and convergence semantics, wired in `AppServices`). Unsupported-server fallback preserves prior behavior for request-unaware remotes.
- Web copy sync: `web/invite-link/privacy/index.html` account-deletion paragraph replaced (stale Android "app-data deletion required" claim removed) and `account-deletion/index.html` gained 端末内のデータと中断時の扱い section; `scripts/test_public_web_contract.py` now pins the new accurate wording (strengthened, not relaxed). Release manifest/docs migration-head bumped to the new head.
- Evidence this session: local Supabase fresh replay + SQL/RLS lint + pgTAP 19 files / 113 tests PASS including new `account_deletion_idempotency_validation.sql`; Android JDK21 `testDebugUnitTest lintDebug assembleDebug` BUILD SUCCESSFUL with 450/450 unit tests PASS (was 443; +7 new idempotency tests) and APK produced; iOS Simulator full suite 228 total / 225 PASS / 0 fail / 3 skip (live-cloud only), including 5 new idempotency tests; focused AiTransparencyTests 30/30 PASS; web contract/browser/release/admin/deno/python static suites all PASS after head bump.
- Independent rebuttal (Nemotron): verdict CONDITIONAL PASS drove two changes — (1) legacy zero-arg path now converges pending requests (pgTAP covers OT→transfer→zero-arg flow), (2) Android comment corrected. Nemotron's claimed iOS corrupt-marker brick was assessed FALSE_POSITIVE against existing passing test `testAccountDeletionCleanupMarkerCorruptBlobRemainsFailClosedAcrossRecreation` (S1 §16.4 fail-closed regeneration design). Rate-limit absence on status RPC: ACCEPTED_RISK given 256-bit token + UUIDv4 id entropy; platform-level throttling remains available.
- Reclassified this session: S2-ACCDEL-008/S1-REMOTE-MARKER-006 → FIXED_AND_VERIFIED (local scope). Still open and external: production Supabase apply + live status-query validation, public web deploy of updated pages, Store/Data Safety Console corrections, physical-device gates, signed candidate build. Full-release judgment remains NO_GO_EXTERNAL until those owner-side external gates close; within declared local scope this session is RELEASE_READY_WITHIN_DECLARED_LOCAL_SCOPE.

### 17.1 Second consecutive independent fresh audit (Nemotron, same day)

- Fresh-auditor #2 hunted independently over `session-commits.diff` (196 files) plus full copies of server SQL/tests, Android repository/fence/store, iOS mirror, and public pages. Reported candidate issues B1/B3 (fence data race), B2/C1 (partial-progress loss on convergence retry), B5/E2 (orphaned pending rows on corrupt blob), E1 (dead code), E4 (test gap).
- Builder rebuttal against actual source, recorded here as the closing classification:
  - B1/B3 `FALSE_POSITIVE`: every access to `deletedAuthUserIds` and the fence-guarded store read occurs inside `operationMutex.withLock` (`withAccountOperation`/`withExclusiveOperation`); mutex confinement provides happens-before. All production call sites verified by grep.
  - B2/C1 `FALSE_POSITIVE`: `convergePendingAccountDeletion`/`finishRemoteAccountDeletion` are reachable only when no cleanup marker is pending (entry guard at DefaultTagRepository deleteAccountLocked / SharedTagCloud deleteAccountWithinAccountOperation). Partial progress always re-enters via the pending-marker branch, which preserves stage state.
  - B5/E2 `FALSE_POSITIVE` after this session's legacy-convergence fix: a corrupt client record with a live session takes the zero-arg path, whose server-side else-branch completes that caller's own pending rows.
  - E1 `CONFIRMED MINOR`, fixed: unused `releaseAfterRemoteDeleteFailure` removed (commit `28d1eff7`); AccountOperationFenceTest + TagRepositoryTest 38/38 PASS after removal.
  - E4 `ACCEPTED_RISK` (Minor): different-user-session convergence covered by logic review + status-query tests, not by a dedicated JVM case.
- Result: two consecutive independent audits; second audit produced ZERO confirmed new Critical/Major issues after rebuttal. Repository scope remains REPO_GO (launch readiness internal PASS at commits 53af82b4..28d1eff7).
