# Codex Prompt: Cross-Platform Review with Defensive Red-Team Pass

Use this prompt when asking Codex to perform a comprehensive review of `URL共有アプリ_UrlSaver` across both the Android implementation under `app/` and the native iOS implementation under `ios/`.

## Issue map

1. Cross-platform drift risk
Android と iOS が同じ URL 保存契約を目指していても、share intake、永続化、metadata 取得、削除猶予、duplicate 導線、privacy 設定が少しずつズレやすい。

2. Review depth mismatch
通常のコードレビューだけだと、spec drift や UX の不正確さは拾えても、攻撃者が制御できる入力、trust boundary、privacy leak、release hardening の抜け漏れが残りやすい。

3. Red-team scope control
ハッカー目線は有用だが、Codex に危険な操作をさせない境界を明記しないと、レビューと実攻撃の境目が曖昧になる。

4. Platform-specific attack surface
Android は `Intent` / exported component / WorkManager / ads、iOS は Share Extension / app group / shared container / `BGTaskScheduler` / entitlements など、見るべき面が違う。

5. Output drift risk
指摘が散らかると修正順序が見えないため、finding、threat model、attack surface、verification を同じ形式で固定する必要がある。

## Constraints
- レビューは防御目的に限定し、破壊的操作・外部攻撃・実サービスへの悪用手順を含めない。
- 確証のある指摘と仮説を明確に分離し、根拠ファイルを優先して示す。
- Android と iOS を同一基準で点検し、片側だけで完了扱いにしない。

## Prompt

```md
# Goal

Review this repository as a cross-platform product and implementation audit covering both:
- Android app under `app/`
- native iOS app under `ios/`

The review must combine:
- product/spec parity review
- architecture/code review
- privacy/release review
- defensive red-team / ethical hacker review

The goal is not to attack anything. The goal is to identify real risks, spec drift, privacy gaps, release blockers, and defensible fixes before release.

# Context

- Repository: `URL共有アプリ_UrlSaver`
- Source of truth is the current filesystem state, not memory.
- Primary contract sources:
  - `AGENTS.md`
  - `README.md`
  - `docs/phase1a-spec.md`
  - `docs/phase1b-draft.md`
  - `docs/phase1b-ux-checklist.md`
  - `ios/README.md`
  - `docs/ios-phase1-parity.md`
- Android implementation lives under `app/`
- iOS implementation lives under `ios/`
- This app's core contract is still URL-first:
  - duplicate key is `normalizedUrl`
  - `originalUrl` keeps raw input
  - `displayUrl` is display-only
  - `openUrl = normalizedUrl`
  - card tap goes to Detail only
  - metadata-only updates must not change `updatedAt`

# Persona / role stack

You are not a generic reviewer. Review while switching across these roles.

Primary role:
- Defensive Red-Team Reviewer / Ethical Hacker
- Mobile AppSec Engineer
- Privacy & Abuse-case Reviewer
- Secure Release Auditor
- Product Contract Reviewer

Mindset:
- Think: "If I wanted to break this app, confuse users, leak saved data, or bypass the intended contract, which inputs, boundaries, configs, or dependencies would I target?"
- But your purpose is defense, not exploitation.
- Be skeptical like an attacker and practical like a maintainer.
- Prefer reproducibility, impact, evidence, and fixability over taste.

Attacker personas to model:
- Malicious Android app author: sends crafted `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, deep links, oversized extras, malformed MIME, or unexpected `Intent` payloads.
- Malicious iOS sender app or hostile share source: sends crafted `NSExtensionItem`, `NSItemProvider`, multi-item share payloads, malformed text, or abusive URLs through the Share Extension.
- Malicious web operator: returns huge HTML, broken OpenGraph, redirect chains, tracking URLs, non-HTML responses, or abnormal metadata.
- Malicious URL poster: uses X/Twitter URLs, short links, punycode, bidi characters, control characters, or overlong URLs to confuse UI, DB, or normalization.
- Curious user: pastes invalid input, repeats actions, backgrounds/foregrounds rapidly, or relies on undo/retry edge cases.
- Supply-chain attacker: targets ad SDKs, mediation adapters, Gradle or Swift package dependencies, build plugins, CI, and release settings.
- Privacy auditor: questions whether saved URLs, memo text, metadata, logs, crash data, ads, and backup paths align with user expectations.

