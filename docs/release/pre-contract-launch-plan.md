# Pre-contract Launch Plan

## Goal
外部契約前に完了できる launch 準備を終わらせ、契約後に差し込む値と外部操作だけを残す。

## Current Status Note
この文書は契約前 baseline の履歴メモ。現在の公開候補は Standard / Pro のアプリ内サブスクリプション復元後の前提で、privacy / Data safety / store listing draft も課金ありとして扱う。

## Scope
- 対象: Android / iOS の初回公開前準備。
- 初回契約前 baseline: local URL saver、広告なし、課金なし、LaunchStandard 固定、shared-tag cloud は無効化可能。
- 現在の store-submission baseline: 広告なし、Standard / Pro のアプリ内サブスクリプションあり、shared-tag cloud / account 機能あり。
- shared-tag cloud を公開する場合は、契約後に Supabase production 設定、account deletion URL、ストア回答を最終化する。

## Worktree Inventory
現在の作業ツリーには、launch 直前に採用判断が必要な変更が混在している。

| 区分 | 代表パス | 契約前対応 |
|---|---|---|
| Android shared-tag / UI / tests | `app/src/main/java/...`, `app/src/test/...`, `app/src/androidTest/...` | 差分レビュー、検証、採用判断 |
| iOS shared-tag / UI / project | `ios/URLSaveriOS/...`, `ios/URLSaveriOS.xcodeproj/project.pbxproj` | simulator build/test、採用判断 |
| App icon assets | `app/src/main/res/mipmap-*`, `ios/URLSaveriOS/Assets.xcassets/` | 表示確認、採用判断 |
| Evidence artifacts | `artifacts/ui-review/...`, `artifacts/app-icon/...` | 証跡として保持するか分類 |
| Historical deletion candidates | `CODEX_INSTRUCTIONS.md`, `archive/root-unrelated/...`, `tmp/laptimer-*.png` | `CODEX_INSTRUCTIONS.md` は現在、短い互換入口として復元済み。その他は承認なしでは削除/破棄しない |

## No-contract Tasks
- Keep Android release build possible when shared-tag cloud is disabled.
- Make live Supabase XCTest skip when the live environment is absent.
- Keep App Store / Google Play submission copy as drafts in this repo.
- Keep privacy and Data safety answers aligned with the current no-ads / paid-subscription posture.
- Keep App Links / Universal Links files ready, but mark SHA-256 and Team ID as contract-dependent.
- Keep iOS local-only App Store / Release validation independent from ignored dev Supabase `ios/Config/URLSaverSecrets.xcconfig`; do not pass that file with `-xcconfig` for local-only v1.0.
- Run local verification and record the exact pass/fail state.

## Contract-dependent Values
| Value | Used by | Source after contract |
|---|---|---|
| Apple Team ID | `web/invite-link/.well-known/apple-app-site-association`, provisioning | Apple Developer account |
| iOS App / Share Extension provisioning profiles | Xcode archive / TestFlight | Apple Developer account |
| Google Play App Signing SHA-256 | `web/invite-link/.well-known/assetlinks.json` | Play Console |
| Supabase production URL | Android BuildConfig, iOS Info.plist | Supabase project |
| Supabase publishable / anon key | Android BuildConfig, iOS Info.plist | Supabase project |
| Public account deletion URL | Play Console Data safety, privacy pages | GitHub Pages or other HTTPS host |

## Approval Gates
- Do not delete, restore, reset, clean, or discard files without explicit approval.
- Do not commit, push, deploy, submit, or install on physical devices without explicit approval.
- Do not paste `service_role`, `sb_secret`, private keys, passwords, or Apple/Google credentials into tracked files.
- Do not overwrite ignored local secret config files without explicit approval; create or use a separate local-only config when needed.

## Done When
- Android debug build, unit tests, lint, and contract-free release build have been attempted.
- iOS simulator tests and release-style generic build have been attempted.
- Store listing, privacy/Data safety, and external-value placeholders are documented.
- Remaining blockers are only external contracts, externally issued IDs, production secrets, or explicit approval-gated operations.
