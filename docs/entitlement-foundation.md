# Entitlement Foundation (Phase A)

## Goal
Keep current URL Saver UX unchanged while preparing a single entitlement decision point for future billing, promo grants, and referral grants.

## What Changed
- Added `EntitlementResolver` in domain.
- Added `EntitlementGrant` and `EntitlementSource` domain concepts.
- `UsageSummaryDataSource` now resolves entitlements through `EntitlementResolver` instead of hard-coding `LaunchStandardPlan.entitlements`.
- Added a profile-only invite code input section gated by screen entry point.

## UI Scope
- Invite code input is shown only from the Main profile sheet.
- `Routes.CLOUD_AUTH` path does not show invite code input.
- Main / Archive / Detail / save completion flows are unchanged.

## Safety Notes
- Invite code input does not unlock paid features locally.
- Current apply action returns a preparation message: `招待コード機能は現在準備中です`.
- Future server/store grant data should be connected into `EntitlementResolver`.
