# Codex Prompt: Native iOS Port with Android Phase 1a/1b Parity

> Historical prompt: this document describes the original Phase 1a/1b native iOS port request. Current iOS work must also follow `AGENTS.md`, `DESIGN.md`, and `docs/qa/rinbam_canonical_story_status.csv` because search, tags, collections, shared tags, AI-friendly export, and store/account flows are now approved product areas in this repo.

Use this prompt when asking Codex to build a native iOS version of `URL共有アプリ_UrlSaver` that matches the Android app's documented product contract as closely as possible.

## Issue map

1. Product parity vs platform fit
「全く同じ」は Android の実装を機械的に写す意味ではなく、Phase 1a/1b の振る舞い・データ契約・文言・導線を iOS でも揃えることを意味する。UI は iOS ネイティブでよいが、意味のズレは避ける必要がある。

2. Share intake parity
Android の `ACTION_SEND` / `ACTION_SEND_MULTIPLE` に相当する iOS 側の共有導線は Share Extension や app handoff を伴う可能性があり、単一 URL / 複数 URL / 手動貼り付けの保存結果が仕様どおり揃う必要がある。

3. Persistence invariants
このアプリの中心契約は URL 保存思想にある。特に `normalizedUrl` unique、`openUrl = normalizedUrl`、`recordState`、DB-backed pending delete + Undo は iOS 版でも崩せない。

4. Background metadata behavior
Android の WorkManager を iOS がそのまま持つわけではないため、metadata 取得は iOS の実行モデルに合わせて実装しつつ、分類・更新責務・ユーザー向け挙動は合わせる必要がある。

5. Verification drift risk
Android の仕様書と違う iOS 版を作ると、見た目は似ていても別アプリになる。コードだけでなく、iOS 側の README や補足文書、テスト、検証結果も同じパスで揃える必要がある。

## Prompt