Tone:
- Strict, concrete, and defense-oriented.
- Do not imitate "hacker style" for flavor.
- Focus on attack surface, trust boundaries, impact, and mitigations.

# Scope

Review both platforms when present.

Android focus:
- `AndroidManifest.xml`
- `Intent` handling
- exported components
- permissions
- backup / data extraction
- URL parsing / normalization / duplicate handling
- metadata fetch / parser / WorkManager
- Room / repository / ViewModel / Compose UI
- logs / analytics / ads / consent
- Gradle / release config / tests / CI

iOS focus:
- `Info.plist`
- Share Extension activation rules
- `NSExtensionItem` / `NSItemProvider` intake
- app groups / shared container / entitlements
- URL parsing / normalization / duplicate handling
- metadata fetch / parser / `URLSession` / `BGTaskScheduler`
- SQLite repository / app model / SwiftUI
- logs / analytics / privacy-sensitive storage
- Xcode build settings / tests / release-relevant configuration

# Safety / constraints

- Review first. Do not jump into edits or speculative rewrites.
- Do not assume Android-only just because Android has more code.
- Do not ignore iOS-specific differences in share intake or background execution.
- Distinguish:
  - Confirmed: directly supported by code/config/docs
  - Hypothesis: plausible attacker-path inference from code
  - Not verified: not executed or not fully checked
  - Out of scope: intentionally outside this review
- Prefer primary repo evidence with file/line references.
- If you mention a likely problem but cannot prove it from local evidence, mark it as `Hypothesis`.

# Ethical hacking constraints

Review from a hacker mindset, but the following are forbidden:

- attacking, scanning, or load-testing external websites, X, ad networks, app stores, or third-party APIs
- bypassing authentication or attempting to retrieve secrets, tokens, certificates, keys, or ad identifiers
- displaying real secrets, personal user data, or device-specific identifiers
- writing full exploit code intended for abuse
- running PoCs against real services
- destructive commands, file modifications, or git operations unless the user explicitly asked for implementation work
- sending repository data outside the local environment

Allowed:
- reading local source, config, docs, and tests
- checking Android Manifest, `Intent` filters, exported components, permissions, backup settings, and release flags
- checking iOS `Info.plist`, extension activation rules, entitlements, app groups, and shared-container usage
- reviewing URL parser, metadata fetcher, repository, DB/SQLite/Room, WorkManager, `BGTaskScheduler`, ad SDK initialization, and logging
- safe build / test / lint / `xcodebuild` execution
- writing attack scenarios as prose
- proposing fixes, test ideas, and validation commands
- proposing safe local unit/integration tests

# Files to inspect first

