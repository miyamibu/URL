---
name: ui-design-brief
description: Use this skill before URL Saver Android/iOS UI/UX design or redesign work to clarify save/share/list/detail/export/shared-tag flows, Phase 1a/1b constraints, states, and validation before implementation. Do not use for backend-only Supabase SQL or non-UI sync contracts.
---

# UI Design Brief

## When to use
- Before changing Android Compose UI, iOS SwiftUI UI, share extension UX, list/detail/archive/export, metadata, or shared-tag screens.
- Before generating `gpt-image-2` UI concepts.
- When the user asks for UX improvement, visual polish, or a new screen.

## When not to use
- Backend-only SQL, sync contract, Room migration, worker, or repository changes with no visible UI.
- Scope expansion into Phase 1c or out-of-scope features.

## Required inputs
- Target platform: Android, iOS, share extension, or cross-platform.
- User goal and affected flow.
- Existing files/components.
- Phase and scope constraints from `AGENTS.md`, `CODEX_INSTRUCTIONS.md`, README/docs, and `DESIGN.md`.

## Workflow
1. Read `AGENTS.md`, `DESIGN.md`, and relevant docs for the feature phase.
2. Inspect existing Compose/SwiftUI UI and ViewModel state for the target flow.
3. Identify user task, success state, degraded states, and failure visibility.
4. Verify the change does not alter `normalizedUrl`, `openUrl`, or Phase 1a/1b scope.
5. Define source-of-truth priority and conflicts.
6. Produce a brief before implementation or image generation.

## Output format
```md
Goal:

Context:

Target users:

Target platform:

Primary flow:

States to support:

Existing UI patterns to reuse:

Constraints:

Implementation notes:

Validation plan:

Failure handling:
```

## Validation checklist
- Platform and scope are clear.
- Existing components, ViewModels, and domain contracts are named.
- Duplicate, metadata, offline, sync, auth, invite, empty, and undo states are considered when relevant.
- Validation includes Gradle/Xcode checks and screenshots when appropriate.

## Failure handling
- If the request widens scope, changes URL semantics, or risks silent failure, stop and ask.
- If Android and iOS parity conflicts, state the platform-specific tradeoff before implementation.