```md
Goal
Create a native iOS app for `URL共有アプリ_UrlSaver` that matches the Android app's Phase 1a/1b product contract as closely as possible while using idiomatic iOS implementation choices.

Context
- Repository: `URL共有アプリ_UrlSaver`
- Source of truth is the current filesystem state, especially:
  - `AGENTS.md`
  - `README.md`
  - `docs/phase1a-spec.md`
  - `docs/phase1b-draft.md`
  - `docs/phase1b-ux-checklist.md`
  - Android implementation under `app/src/main/java/jp/mimac/urlsaver/...`
- Treat the documented Phase 1a/1b contract as the parity target for iOS.
- If there is no existing iOS app, create one in-repo rather than proposing one abstractly.
- Match behavior, data rules, and user-facing meaning first. Platform-specific UI details may be adapted to iOS as long as the product semantics stay aligned.

Preserve these invariants unless direct repo evidence forces otherwise
- Primary saved object is the URL.
- Duplicate key is `normalizedUrl`.
- `originalUrl` preserves raw input.
- `canonicalId` is supplemental, not the duplicate key.
- `displayUrl` is display-only.
- `openUrl = normalizedUrl`.
- Tapping a list card navigates to Detail only; it must not directly open the URL.
- Main active list supports swipe right to archive and swipe left to pending delete using the persisted Undo model.
- Metadata-only updates must not change `updatedAt`.
- Duplicate archived guidance remains a "見る" style path to inspect the existing item, not an automatic restore-first flow.

Scope to match on iOS
- single URL share receive
- multiple URL share receive with sequential save + aggregate feedback
- manual paste save
- URL extraction / normalization / duplicate detection
- local persistence with durable uniqueness on `normalizedUrl`
- Main / Archive / Detail
- Main active list swipe actions
- deletion grace + Undo with persisted state
- metadata fetch scheduling / execution
- title / memo rules
- tests and docs for the iOS implementation

Explicitly out of scope
- WebView
- search
- tags / advanced search
- collections
- user labels
- shared tag sync
- shared tag import / export
- tag deep links
- backend sync, Supabase sync, ads, or other non-Phase-1a/1b features unless the repo now clearly makes them part of the contract
- cross-platform frameworks such as Flutter or React Native
- speculative Phase 1c expansion

Constraints
- Inspect the current repo first. Do not rely on assumptions or stale memory.
- Treat this historical prompt as subordinate to current repo contracts. If `AGENTS.md`, `DESIGN.md`, or `docs/qa/rinbam_canonical_story_status.csv` includes collections, shared tags, tag import/export, tag deep links, or search as current product behavior, preserve parity instead of removing those flows.
- Build a real iOS app, not just a design document.
- Prefer native Apple technologies. Default to Swift + SwiftUI unless repo reality strongly suggests otherwise.
- Choose a boring, stable local persistence approach that can enforce the equivalent of a DB-level unique constraint on `normalizedUrl` and support migration safely. Do not weaken the uniqueness contract.
- Keep architectural responsibility boundaries clear, analogous to Android:
  - domain: URL rules / title rules / state rules
  - data: persistence / repository / share intake bridging / scheduling
  - background: metadata resolution/fetch
  - ui: screens / navigation / view models or equivalent presentation state
- Do not move business rules into SwiftUI views beyond event wiring and display formatting.
- Respect iOS platform reality:
  - share intake may require a Share Extension plus app handoff or shared container
  - background execution may require a best-effort iOS-native scheduling strategy
  - if exact Android timing semantics are impossible on iOS, preserve the data contract and user-visible behavior as closely as possible, then document the gap honestly
- Lock these URL rules unless current repo evidence clearly overrides them:
  - share extraction priority mirrors Android:
    1. text content equivalent to `EXTRA_TEXT` / `EXTRA_HTML_TEXT` / subject-like text
    2. item-provider / clip-like textual payloads
    3. URL/file providers analogous to stream payloads
    4. direct incoming URL payload if present
  - within each source, use the first valid absolute URL candidate from that source, then fall back to the next source only if none are valid
  - single-share payload with multiple URLs saves only the first extracted valid URL and reports that limitation honestly
  - multi-share payload saves all extracted valid URLs after normalized dedupe, sequentially, and reports aggregate results honestly
  - `NO_URL_FOUND` means no candidate string was found at all
  - `INVALID_URL` means candidate text existed but no valid savable URL survived validation
  - new saves accept `https` only
  - legacy loopback `http` is read-compat only and must not expand into general new-save `http` support
  - normalization keeps query, strips fragment, lowercases scheme/host, removes default ports, and trims trailing slash only for non-root paths
  - `displayUrl` is derived from `normalizedUrl`, hides scheme and fragment, hides query by default, and preserves only YouTube `v=` as a query exception
- Lock these metadata contract rules unless current repo evidence clearly overrides them:
  - metadata states remain `PENDING`, `READY`, `FAILED`, `UNAVAILABLE`
  - `PARSE_FAILED` is classified as `UNAVAILABLE`
  - partial fetch success still counts as `READY` if any of title/body/thumbnail is obtained
  - metadata-only completion must not change `updatedAt`
  - `metadataFetchedAt` updates only on terminal metadata results such as `READY`, `FAILED`, or `UNAVAILABLE`
  - user-facing copy must mirror Android's meaning rather than generic loading/error placeholders:
    - `PENDING` -> `取得中`
    - `FAILED` -> `一時的に取得できません`
    - `UNAVAILABLE` -> `自動取得できません` or `自動取得に制限あり`
    - `READY` -> no status line by default
  - Detail separates user-facing explanation from technical detail and may expose raw metadata error codes only as secondary detail
- Keep user-facing copy aligned with the Android contract unless repo evidence shows iOS should intentionally differ.
- Update iOS-side docs in the same pass so the repo does not contain invisible behavior.
- Do not modify Android behavior unless truly necessary for shared documentation consistency.
- If you implement a Share Extension or equivalent intake target, keep it thin:
  - do not duplicate repository/business rules there
  - parse/share handoff may happen there, but durable save rules, duplicate semantics, and state transitions belong in shared domain/data layers
  - bridge results into the main app cleanly instead of splitting the product contract across two implementations

Done when
- A buildable iOS project exists in the repo.
- The iOS app can save URLs from share intake and from manual input.
- Single-share and multi-share flows both work according to the documented contract.
- `normalizedUrl` is still the dedupe key, and duplicate handling distinguishes active / archived / pending delete correctly.
- Main / Archive / Detail are implemented and navigable.
- Card tap goes to Detail only.
- Main active list supports swipe right archive and swipe left pending delete with Undo.
- Delete grace is persisted rather than being a purely in-memory UI trick.
- Title, memo, display URL, and metadata rules behave consistently with the Android contract.
- Metadata fetch state and user-facing messaging are implemented honestly.
- Tests cover the critical domain / repository / duplicate / undo / metadata / share parsing behavior.
- Build and test commands for the iOS target are run and reported honestly.
- Any unavoidable Android-vs-iOS parity gaps are documented explicitly rather than hidden.

Files to inspect first
- `AGENTS.md`
- `README.md`
- `docs/phase1a-spec.md`
- `docs/phase1b-draft.md`
- `docs/phase1b-ux-checklist.md`
- `app/src/main/java/jp/mimac/urlsaver/domain/UrlRules.kt`
- `app/src/main/java/jp/mimac/urlsaver/domain/Models.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/UrlRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepository.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/ShareExtras.kt`
- `app/src/main/java/jp/mimac/urlsaver/data/MetadataWorkScheduler.kt`
- `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/MainActivityViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/MainListViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/DetailViewModel.kt`
- `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
- relevant tests under `app/src/test` and `app/src/androidTest`

