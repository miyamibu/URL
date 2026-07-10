Review summary:
- Export UI options are aligned on both platforms: format options are ZIP/JSON only, and destinations are 共有シート/ファイルに保存 only.
- Existing visual structure (section layout, card shape, spacing rhythm, component styling) remains unchanged; only option sets and behavior wiring were updated.
- Export behavior follows selected format end-to-end (UI selection -> request path -> serializer/packaging -> filename/mime -> share/save behavior).
- iOS `file` destination now performs a real file-save flow via SwiftUI `fileExporter` (not the same as share sheet).

Issues found:
- Android project-level validation commands in AGENTS (`./gradlew testDebugUnitTest`, `./gradlew assembleDebug`) are unavailable in this workspace state because `:app` build tasks are not registered (module build script missing), so Android compile/test could not be executed via Gradle.
- iOS full-suite test run has pre-existing environment-dependent failures in `SharedTagCloudLiveSyncTests` (missing live env config), unrelated to export changes.
- `ExportArchiveBuilderTests` appears outside active iOS test target membership (only-testing run executed 0 tests). Equivalent checks are executed in `ServiceFilterTests`.

Evidence:
- Android behavior linkage and option removal:
  - `app/src/main/java/jp/mimac/urlsaver/ui/ExportScreen.kt`
  - `app/src/main/java/jp/mimac/urlsaver/ui/ExportViewModel.kt`
  - `app/src/main/java/jp/mimac/urlsaver/data/ExportRepository.kt`
  - `app/src/test/java/jp/mimac/urlsaver/ExportRepositoryTest.kt`
- iOS behavior linkage and option removal:
  - `ios/URLSaveriOS/UI/ExportSheet.swift`
  - `ios/URLSaverShared/Data/ExportArchiveBuilder.swift`
- Active iOS focused tests passed in target:
  - `URLSaveriOSTests/ServiceFilterTests` (13 tests, including ZIP/JSON output checks and legacy option absence checks).

Commands run:
- `./gradlew testDebugUnitTest` -> failed (`Task 'testDebugUnitTest' not found in root project 'UrlSaver'`)
- `./gradlew :app:testDebugUnitTest` -> failed (`task not found in project ':app'`)
- `./gradlew assembleDebug` -> failed (`Task 'assembleDebug' not found in root project 'UrlSaver'`)
- `./gradlew :app:tasks --all` -> only help tasks listed for `:app` in current workspace state.
- `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -showdestinations` -> succeeded.
- `xcodebuild -workspace ios/URLSaveriOS.xcodeproj/project.xcworkspace -scheme URLSaveriOS -destination 'platform=iOS Simulator,id=2021719A-3EE0-4D14-8EFF-39758C799300' build` -> succeeded.
- `xcodebuild ... test` (full suite on iPhone 17 Pro Max) -> failed only at `SharedTagCloudLiveSyncTests` due missing live environment.
- `xcodebuild ... test -only-testing:URLSaveriOSTests/ExportArchiveBuilderTests -destination 'platform=iOS Simulator,id=1938724F-9804-4F14-8949-77F22D548A01'` -> succeeded with 0 tests executed.
- `xcodebuild ... test -only-testing:URLSaveriOSTests/ServiceFilterTests -destination 'platform=iOS Simulator,id=1938724F-9804-4F14-8949-77F22D548A01'` -> succeeded (13 tests, 0 failures) after `fileExporter` fix.

Residual risk:
- Android runtime/build verification remains pending until module Gradle tasks are restorable in this repository state.
- `ExportArchiveBuilderTests` file itself is not target-membered; coverage relies on equivalent assertions in `ServiceFilterTests`.
- Visual screenshot evidence was not captured in this run; review is code-based plus automated test/build logs.
