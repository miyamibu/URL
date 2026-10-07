# Mobile design and performance snapshot — 2026-10-08

Status: reviewed work in progress, not release-ready or a fully unified design.

## Implemented source

- Preserve the existing URL identity, duplicate/open rules, persistence and schema.
- Preserve manual-add input and selected tags while saving or retrying; guard dismissal and repeated saves.
- Debounce Android database search by 250 ms, cancel stale queries, and retain immediate local filtering.
- Build the iOS list's local-tag lookup once per render instead of once per card.
- Add local home-background preferences with four stable choices: existing warm beige, sakura, lavender and mint. Background selection applies to the home screen and is independent from light/dark/system mode.
- Refine detail action hierarchy, memo editors, tag density, share confirmations and group presentation using existing components and callbacks.
- Preserve the raw full iOS fetched body when removing a redundant prefix summary from its presentation.
- Refresh Android background/theme values inside navigation destinations and use readable Android system-bar icons for the active surface.

## Validation already performed

- Android unit tests: 510 tests, zero failures/errors/skips; debug build and lint passed.
- iOS related tests: 95 tests passed. The final body-projection change reran 29 overlapping focused tests successfully; these counts are not additive.
- A subsequent signed Debug Simulator build passed to enable App Group access for actual share-extension verification.
- Mobile UI contract and diff whitespace checks passed.
- Before committing, Android instrumentation-test Kotlin compilation passed without running connected tests or touching a physical device.

The full unit suites were not rerun merely to create this Git snapshot; implementation sources are unchanged since the recorded validation.

## Visual review

Canonical Android and iOS apps were operated on an owned read-only Android emulator and an owned iOS simulator. The audit opened locally reachable screens, sheets and dialogs, including onboarding, home/list/filter/search, manual input, selection, tags, detail/editors, archive, profile, support, export, all four AI-provider pages, usage guidance and a synthetic saved-media viewer.

The canonical iOS share extension was opened from a small local QA host. A synthetic URL was saved with a local tag, and the saved record was verified. Normal JSON export reached the OS document picker after simulator signing/App Group access was restored. Earlier unsigned-build failures are environment evidence, not proof of production failures.

Raw local evidence includes 48 Android screenshots and 99 iOS screenshots, including alternate states and retries; these are not counts of distinct pages. Related public web entry pages were also inspected. No physical-device, purchase, external-send or authenticated multi-device result is claimed.

## Known unresolved findings

1. iOS dark detail secondary labels (Copy/Archive) use a dark fixed foreground and are difficult to read on a dark surface. This was observed again on the signed simulator build.
2. The iOS AI ZIP share sheet opened but remained blank on the simulator. The generated two-entry ZIP existed, had seven members and passed CRC validation. Simulator-specific behavior versus an implementation issue remains unresolved.
3. With iOS dark mode selected, home remains light/beige while status icons are white and have weak contrast.
4. Blue/navy styling remains in title/tag editors, profile, manual input, selection, export, AI and support. The new plum/pearl treatment is not unified across every page.
5. Android's globe/service icon on the plum detail header and the dark-home shared-tag heading have weak visual contrast.
6. Authenticated shared-tag/group detail, membership, invitation and role-management pages were not reachable in the signed-out fixture. Android local-tag share confirmation was not reached through the current navigation. These remain unverified.

These findings are retained in the source snapshot; this commit operation does not fix them or declare the app ready for release.

## Evidence retention and release boundary

- Local implementation notes: `artifacts/ui-review/2026-10-07/detail-design-01a11413/`.
- Local all-page audit and per-platform inventories: `artifacts/ui-review/2026-10-08/all-pages-01a11413/`.
- Raw evidence, build outputs, device/session identifiers and unknown-origin photo thumbnails remain ignored and locally retained; they are not included in the public Git repository.
- Owned test runtimes were stopped/removed after capture; the existing protected simulator, underlying Android AVD and normal user data were preserved.
- The existing `1.0.24 (39)` metadata and October 5 submission record predate these later UI/performance changes. Their receipts are historical evidence for their exact submitted artifacts, not receipts for the current working source.
- No new build/version increment, Store submission, deployment or release configuration change is performed by this Git snapshot.
