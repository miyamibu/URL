# 2026-07-08 UI restore organization ledger

## Purpose

This ledger separates the current uncommitted work by user request, validation status, and evidence handling.
It is intentionally separate from app code so the mixed Android/iOS UI changes can be reviewed without relying on file-level grouping.

## Current branch

- Branch: `codex/rinbam-consolidation-20260705`
- Latest checked commit before this work: `c1d3fb88 Exclude X from media resolver targets`

## Code change buckets

| Bucket | User-facing purpose | Files involved | Suggested handling |
| --- | --- | --- | --- |
| Home profile restoration | Restore the profile button and guard against it disappearing again | `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`, `ios/URLSaveriOS/UI/RootView.swift`, `docs/mobile-ui-regression-contract.md`, `scripts/verify_mobile_ui_contract.py` | Keep |
| Bottom bar position and plus styling | Move the bottom bar down and make the Android plus sign larger/thicker | `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`, `ios/URLSaveriOS/UI/RootView.swift` | Keep |
| Detail tag edit buttons | Put edit buttons to the right of the local/shared tag headings and keep headings one line | `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`, `ios/URLSaveriOS/UI/DetailView.swift`, `docs/mobile-ui-regression-contract.md`, `scripts/verify_mobile_ui_contract.py` | Keep |
| Card timestamp/status cleanup | Hide saved/archive timestamp and the right-side metadata dot on cards without local tags | `app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt`, `ios/URLSaveriOS/UI/AppChrome.swift`, `docs/mobile-ui-regression-contract.md`, `scripts/verify_mobile_ui_contract.py` | Keep |
| iOS share keyboard behavior | Prevent the share-extension tag field from becoming first responder at initial display | `ios/URLSaverShareExtension/ShareViewController.swift` | Keep |
| iOS media audio | Configure playback audio session and explicit player volume for saved videos | `ios/URLSaveriOS/UI/DetailView.swift` | Keep |
| Media viewer title/internal labels | Keep media title one line with horizontal scroll and hide internal file/type strings below media | `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`, `ios/URLSaveriOS/UI/DetailView.swift`, `docs/mobile-ui-regression-contract.md`, `scripts/verify_mobile_ui_contract.py` | Keep |
| Android media viewer layout/dismiss | Match Android media sheet position/size closer to iPhone, improve light-mode close button, add downward drag dismiss | `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt` | Keep |
| Android media dots | Add/fix Android dot indicator, then keep the dot row fixed while the pager content moves | `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt` | Keep |
| Regression contract | Encode the restored UI expectations so they do not silently regress again | `docs/mobile-ui-regression-contract.md`, `scripts/verify_mobile_ui_contract.py` | Keep with code changes |

## Evidence buckets

The generated evidence under `artifacts/ui-review/2026-07-08/` is local proof, not app source. It should remain on disk but stay out of normal Git staging unless the user explicitly asks to commit evidence.

| Evidence folder | File count | Meaning |
| --- | ---: | --- |
| `artifacts/ui-review/2026-07-08/media-viewer-device-check/` | 20 | Earlier Android/iPhone media viewer layout and dismiss checks |
| `artifacts/ui-review/2026-07-08/media-fixed-width-variable-height/` | 14 | Android fixed-width / variable-height media layout iterations |
| `artifacts/ui-review/2026-07-08/media-fixed-width-variable-height-v2/` | 6 | Follow-up media size/position checks |
| `artifacts/ui-review/2026-07-08/media-offset-64/` | 2 | Android media vertical offset check |
| `artifacts/ui-review/2026-07-08/media-dots-functional/` | 8 | First Android dot indicator functional check |
| `artifacts/ui-review/2026-07-08/media-dots-fixed-position/` | 8 | Android fixed-size dot position check |
| `artifacts/ui-review/2026-07-08/media-dots-fixed-overlay/` | 11 | Final Android fixed-overlay dot proof plus iPhone Appium launch proof |

Representative final evidence:

- `artifacts/ui-review/2026-07-08/media-dots-fixed-overlay/03-android-media-before.png`
- `artifacts/ui-review/2026-07-08/media-dots-fixed-overlay/04-android-media-mid-swipe.png`
- `artifacts/ui-review/2026-07-08/media-dots-fixed-overlay/05-android-media-after.png`
- `artifacts/ui-review/2026-07-08/media-dots-fixed-overlay/06-iphone-appium-launch.png`

## Validation already run

- `python3 scripts/verify_mobile_ui_contract.py`: passed
- `./gradlew assembleDebug`: passed
- `./gradlew testDebugUnitTest lintDebug`: passed
- Android Pixel 9a overwrite install: passed, package `jp.miyamibu.urlalbum`, version `1.0.13`
- Android media dots real-device check: passed, dot row stayed fixed during swipe and active dot changed after swipe
- iPhone 12 overwrite install: passed, bundle `com.mibu.codebridge.ios`, version `1.0.11 (11)`
- iPhone Appium/WDA launch screenshot: passed for home screen launch proof

## Recommended staging groups

No staging has been done yet. If staging is approved, use patch-selective staging and verify each staged group with `git diff --cached --name-only` and `git diff --cached --check`.

1. UI restore implementation:
   - `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
   - `app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt`
   - `ios/URLSaverShareExtension/ShareViewController.swift`
   - `ios/URLSaveriOS/UI/AppChrome.swift`
   - `ios/URLSaveriOS/UI/DetailView.swift`
   - `ios/URLSaveriOS/UI/RootView.swift`

2. Regression guard:
   - `docs/mobile-ui-regression-contract.md`
   - `scripts/verify_mobile_ui_contract.py`

3. Organization metadata:
   - `.gitignore`
   - `docs/qa/rinbam-ui-restore-2026-07-08-organization.md`

Generated screenshots and XML files should not be staged by default.

## Open decisions

- Whether to keep this as one local commit or split it into multiple commits by bucket.
- Whether any evidence images/XML should be intentionally committed, despite the default local-only recommendation.
- Whether to re-run both physical-device installs after final staging, or treat the already completed real-device proof as sufficient for this cleanup pass.
