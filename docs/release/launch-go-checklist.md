# Launch GO Checklist

## Goal
Drive the repo from `LAUNCH_READY_REPO` through manual `STAGING_GO`, `INTERNAL_TEST_GO`, and final `LAUNCH_GO` without mixing external console actions into repo-local readiness.

## 1. Pre-commit / diff freeze

- [ ] Check:
  - Owner: Release owner
  - Command / Console: `git status --short && git diff --check && bash scripts/check_launch_readiness.sh`
  - Expected result: Only intentional launch/release diffs; no whitespace errors; readiness script passes.
  - Evidence to save: Terminal log in a local release evidence folder outside git or approved artifacts folder.
  - Stop if: Unexpected source changes, generated root ZIP, `.env.production`, invalid docs reference, or actual secret appears.

- [ ] Check:
  - Owner: Release owner
  - Command / Console: Review `docs/release/repo-go-evidence.md`
  - Expected result: REPO_GO proof is current enough for this launch train.
  - Evidence to save: Timestamped release decision note.
  - Stop if: Evidence predates meaningful code/config changes.

## 2. Android internal testing

- [ ] Check:
  - Owner: Android release owner
  - Command / Console: `./gradlew testDebugUnitTest lintDebug assembleDebug`
  - Expected result: Build and validation pass.
  - Evidence to save: Gradle summary and versionCode/versionName.
  - Stop if: Test/lint/build fails or app ID is not `jp.miyamibu.urlalbum`.

- [ ] Check:
  - Owner: Android release owner
  - Command / Console: Play Console internal testing upload, following `docs/release/android-internal-testing-checklist.md`
  - Expected result: Internal testing build available to testers.
  - Evidence to save: Play Console build/version screenshot.
  - Stop if: Data Safety, signing, tester access, or package identity mismatch appears.

## 3. iOS TestFlight

- [ ] Check:
  - Owner: iOS release owner
  - Command / Console: `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -configuration Release CODE_SIGNING_ALLOWED=NO clean build`
  - Expected result: Code compiles without signing.
  - Evidence to save: xcodebuild success log.
  - Stop if: Swift compile, schema, entitlement, or bundle ID mismatch appears.

- [ ] Check:
  - Owner: iOS release owner
  - Command / Console: App Store Connect upload/TestFlight, following `docs/release/ios-testflight-checklist.md`
  - Expected result: Internal TestFlight build processing and installable.
  - Evidence to save: App Store Connect build/version screenshot.
  - Stop if: signing/provisioning mismatch, privacy manifest issue, or Share Extension failure appears.

## 4. MCP staging

- [ ] Check:
  - Owner: Web/MCP owner
  - Command / Console: Follow `docs/release/staging-deploy-checklist.md`
  - Expected result: Staging HTTPS MCP endpoint exists and is disabled until explicitly enabled.
  - Evidence to save: endpoint URL, env var list without values, deploy ID.
  - Stop if: production secrets are pasted into repo, noauth data is exposed, or endpoint is HTTP.

- [ ] Check:
  - Owner: Web/MCP owner
  - Command / Console: `bash scripts/smoke_mcp_staging.sh`
  - Expected result: disabled/noauth/invalid token checks are safe; authenticated staging search/fetch behave as read-only.
  - Evidence to save: sanitized smoke log.
  - Stop if: raw body, token, prompt, shared-tag default inclusion, write side effect, or unbounded external call appears.

## 5. OpenAI Developer Mode

- [ ] Check:
  - Owner: OpenAI app owner
  - Command / Console: Follow `docs/ai/openai-apps-developer-mode-test-plan.md`
  - Expected result: Developer Mode app connects to staging MCP and returns expected read-only outputs.
  - Evidence to save: screenshots of connector settings and test prompt responses.
  - Stop if: Developer Mode unavailable, connector uses production unintentionally, auth fails open, or write tools appear.

## 6. Privacy / policy

- [ ] Check:
  - Owner: Privacy/store owner
  - Command / Console: Follow `docs/release/privacy-policy-and-store-disclosure-checklist.md`
  - Expected result: Privacy Policy, Data Safety, App Privacy, and review notes match actual data flow.
  - Evidence to save: final disclosure text and console screenshots.
  - Stop if: AI/MCP data use is misstated or shared-tag/raw body/token handling is incorrect.

## 7. Production secrets

- [ ] Check:
  - Owner: Infrastructure owner
  - Command / Console: Follow `docs/release/production-secrets-and-flags.md`
  - Expected result: Secrets are entered only in external secret stores; no real values in git/chat/logs.
  - Evidence to save: secret name list only, not values.
  - Stop if: `.env.production` or real secret values appear in repo.

## 8. Feature flags / kill switch

- [ ] Check:
  - Owner: Release owner
  - Command / Console: Verify AI and MCP flags in hosting/mobile build settings.
  - Expected result: AI feature remains off in normal UI; MCP can be disabled immediately.
  - Evidence to save: sanitized settings screenshots or command output.
  - Stop if: normal public UI exposes unapproved AI entry or MCP cannot be disabled quickly.

## 9. Real device QA

- [ ] Check:
  - Owner: QA owner
  - Command / Console: Follow `docs/release/manual-qa-matrix.md`
  - Expected result: Pixel 9a and iPhone/TestFlight flows pass.
  - Evidence to save: screenshots/videos/logs per matrix row.
  - Stop if: crash, data loss, share-sheet failure, export leak, or feature flag violation appears.

## 10. Rollback

- [ ] Check:
  - Owner: Release owner
  - Command / Console: Review `docs/release/rollback-plan.md`
  - Expected result: AI/MCP/store rollback steps are understood before launch.
  - Evidence to save: rollback owner and contact path.
  - Stop if: there is no tested path to disable MCP or remove a bad build from testers.

## 11. Final launch decision

- [ ] Check:
  - Owner: Release owner
  - Command / Console: Compare evidence against `docs/release/launch-status-model.md`
  - Expected result: `STAGING_GO` and `INTERNAL_TEST_GO` are achieved; privacy/store/OpenAI stop conditions clear.
  - Evidence to save: final `LAUNCH_GO` decision note.
  - Stop if: any stop condition remains unresolved or evidence is stale.
