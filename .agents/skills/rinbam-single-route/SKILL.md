---
name: rinbam-single-route
description: Use before changing りんばむ Android/iOS mobile UI, tag/card/detail screens, device verification, or any rollback/restoration work that could reintroduce old UI regressions.
---

# Rinbam Single Route

## Goal
Keep Android/iPhone changes on one consistent route so old UI and schema regressions do not return.

## Context
This repo has Android Compose and iOS SwiftUI implementations for the same URL-saving product. Recent regressions came from restoring code without a single UI contract, wiring only one layer of tag behavior, and mixing build/install proof with real UI proof.

## Constraints
- User-facing explanations and reports must be in Japanese.
- Do not delete app data, reset databases, uninstall real-device apps, or use destructive migrations without explicit approval.
- Do not use broad git restore/checkout to "fix" UI drift. Read the current files and patch only the intended hunks.
- Do not treat iPhone `devicectl` install/launch as physical UI proof.
- Keep artifacts and implementation/source changes separate in reports and commit decisions.

## Canonical Sources
Read these before editing affected files:

1. `AGENTS.md`
2. `DESIGN.md` for UI/UX changes
3. `docs/mobile-ui-regression-contract.md`
4. Current Android/iOS source files for the exact surfaces being changed

## Required Baseline Check
Run this before and after touching the affected mobile UI/tag/card/detail route:

```bash
python3 scripts/verify_mobile_ui_contract.py
```

If it fails before editing, report that the baseline is already dirty and fix the contract violation first unless the user explicitly asked for read-only investigation.

## Work Route
1. Classify the change lane: UI display, tag/card wiring, data/schema, device proof, backend/media, or docs only.
2. Read both platform surfaces when the user-visible behavior exists on both Android and iPhone.
3. For card/tag changes, preserve the path from repository/model assignment -> screen map -> card props -> card rendering.
4. For restored older code, check Android Room `version`, available migrations, and any connected real-device `PRAGMA user_version` before installing.
5. Patch the smallest coherent set of files.
6. Run `python3 scripts/verify_mobile_ui_contract.py`.
7. Run the closest build/test/lint checks for the changed platforms.
8. If installing on devices, use canonical IDs only and keep proof categories separate:
   - Android real-device foreground/screenshot proof
   - iPhone install/launch proof
   - iPhone Appium/WDA UI operation proof

## Must Preserve
- Home should not regain the removed Info/privacy top-bar icon.
- Home must keep local tag chips and the local `+` in the same top horizontal row as service filters, while keeping the shared-tag `+` route separate.
- Entry cards must receive local tag names and show them instead of service/time when present.
- Detail local-tag heading must be `自作タグ`; shared-tag heading must be `共有タグ`.
- Local tag management must not show `リンク` / `JSON` share controls or the old large text `削除` label.
- Real-device DB data must not be cleared to bypass Room migration issues.

## Done When
- Contract check passes.
- Relevant Android/iOS build/test/lint checks have been run or clearly marked not run.
- Device verification, if requested, reports the exact package/bundle, device, backend, artifact paths, and any `NOT VERIFIED` boundary.
- Final report names the changed files and explains any remaining risk.

## Failure Handling
- If old UI returns, stop adding features and restore the contract first.
- If code and contract disagree, update the contract only when the user's current instruction explicitly changes the desired behavior.
- If schema drift is found, add a preserving forward migration or stop and report why it cannot be made safe.
