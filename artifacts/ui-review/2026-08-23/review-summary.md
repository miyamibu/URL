# Android Home Visual Review — 2026-08-23

## Scope

- Canonical package: `jp.miyamibu.urlalbum`
- Backend: Android emulator `emulator-5554` / AVD `rinbam_api36_pixel9a`
- Display: 1080 x 2424, density 420
- Build: current-source `app-debug.apk`, installed with `adb install -r -t`
- Physical Android: not connected; these captures are not physical-device evidence

## Captures

| Font scale | PNG | SHA-256 | Finding |
|---|---|---|---|
| 1.0 | `android-home-font-100.png` | `f75f5c25abdc39497c06497bacd5b996d963dd172f53c3bdc0984546b1082520` | Five main actions and center add action visible; labels fit. |
| 1.3 | `android-home-font-130.png` | `2c2c6e2bdbce35e29648d0c41cf24ae5f980f396f5b6cbfe1b50c117b78a03b8` | Expanded bottom-bar layout is active; wrapped labels remain inside the bar. |
| 2.0 | `android-home-font-200.png` | `54d67341076fe9a8b3cbdd88eb9695af9a8ba035a92d3a6409c51cb0c9556735` | Five actions remain visible; capped labels and increased bar height avoid bounds clipping. |

The corresponding UIAutomator XML files are retained beside each PNG. Emulator font scale was restored to `1.0` after capture.

## Review perspectives

- User: primary home actions remain discoverable at all three scales; no action disappears behind overflow.
- Designer: the center add action remains visually dominant; expanded labels increase bar height predictably instead of compressing icon and text.
- Administrator/release: canonical package and repeatable font-scale evidence are recorded; no physical-device claim is made.
- Adversarial accessibility: labels wrap at large scale and the ChatGPT floating action occupies nearby visual space, but captured controls remain visible and no text is clipped outside its bounds. TalkBack traversal, switch control, landscape, and physical-device tap targets were not verified in this pass.

## Verdict

`PASS_EMULATOR_VISUAL / PHYSICAL_ANDROID_NOT_VERIFIED`

The visual review supports the responsive bottom-bar change. It does not replace physical Android or iPhone interaction proof.
