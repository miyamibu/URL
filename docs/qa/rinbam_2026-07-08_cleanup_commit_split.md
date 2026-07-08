# りんばむ 2026-07-08 整理レポート

## Goal

2026-07-08 の Instagram メディア順、Android メディアビューア、iOS 詳細表示まわりの変更を、後から追える単位に分けて commit する。

## Scope

- Instagram 投稿内メディア順を resolver から Android/iOS 表示まで保持する。
- Android の写真/動画混在カルーセルで、ページ切替時に点インジケータや外側レイアウトが縦にずれないようにする。
- iPhone 詳細画面の投稿内容欄だけ、文字を少し大きくし、上下幅を少し小さくする。
- 実機証跡と Render レスポンスは git 管理外の `artifacts/` に保持し、commit には含めない。

## Commit Split

1. `Preserve Instagram media order in resolver`
   - `scripts/media_resolver_backend.py`
   - `tests/test_media_resolver_backend.py`

2. `Persist Android media asset order`
   - Android Room schema / migration / DAO / repository / worker / resolver model
   - Android unit tests for migration and media order

3. `Persist iOS media asset order`
   - `ios/URLSaveriOS/App/URLSaverAppModel.swift`
   - `ios/URLSaveriOSTests/ServiceFilterTests.swift`

4. `Stabilize Android media carousel dots`
   - `app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt`
   - `docs/mobile-ui-regression-contract.md`
   - `scripts/verify_mobile_ui_contract.py`

5. `Adjust iOS post content detail section`
   - `ios/URLSaveriOS/UI/DetailView.swift`

6. `Document July 8 Rinbam change split`
   - `docs/qa/rinbam_2026-07-08_cleanup_commit_split.md`

## Validation

- `python3 scripts/verify_mobile_ui_contract.py`
- `python3 -m unittest discover -s tests -p 'test_media_resolver_backend.py'`
- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- `./gradlew lintDebug`
- iOS XCTest via XcodeBuildMCP: `101 passed / 0 failed / 3 skipped`
- iPhone physical install and Appium/WDA UI confirmation
- Android physical install and UI confirmation
- `git diff --check`

## Device Evidence

- Android media dots: `artifacts/device-verification/2026-07-08-android-media-dots-stable/`
- iPhone post content UI: `artifacts/device-verification/2026-07-08-iphone-post-content-ui/`
- Instagram order: `artifacts/device-verification/2026-07-08-instagram-order/`
- Audio fix: `artifacts/device-verification/2026-07-08-audio-fix/`
- Android app-data backups: `artifacts/device-backups/android/`

## Render Live Check

Checked against `https://rinbam-media-resolver.onrender.com` on 2026-07-08.

- `/health`: HTTP 200, `ok=true`, `version=8a152e83ac000a165d0fe4812a6b44980504cac1`
- `/resolve` for `https://www.instagram.com/p/DZz_coCmxkM?igsh=dnk4eDF0aGpuMmFi`: `ok=true`, `provider=instagram`, `assetCount=7`
- Returned order: `IMAGE 0`, `VIDEO 1`, `IMAGE 2`, `VIDEO 3`, `IMAGE 4`, `VIDEO 5`, `IMAGE 6`
- Evidence: `artifacts/render-verification/2026-07-08-cleanup-commit-split/`

## Excluded From Git

- Device screenshots, raw UI traces, app data backups, and raw Render JSON under `artifacts/`.
- No push, deploy, release, uninstall, `pm clear`, or app-data deletion is part of this cleanup.

## Remaining Boundaries

- Render was rechecked live, but this cleanup does not deploy new backend code.
- Real-device UI proof exists in `artifacts/`, but the binary/evidence files remain local and ignored.
