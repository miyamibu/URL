# Codex Prompt: Implement Dark UI from HTML Mock Without Changing Product Behavior

Use this prompt when asking Codex to translate the approved dark UI mock into the real Android Compose app for `URL共有アプリ_UrlSaver`.

## Issue map

1. Visual rewrite vs behavior drift
今回の目的は見た目の刷新であり、機能仕様の変更ではない。UI の実装中にナビゲーション、保存契約、duplicate 挙動、Undo フローを壊さない境界を明示する必要がある。

2. Token gap
現状の UI は `MaterialTheme` を使っているが、参照デザインは色・角丸・カード階層・ボタン強弱・余白設計まで再定義が必要で、色差し替えだけでは届かない。

3. Screen consistency risk
`Detail` だけ寄せても `Main` の一覧カード、フィルタ、追加導線、アーカイブ画面が旧デザインのままだと統一感が崩れる。共通トークンと共通コンポーネント化が必要。

4. Mock-to-Compose translation risk
承認済みの HTML モックはあくまで視覚参照であり、実装コードとして写経すべきものではない。Compose の責務分離を保ちつつ、見た目の言語だけを移植する必要がある。

5. Verification drift risk
デザイン変更は「ビルドは通るが、押せなくなった・読みにくくなった・画面が崩れた」が起きやすい。最低限のビルド・テスト・視覚確認の結果を同じターンで残す必要がある。

## Prompt

```md
Goal
Implement the approved dark visual redesign for the Android app in `URL共有アプリ_UrlSaver`, using the repository's HTML mock as the visual reference while preserving the existing product behavior and data contract.

Context
- Repository: `URL共有アプリ_UrlSaver`
- Source of truth is the current filesystem state.
- Approved visual reference:
  - `docs/mockups/urlsaver-dark-reference.html`
  - `docs/mockups/urlsaver-dark-reference.html` represents the target design language for the real app, not a literal DOM structure to recreate.
- Current Android UI is built with Compose and Material 3.
- Treat the existing app behavior as stable unless direct repo evidence or this prompt explicitly requires otherwise.
- Preserve these invariants unless direct repo evidence shows a current implementation already differs:
  - `normalizedUrl` remains the duplicate key
  - `openUrl = normalizedUrl`
  - card tap navigates to Detail only
  - delete remains DB-backed pending delete + Undo, not immediate destructive deletion
  - metadata-only updates must not change `updatedAt`
  - ViewModel / Repository / Worker / Domain responsibility split remains intact

Constraints
- Inspect the repo first. Do not rely on memory or git history as the source of truth.
- This is a visual implementation task, not a product-scope expansion task.
- Do not change user flows, storage semantics, repository contracts, metadata semantics, or navigation unless required to keep existing behavior working after the visual refactor.
- Prefer shared design tokens and reusable UI components over one-off per-screen styling.
- Use the HTML mock as a style target for:
  - dark background treatment
  - layered panel cards
  - heavy rounded corners
  - high-contrast title typography
  - blue primary actions
  - red destructive actions
  - muted secondary surfaces
  - accordion / section affordances
- Preserve accessibility and readability:
  - text contrast must remain readable
  - tap targets must stay practical
  - long titles and metadata text must still truncate or wrap safely
- Preserve screen structure where possible:
  - Main
  - Archive
  - Detail
- Do not introduce speculative Phase 1c+ features.
- Do not replace working logic with hardcoded preview data.
- If a visual choice in the mock conflicts with current app constraints, prefer:
  1. preserving behavior
  2. preserving the overall visual tone
  3. preserving implementation simplicity
  rather than copying the mock literally.

Done when
- The real Android Compose UI visually reflects the approved dark mock across the main user-facing surfaces.
- `Detail` clearly matches the dark panel/card/button language from the mock.
- `Main` and list cards are updated so they no longer look like the previous theme next to the new Detail.
- Shared styling is centralized in theme/tokens/components rather than duplicated inline everywhere.
- Existing interactions still work:
  - open
  - copy
  - archive
  - delete to pending delete
  - undo flows
  - detail navigation
  - manual add entry points
- No core URL-saving contract behavior is changed accidentally.
- Required validation commands are run and reported honestly.

Files to inspect first
- `AGENTS.md`
- `docs/mockups/urlsaver-dark-reference.html`
- `app/src/main/java/jp/mimac/urlsaver/ui/theme/Theme.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/theme/OrbitTokens.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/components/ServiceFilterRow.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/components/CollectionFilterRow.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/components/TagFilterRow.kt`
- any relevant drawables, fonts, and tests referenced by those files

Implementation expectations
- Start by defining or refining reusable design tokens:
  - colors
  - surface hierarchy
  - outline colors
  - corner radii
  - button styles
  - spacing rhythm
  - typography choices within repo constraints
- Add reusable Compose components when helpful, such as:
  - dark panel card
  - prominent action button variants
  - secondary / destructive button variants
  - icon button shell
  - section / accordion container
- Apply the new visual language at minimum to:
  - Detail top area
  - Detail content sections
  - Detail action buttons
  - Main list cards
  - Main bottom add bar or equivalent entry point
  - Archive surfaces if they visibly share the same system
- Keep the implementation disciplined:
  - business logic stays where it already belongs
  - composables handle layout, event wiring, and styling only
- If the current theme has light mode and dark mode support, do not break app startup or theming stability. If you intentionally bias toward the approved dark look, do so explicitly and keep the result coherent.
- If small copy/layout adjustments are needed for spacing or visual hierarchy, keep them minimal and truthful.

Validation method
- Run:
  - `./gradlew assembleDebug`
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintDebug`
- If practical in the current environment, also run:
  - `./gradlew connectedDebugAndroidTest`
- If instrumentation cannot run or fails for an unrelated existing blocker, report the exact reason honestly.
- Perform at least one implementation-level visual sanity check by inspecting the composables and, if possible in the environment, previewing or launching the app path you changed.

Failure-handling behavior
- If the mock cannot be matched exactly without breaking behavior or creating fragile Compose code, preserve behavior and document the compromise.
- If the refactor starts touching business logic or repository behavior, stop and correct course rather than letting a visual task become a behavior rewrite.
- Do not leave the app in a mixed half-migrated state where Detail is redesigned but Main/Archive still look unintentionally disconnected, unless you clearly state that as an explicit temporary limitation.
- Do not silently drop interactions, accessibility, or text safety in pursuit of visual fidelity.

Output format
- `Audit`
  - short note on current UI structure and the main visual gaps vs the mock
- `Plan`
  - concise implementation plan before major edits
- `Changes made`
  - short summary of token/component changes and screen updates
- `Validation`
  - exact pass/fail status for each command actually run
- `Visual notes`
  - brief note on where the final UI intentionally differs from the mock, if anywhere
- `Risks`
  - only the remaining meaningful design or implementation risks
```