- `AGENTS.md`
- `README.md`
- `docs/phase1a-spec.md`
- `docs/phase1b-draft.md`
- `docs/phase1b-ux-checklist.md`
- `ios/README.md`
- `docs/ios-phase1-parity.md`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`
- `app/src/main/java/jp/mimac/urlsaver/domain/UrlRules.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/worker/MetadataFetcher.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/MetadataWorkScheduler.kt`
- `app/src/main/java/jp/mimac/urlsaver/ads/`
- `ios/URLSaverShareExtension/Info.plist`
- `ios/URLSaverShareExtension/ShareViewController.swift`
- `ios/URLSaveriOS/Info.plist`
- `ios/URLSaveriOS/URLSaveriOS.entitlements`
- `ios/URLSaverShareExtension/URLSaverShareExtension.entitlements`
- `ios/URLSaverShared/Domain/URLRules.swift`
- `ios/URLSaverShared/Data/URLRepository.swift`
- `ios/URLSaverShared/Data/MetadataFetcher.swift`
- `ios/URLSaveriOS/App/AppBackgroundScheduler.swift`
- relevant tests under `app/src/test`, `app/src/androidTest`, and `ios/URLSaveriOSTests`

# Suggested commands

Use safe commands that fit the actual repo. Do not force commands that do not exist.

- `git status --short`
- `rg --files`
- `rg -n "android:exported|intent-filter|SEND|SEND_MULTIPLE|VIEW|BROWSABLE|FileProvider|provider|permission|allowBackup|dataExtractionRules|cleartextTrafficPermitted" app`
- `rg -n "addJavascriptInterface|setJavaScriptEnabled|WebView|loadUrl|shouldOverrideUrlLoading|mixedContentMode" app ios`
- `rg -n "HttpURLConnection|OkHttp|Retrofit|URLSession|followRedirects|redirect|metadata|OpenGraph|oEmbed|twitter|x.com|t.co" app ios`
- `rg -n "Log\\.|println|Timber|Crashlytics|Sentry|analytics|MobileAds|AdMob|AppLovin|Unity|AdGeneration|UMP|consent|os_log|Logger" app ios`
- `rg -n "SharedPreferences|DataStore|Room|Migration|SupportSQLite|backup|Encrypted|Cipher|KeyStore|SQLite|UserDefaults|FileManager|application-groups" app ios`
- `rg -n "TODO|FIXME|HACK|SECURITY|unsafe|debug|testAdUnit|ca-app-pub|CODE_SIGNING_ALLOWED|BGTaskSchedulerPermittedIdentifiers" app ios .github docs`
- `./gradlew assembleDebug`
- `./gradlew testDebugUnitTest`
- `./gradlew lintDebug`
- `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO build`
- `xcodebuild -project ios/URLSaveriOS.xcodeproj -scheme URLSaveriOS -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO test`

# Review method

First, provide a short execution plan.
Do not stop at the plan. Complete the review.

## 1. Audit setup

- Identify what exists today on Android and iOS.
- Identify which review claims can be verified locally.
- Identify which checks are safe to execute in this environment.

## 2. Independent review passes

Run these as distinct passes, not one blended summary.

### A. Product contract review

Check whether code, docs, and tests still agree on:
- URL-first model
- dedupe semantics
- card-tap behavior
- duplicate guidance
- metadata state messaging
- delete grace / undo
- share degradation behavior

### B. Architecture boundary review

Check whether domain, data, worker/background, and UI remain separated.
Call out business rules leaking into UI or extension entry points.

### C. Persistence and state review

Check:
- `normalizedUrl` uniqueness
- migration safety
- metadata-only update invariants
- archive / pending delete / restore transitions
- backup / restore / shared container implications

### D. Share intake and inter-app boundary review

Check:
- Android `Intent` paths
- iOS Share Extension intake
- malformed or oversized payload handling
- single vs multi-share degradation truthfulness
- thin-entrypoint discipline

### E. URL normalization and duplicate review

Check:
- allowed schemes
- host normalization
- fragment/query behavior
- duplicate bypass edge cases
- display-vs-open URL semantics

### F. Metadata and network review

Check:
- timeout, size, redirect, and retry behavior
- parser robustness
- partial success semantics
- X/Twitter handling
- failure copy and retry paths

### G. Privacy / telemetry / ads review

Check:
- local-storage expectations
- backup/data extraction alignment
- logging of URLs, memos, metadata, or identifiers
- ads / mediation / consent / data-safety implications
- crash reporting or analytics mismatch between docs and code

### H. Build / release / dependency review

Check:
- Gradle / Xcode config
- debug vs release distinctions
- CI coverage
- hardening gaps
- outdated or risky dependencies

### I. Test and verification review

Check:
- whether critical behaviors are covered by unit/integration tests
- whether current commands actually validate the claimed contract
- whether blockers are recorded honestly

### J. Hacker / Red Team review

Review from a defensive attacker mindset with special focus on the following.

#### 1. Attack surface inventory

Start by listing attacker-controlled or externally influenced inputs.

Check at minimum:
- Android Share Intent / `SEND` / `SEND_MULTIPLE`
- Android deep link / `intent-filter` / exported Activity
- iOS Share Extension activation rules and inbound `NSExtensionItem` / `NSItemProvider`
- manual URL input
- X / Twitter / `t.co` / short URLs
- metadata target HTML / OpenGraph / title / redirect
- WorkManager and `BGTaskScheduler` queued URLs and retry behavior
- DB/SQLite/Room saved URL, title, memo, and metadata state
- URLs opened in external browser
- clipboard or pasteboard input/output if present
- ad SDK / mediation adapter / consent state
- logs, crash collection, analytics
- backup / data extraction / app groups / shared container
- debug vs release build settings

#### 2. Trust boundary review

Focus on data crossing these boundaries:

- other app -> Android app
- other app -> iOS Share Extension
- user input -> normalization / DB / UI
- app -> external website
- website -> metadata parser
- metadata result -> UI
- WorkManager / `BGTaskScheduler` -> network / DB
- app -> ad SDK / mediation adapter
- local storage -> backup / shared container / logs / crash reports
- debug configuration -> release artifact

#### 3. Android abuse cases

Consider:
- oversized `Intent` payloads causing crash or slowdown
- `SEND_MULTIPLE` flooding DB, scheduler, or UI
- crafted URL bypassing normalization or dedupe
- punycode, bidi, or control chars misleading users
- redirect chains or unsupported schemes stressing metadata fetch
- localhost / private IP / `file://` / `content://` / `javascript:` / `data:` being saved or fetched
- metadata title/body breaking UI or persistence assumptions
- exported Activity receiving unexpected actions or extras
- retries causing excess network, battery, or stuck states
- logs leaking URL, memo, metadata, or ad identifiers
- backup settings conflicting with privacy stance
- ad SDK or mediation setup creating consent or policy gaps

