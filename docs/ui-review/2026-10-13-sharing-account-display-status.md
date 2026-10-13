# 2026-10-13 共有・アカウント・表示変更

## Accepted direction
GPT-6 Web xhigh generated design options. The owner selected layout A with the existing palette, then corrected the central control to a direct list/two-column display-method toggle without a trailing down arrow. In the two-column layout, the closed eye hides text while retaining square thumbnails; the open eye adds existing title/tag information below the images. Existing independent AI, tag creation plus controls, bottom five actions and rich usage guide are retained.

## Source changes
- Android/iOS external-share flows expose shared-tag selection and retain pending partial assignments without repeating successful URL saves. Account-owner checks prevent applying a queued assignment to a different account.
- Google/Apple sign-in and identity-link entry points reuse existing authentication paths and preserve the current session on failed/cancelled linking. Email/password remains a disclosure form. ChatGPT sign-in is visibly unavailable until provider eligibility/client registration are resolved.
- Shared-tag invitation acceptance can create an owner/invitee group; members can contribute their owned tags without importing unrelated direct members. The prepared migration is not yet applied to production.
- Home-specific layout/information preferences preserve existing list/archive/detail preferences. Sorting includes key and direction. Missing thumbnails retain a placeholder and accessible title.
- Tag double tap opens name/color editing, preserving role-based rename limits and long-press reorder. Paid offers are hidden for paid plans; annual labels use one-line native presentation.

## Local validation
Android 556 unit tests, Debug assemble/lint and final related 21 tests/assemble passed. iOS 65 auth/share tests and 35 display/domain tests passed, with subsequent UI-only generic builds passed. Isolated Supabase migration reset/lint and the three SQL suites passed. Mobile UI contract and whitespace checks passed.

Root operated canonical `jp.miyamibu.urlalbum` on a task-owned API36 emulator and `com.mibu.codebridge.ios` on an iPhone17/iOS26.5 simulator through Appium uiautomator2/XCUITest. Both layouts/eye states, persistence, detail tap, selection, swipe/archive/delete/Undo, sort direction, text scaling, local tag editing and the rich usage guide were observed. iOS narrow hostname wrapping and overlapping accessibility frames were corrected and rechecked.

Evidence is retained locally at `artifacts/ui-review/2026-10-13/sharing-account-controls/`, including `final-validation-report.md`, approved generated images, screenshots/XML and logs. Disposable devices and Appium were cleaned up. This document does not claim physical-device or production sign-in/sync proof. Free-account annual buttons were not observed on a live account.

## Release boundary
The owner subsequently authorized committing/pushing all accumulated changes and submitting both Stores as version 1.0.26 (41). Signed release artifact validation, upload, processing, version association and review submission are recorded separately in the release records. Production SQL application, provider/security configuration and OpenAI client registration remain separate unresolved external actions.