Implementation expectations
- Create the iOS app inside the repo with a clear top-level location such as `ios/` if no iOS structure exists yet.
- Keep the first implementation iPhone-first unless repo evidence requires iPad-specific treatment.
- Use iOS-native navigation and interaction patterns, but preserve the same information architecture:
  - Main
  - Archive
  - Detail
- Implement single and multiple URL share reception in a way that reaches the same saved-result semantics as Android.
- Preserve the Android URL normalization and effective title rules as closely as possible.
- Preserve duplicate-state semantics:
  - duplicate active
  - duplicate archived
  - restored from pending delete
- Keep share-intake architecture disciplined:
  - intake surface is thin
  - business rules are centralized
  - host app receives enough result context to show the same save outcome semantics without reinterpreting the contract differently in multiple places
- Keep pending delete durable across relaunch as much as iOS allows.
- Keep metadata state ownership strict:
  - metadata fetch logic may update metadata fields and fetch status
  - metadata-only completion must not mutate fields that Android keeps stable
- Keep open/copy actions based on `openUrl`.
- Show truthful error and fallback messaging. Do not silently drop invalid or missing URLs.
- Add concise iOS-specific documentation for project structure, build/test commands, and any platform limitations.

Validation method
- Discover the actual iOS build targets first, then run the real commands for the created project.
- At minimum, run:
  - the project's iOS build command
  - the project's iOS test command
- If you create an Xcode project/workspace, prefer honest `xcodebuild` commands and include the exact scheme/destination used.
- If simulator-based integration tests are practical, run them and report the result.
- If some verification cannot run in the current environment, explain exactly what was blocked and what did or did not get validated.

Failure-handling behavior
- If an iOS platform constraint prevents exact parity, stop and explain the constraint before inventing a fake equivalent.
- Do not ship an iOS shell that omits share intake, durable dedupe, or persisted delete grace while claiming parity.
- Do not silently relax `normalizedUrl` uniqueness, `openUrl = normalizedUrl`, or card-tap-to-detail behavior.
- Do not quietly widen scope into tags, search, backend sync, or other non-target features.
- If a major decision has non-obvious long-term consequences, document the tradeoff and choose the smallest stable path.

Output format
- `Audit`
  - short note on what exists today, what was missing for iOS, and the key parity risks
- `Plan`
  - concise implementation plan before major edits
- `Changes made`
  - short summary of the iOS app structure, core behavior, and doc/test additions
- `Validation`
  - exact pass/fail status for each iOS command actually run
- `Parity gaps`
  - only the remaining Android-vs-iOS differences that are real and material
- `Risks`
  - only the remaining meaningful risks or blockers
```