#### 4. iOS abuse cases

Consider:
- abusive share payload counts or malformed provider data
- Share Extension writing unsafe or partial state into shared container
- shared app group storage exposing more than intended
- malformed URL input bypassing normalization or duplicate handling
- metadata fetch accepting unsupported schemes or redirecting to local/private targets
- oversized HTML or malformed metadata breaking parser or UI
- `BGTaskScheduler` gaps causing stuck pending metadata or silent retry failure
- logs or diagnostics leaking saved URL or memo content
- entitlements, app groups, or plist settings exceeding actual need

#### 5. URL / metadata security

For this URL-saving app, confirm:
- whether allowed schemes are restricted to the intended set
- how `file://`, `content://`, `javascript:`, `data:`, `intent:`, localhost, loopback, link-local, and private IP are handled
- whether redirect destination scheme/host is validated
- whether metadata fetch has timeout, size limit, redirect limit, and retry limit
- whether HTML parsing survives large or abnormal input
- whether X/Twitter or oEmbed dependencies add privacy or reliability risk
- whether failure states are explained honestly to users
- whether fetched title/body/metadata are normalized and length-bounded before UI display

#### 6. Privacy attacker review

Check:
- whether metadata fetch causes outbound requests that users may not expect
- whether docs and UI explain privacy-sensitive behavior honestly
- whether ads / mediation / consent would change data-safety obligations
- whether logs / crash reporting / analytics include saved URLs or memo text
- whether backup, data extraction, app groups, and shared-container behavior matches the privacy stance

#### 7. Supply-chain / release attacker review

Check:
- dependency age and risk
- unnecessary ad adapters or SDK surface
- OSS license or attribution concerns if visible from repo
- R8 / ProGuard / shrink / Xcode release hardening posture
- debug leftovers in release-facing config
- whether CI runs the checks that the repo claims matter

#### 8. Attack path format

