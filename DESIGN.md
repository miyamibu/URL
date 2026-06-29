# DESIGN.md

## Goal
Keep URL Saver fast, calm, and reliable for saving shared links now and rediscovering them later across Android and iOS surfaces.

## Context
- Product: native URL saving app for shared URLs, manual paste, active/archive/detail flows, metadata, shared tags, and AI-friendly export.
- Android stack: Kotlin, Jetpack Compose, Material3, Room, ViewModel, Flow/Coroutines, WorkManager, Supabase for optional shared-tag sync.
- iOS stack exists under `ios/` with SwiftUI, SQLite-backed shared data, share extension, and shared-tag sync parity work.
- Android UI lives under `app/src/main/java/jp/mimac/urlsaver/ui` and `ui/components`; theme tokens live in `ui/theme`.
- Existing mockups and prompts live under `docs/`, including dark reference material. Use them as references, not as automatic implementation targets.

## Source Of Truth
1. Current explicit user instruction.
2. Existing Android Compose/iOS SwiftUI code, domain contracts, Phase 1a/1b core invariants, and approved post-Phase features.
3. This `DESIGN.md`.
4. Approved Figma/design files or approved mockups.
5. Screenshots or `gpt-image-2` generated images.
6. Ambiguous natural-language preferences.

If sources conflict, explain the conflict before changing the UI direction.

## UX Principles
- Saving a URL must feel instant and trustworthy. Silent failure is forbidden.
- List, archive, detail, share extension, duplicate handling, metadata status, and shared-tag states must be understandable without technical knowledge.
- Do not widen scope into WebView, unapproved advanced search/tagging, Hilt/Koin, or Phase 1c features unless explicitly approved.
- Preserve core URL contracts: `normalizedUrl` is the duplicate key and `openUrl = normalizedUrl`.
- Metadata and shared-tag sync should communicate partial success, retry, disabled, or unavailable states clearly.

## Visual Direction
- Android should continue using Compose Material3 and existing Orbit/theme components.
- iOS should follow the existing SwiftUI app structure and platform conventions rather than copying Android visuals exactly.
- Keep lists dense enough for repeated use, but not cramped. Prioritize title, host/service, status, tags, and key actions.
- Use service visuals/icons only from existing code or approved assets/dependencies.
- Avoid decorative hero treatment; this is a utility app.

## Layout
- Main active list: fast scanning, stable swipe actions, clear archive/delete undo affordances.
- Detail: title, memo, URL/open action, metadata status/body, tags, and share/export actions should have predictable hierarchy.
- Shared-tag flows: distinguish local tags, cloud-shared tags, invite links, auth-required states, sync-in-progress, and sync failures.
- Export flows: clearly separate AI-friendly export from backup/restore semantics.
- iOS share extension must be concise and resilient to constrained extension UI.

## Components
- Android: reuse `ui/components`, `ui/theme/OrbitTokens.kt`, existing ViewModel state, and Material3 controls.
- iOS: reuse `ios/URLSaveriOS/UI/*` and `ios/URLSaverShared/*` patterns.
- Loading, empty, duplicate active/archived, pending metadata, failed metadata, offline, cloud disabled, auth required, invite invalid, and undo states must be explicit when touched.

## Accessibility
- Maintain readable text at Android/iOS dynamic type sizes where possible.
- Do not communicate metadata or sync status by color alone.
- Swipe actions need visible alternative paths when possible.
- Touch targets should follow native platform expectations.
- External open/share actions must be clearly labeled.

## Validation
For UI changes, run the most relevant checks available:
- Android build: `./gradlew assembleDebug`.
- Android unit tests: `./gradlew testDebugUnitTest`.
- Android lint: `./gradlew lintDebug`.
- Android instrumentation when relevant: `./gradlew connectedDebugAndroidTest`.
- iOS build/test when touching `ios/`: use the existing `URLSaveriOS` scheme and project/workspace commands documented in `ios/README.md`.
- For substantial UI work, save emulator/simulator screenshots and command outputs under `artifacts/ui-review/YYYY-MM-DD/` when possible.

## Avoid
- New scope such as search/WebView/advanced tags without approval.
- Changing duplicate/open URL semantics.
- Silent truncation, silent metadata failure, or UI that implies sync success without evidence.
- New dependencies without approval.
- Treating generated images as implementation specs.
