# iPhone physical Appium E2E (2026-07-15)

- Device: iPhone 12 (`00008101-00066D96340A001E`)
- CoreDevice ID: `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E`
- Bundle ID: `com.mibu.codebridge.ios`
- Build: `1.0.14 (15)`
- Backend: Appium 3.5.0 + XCUITest 11.16.3 + RemoteXPC tunnel
- Session: `b00ea1e3-211d-495e-96f8-579417238c93`

## Checked operations

1. Existing home card tap opened `詳細`.
2. `メディアを開く` opened the media sheet and displayed page `1 of 7`.
3. `閉じる` closed the media sheet and `戻る` returned to home.
4. `＋` opened the manual URL form.
5. Entered `https://example.com/rinbam-physical-e2e-20260715` and tapped `保存`.
6. Home displayed the saved `example.com` card after normalization.

Screenshots and Appium XML sources in this directory are the raw evidence for each step.