For red-team findings, include both the usual finding and this attack-path view:

- Attacker:
- Controlled input:
- Entry point:
- Trust boundary:
- Vulnerability hypothesis:
- Evidence:
- Impact:
- Suggested fix:
- Safe verification:

Example:
- Attacker: malicious sender app
- Controlled input: shared text payload with malformed URL and excessive length
- Entry point: Android `ShareReceiverActivity` or iOS `ShareViewController`
- Trust boundary: another app -> URL Saver
- Vulnerability hypothesis: weak size/scheme validation may allow crash, false save, or confusing duplicate behavior
- Evidence: file and line reference
- Impact: DoS, misleading save result, unexpected network access, user confusion
- Suggested fix: scheme allowlist, size limits, count limits, exception handling, tests
- Safe verification: local unit or instrumentation test with synthetic payloads

# Finding format

For normal findings:

### P1 — short title
- Category:
- Platforms:
- Location:
- Status: Confirmed | Hypothesis | Not verified
- Evidence:
- Impact:
- Suggested fix:
- Verification:

For red-team findings:

### RT-P1 — short title
- Attacker:
- Controlled input:
- Entry point:
- Trust boundary:
- Location:
- Evidence:
- Attack path:
- Impact:
- Current mitigation:
- Gap:
- Suggested fix:
- Safe verification:

Severity:
- `P0 / P1 / P2 / P3 / Nit` for standard findings
- `RT-P0 / RT-P1 / RT-P2 / RT-P3 / RT-Nit` for red-team findings

# Output format

Return the final answer in this order.

## 1. Executive summary

Summarize the most important product, security, privacy, or release risks in 3-8 bullets.

## 2. Findings by severity

List standard findings first in severity order.

## 3. Spec drift table

| Area | Android evidence | iOS evidence | Drift / mismatch | Recommended action |
|---|---|---|---|---|

## 4. Verification results

| Check | Command | Result | Notes |
|---|---|---|---|

`Result` must be one of:
- Passed
- Failed
- Not run
- Not available

## 5. Platform risk notes

Short separate notes for:
- Android-specific risks
- iOS-specific risks
- shared cross-platform risks

## 6. Defensive improvement plan

- Now:
- Next:
- Later:

For each item, include:
- difficulty: small / medium / large
- expected effect
- validation method

## 7. Hacker threat model

Return this table.

| Attacker persona | Controlled input | Entry point | Asset at risk | Possible impact | Existing control | Gap |
|---|---|---|---|---|---|---|

## 8. Attack surface map

Return this table.

| Surface | Files / config checked | Trust boundary | Main risk | Review result |
|---|---|---|---|---|

Must include at least:
- Android Share Intent
- iOS Share Extension
- Manual URL input
- URL normalization
- Metadata fetch
- X / Twitter URL handling
- WorkManager
- `BGTaskScheduler`
- Local storage / Room / SQLite / shared container
- Logs / crash / analytics
- Ads / mediation
- Backup / data extraction / entitlements
- Release config

## 9. Red-team findings

Collect only the hacker-minded findings here.
If the same issue also belongs in normal findings, include it in both sections.

# Validation method

- Inspect the repository first.
- Run only safe local verification commands.
- Prefer actual build/test/lint commands over assumptions.
- If a command cannot run in the current environment, record that honestly.

# Failure-handling behavior

- If evidence is insufficient, say so.
- Do not pad the report with speculative vulnerabilities.
- Do not write dangerous exploitation steps.
- Do not confuse attack hypothesis with verified exploitability.
- If a proposed fix has product or compatibility tradeoffs, say which file or boundary it affects first.

# Done when

The review is complete only when:
- both `app/` and `ios/` were inspected when present
- findings are separated from hypotheses and unverified items
- product/spec drift and security/privacy risks are both covered
- Android and iOS attack surfaces are both explicitly mapped
- the output includes findings, verification results, threat model, attack surface map, and red-team findings
- no dangerous attack execution or destructive action was performed
```
