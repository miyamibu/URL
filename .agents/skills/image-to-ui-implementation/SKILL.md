---
name: image-to-ui-implementation
description: Use this skill when converting a generated UI image, screenshot, mockup, or Figma-derived visual direction into URL Saver Android Compose or iOS SwiftUI implementation. It must produce an implementation brief and receive user approval for generated images before editing code. Do not use for backend-only tasks.
---

# Image To UI Implementation

## When to use
- A `gpt-image-2` UI image, screenshot, docs mockup, or Figma reference should guide URL Saver UI work.
- A visual direction needs to become Compose or SwiftUI code.

## When not to use
- Backend-only Room/Supabase/worker work.
- Unapproved generated images.
- Phase 1c scope expansion.

## Required inputs
- Approved visual reference or instruction to generate options.
- Target platform and screen.
- Existing components/theme tokens to reuse.
- Validation expectations.

## Generated image approval
If UI images are generated or received as options:
1. Present the image options to the user.
2. Summarize each option's layout, tone, strengths, and implementation risks.
3. Ask the user to choose one option or request changes.
4. Do not implement until the user explicitly approves a direction.
5. After approval, produce the implementation brief before editing code.

## Workflow
1. Read `AGENTS.md` and `DESIGN.md`.
2. Inspect current Compose/SwiftUI files, theme tokens, and ViewModel state.
3. Convert the approved visual reference into an implementation brief.
4. Map visual elements to existing native components and platform conventions.
5. Identify states: empty, duplicate, pending delete/undo, metadata pending/failed/partial, offline, sync disabled, auth required, invite failure.
6. Edit only the needed platform files.
7. Validate with available Gradle/Xcode checks and screenshots when possible.

## Implementation brief must include
- Layout structure.
- Component list.
- Color/theme tokens.
- Typography.
- Spacing scale.
- Responsive/platform behavior.
- Loading, empty, error, duplicate, metadata, sync, auth, invite, and undo states.
- Accessibility requirements.
- Implementation risks.
- Validation plan.

## Output format
```md
Implementation brief:

Files to change:

Validation plan:

Post-change evidence:
```

## Validation checklist
- Generated image details are adapted to native platform constraints.
- Existing theme/components are reused unless a deviation is justified.
- URL semantics and Phase 1a/1b scope remain intact.
- Emulator/simulator screenshots are saved for substantial UI work when possible.
- Relevant Gradle/Xcode checks are run when possible.

## Failure handling
- If a visual reference conflicts with platform conventions, scope, or data contracts, stop and explain.
- If a dependency seems useful, explain alternatives and wait for approval.
- If screenshots cannot be captured, state why and provide manual verification steps.
