# iPhone 1.0.19 (21) Appium/WDA verification

## Identity

- Verified at: 2026-08-26 10:23-10:41 JST
- App: `りんばむ`
- Canonical bundle ID: `com.mibu.codebridge.ios`
- Installed version: `1.0.19`
- Installed build: `21`
- Physical device: iPhone 12, iOS 26.6
- Xcode/usbmux UDID: `00008101-00066D96340A001E`
- CoreDevice ID: `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E`
- Backend: Appium 3.5.0 / XCUITest driver 11.16.3 / WebDriverAgent over USB
- Appium sessions: `1e9c05db-fdc3-440f-948f-3b877fcb04b9`, `4c0a5eef-2665-4fe2-8559-12a3079045fd`

## Safety boundary

- `DevToolsSecurity -status` returned `Developer mode is currently enabled` before session creation.
- Session used `appium:noReset=true`.
- The existing canonical app was not uninstalled, reinstalled, reset, or cleared.
- The installed development-signed 1.0.19 (21) app was operated. This is physical UI proof for the current version/build identity, not installation proof for the exported App Store distribution IPA.

## Verified operations

1. Appium created a real-device XCUITest session for `com.mibu.codebridge.ios` on iOS 26.6.
2. The home menu button was located by its accessibility label and tapped through Appium.
3. The menu displayed `プロフィール`, `画像つき表示に切り替える`, `選択`, `使い方`, and `データの取り扱い`.
4. `使い方` was tapped through Appium and opened the rich illustrated manual page rather than first-run onboarding.
5. The top of the manual showed `使い方` and `まず覚える` with the illustrated save/tag/search guidance.
6. Three upward swipes moved the manual to its lower content, including the `共有とAI` section and `確認してChatGPTへ渡す` guidance.
7. Two downward swipes returned the manual toward the top, proving both scroll directions.
8. The in-page back control returned to the home screen.
9. The home accessibility hierarchy exposed the required five bottom actions:
   - `グループ` at y=742
   - `エクスポート` at y=742
   - center `URLを追加` (`plus`) at y=712
   - `タグ管理` at y=742
   - `アーカイブ` at y=742
10. The center add button therefore visibly and geometrically protruded above the other four bottom actions.
11. The center `URLを追加` control opened the add sheet; the URL was left empty, `保存` remained disabled, and the sheet was cancelled without creating data.
12. `タグ管理` opened its management popover and was dismissed without creating, editing, sharing, reordering, or deleting tags.
13. `エクスポート` opened its export popover and was dismissed without generating or sharing a file.
14. `アーカイブ` opened the archive page and its back control returned to home without restoring or mutating an entry.
15. `グループ` and `ChatGPT` each opened their respective non-destructive navigation surfaces and returned to home without changing data or initiating a share.

These checks are classified as a current-version physical-iPhone core-navigation smoke pass. They are not a claim that every feature flow or every state was exercised.

## Retained screenshots

- `01-menu-open.png`: privacy-safe menu state after the Appium tap.
- `02-usage-top.png`: top of the rich illustrated manual.
- `03-usage-lower.png`: lower manual content after three upward swipes, including AI handoff guidance.
- `04-usage-return.png`: manual after two downward swipes toward the top.

SHA-256:

- `01-menu-open.png`: `b993c5dabcfe1c58cceb77848a8e12e42df7b790137e77b42f001e587db1a572`
- `02-usage-top.png`: `1fdef2b88f8136bbbaade8dd157fcda857bdaabe6fc44666aa83b551f976a310`
- `03-usage-lower.png`: `0ee8860a9e5c88b1913ccf7ce6132299f2bea71c058f52bd729ae0572466511e`
- `04-usage-return.png`: `7f23992e4bb617032f96ae60d59fdec94cc1685fb86b2e57ea86b3ab8683d37a`

## Privacy handling

- The initial home screenshot and raw accessibility sources contained user-saved URL/title data and were not retained in the repository.
- The first raw Appium server log was generated only for this run and removed after verification because Appium may record screenshot/source payloads. The second run used error-level console logging and did not create a repository log file.
- Temporary source files used to count allow-listed accessibility labels were removed after the checks.
- Only the privacy-safe menu/manual screenshots above are retained.

## Not verified

- The Share Extension App Group-inaccessible fail-closed branch was not deliberately induced on the physical device because doing so would require changing signing/entitlement/runtime conditions outside this UI verification.
- Full create/edit/delete/share completion flows, payment flows, and distribution-signed App Store execution were not exercised by this core-navigation smoke.
- Apple review approval and public App Store propagation are external gates and are not implied by this device proof.
