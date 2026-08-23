# Store Submission Summary — 2026-08-23

## Google Play

- Canonical package: `jp.miyamibu.urlalbum`
- Submitted change: Data Safety questionnaire
- Imported packet: `google-play-data-safety-import-v3.csv`
- Packet SHA-256: `b347b759e816eef3306035cee4733cac9a8836d97f8b4c4d0d1c9c87c2fcc08e`
- Save result: Play Console confirmed the answers were saved and directed the operator to Publishing overview.
- Submission result: `1 件の変更を審査に送信しました`
- Post-submit state: after the automated quick check completed, Publishing overview showed `審査中の変更` and `変更内容は現在審査中です`.
- Public-page boundary: the public Data Safety page is not expected to update until Google completes review and propagation.

## App Store Connect

- Apple app ID: `6771251450`
- Canonical bundle: `com.mibu.codebridge.ios`
- Share extension: `com.mibu.codebridge.ios.share`
- Submitted version/build: `1.0.18 (20)`
- Team ID: `8R3B5675ZJ`
- Distribution IPA: `/tmp/rinbam-app-store-export-1.0.18-20/りんばむ.ipa`
- IPA SHA-256: `23c2aae8fce7d56495e978b68b7229c6ef27792859b877460de079155dd823d8`
- Upload result: `Upload succeeded.` / `** EXPORT SUCCEEDED **`
- Metadata: description, What's New, and review notes were aligned to the exact local-only binary; automatic release after approval and immediate all-user update were retained.
- Submission result: `1項目が提出されました`
- Post-submit state: `1.0.18 審査待ち`
- Public-page boundary: the public page remains on 1.0.17 until Apple approves and publishes 1.0.18.

## Device boundary

- iPhone 12: data-preserving overwrite install and launch of canonical `1.0.18 (20)` succeeded.
- Appium/WDA: not verified because the required administrator-run RemoteXPC tunnel was absent.
- Physical Android: not connected; emulator font-scale evidence is retained separately under `artifacts/ui-review/2026-08-23/`.

## Classification

`STORE_SUBMISSION_GO / EXTERNAL_REVIEW_PENDING / DEVICE_UI_PARTIAL`
