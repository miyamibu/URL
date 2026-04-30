---
name: frontend-visual-review
description: Use this skill after URL Saver Android Compose or iOS SwiftUI UI changes to review visual quality, platform behavior, accessibility, state clarity, existing design consistency, and validation evidence. Do not use for backend-only changes.
---

# Frontend Visual Review

## When to use
- After changes to Android Compose UI, iOS SwiftUI UI, share extension, list/detail/archive/export, metadata, or shared-tag screens.
- Before reporting completion for substantial UI/UX work.
- When reviewing screenshots or visual implementation quality.

## When not to use
- Backend-only, SQL-only, docs-only, worker-only, or repository-only changes with no visible UI.

## Required inputs
- Files changed.
- Target platform/screens/states.
- Validation commands available.
- Screenshots or emulator/simulator target when available.

## Workflow
1. Read `DESIGN.md`.
2. Inspect changed UI against existing native components and theme tokens.
3. Check relevant device sizes/orientations where possible.
4. Check state coverage: empty, duplicate, pending delete/undo, metadata pending/failed/partial, offline, cloud disabled, auth required, invite failure.
5. Check accessibility: text scaling, labels, touch targets, contrast, non-color status communication.
6. Run relevant checks or record why they were not run.
7. Save evidence under `artifacts/ui-review/YYYY-MM-DD/` for substantial UI work when possible.

## Output format
```md
Review summary:

Issues found:

Evidence:

Commands run:

Residual risk:
```

## Validation checklist
- Save/share/detail flows remain clear.
- Silent failure is not introduced.
- Swipe actions have understandable undo or alternative paths when relevant.
- Android and iOS follow platform conventions.
- Checks/screenshots are recorded or explicitly unavailable.

## Failure handling
- If visual verification cannot run, state the blocker and provide manual verification steps.
- If a severe UX, accessibility, or data-contract issue is found, stop and recommend a fix before completion.
