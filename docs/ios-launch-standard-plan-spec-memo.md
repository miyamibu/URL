# iOS LaunchStandard Spec Memo

## Goal
Align iOS behavior with the Android `LaunchStandard` limits and gating rules without implementing billing yet.

## Plan
- Plan type: `LaunchStandard` (fixed for launch)
- Personal URL save: `200`
- Normal tags: `10`
- Shared tags: `2`
- URLs per shared tag: `20`
- Export: enabled
- Ads: disabled (`shouldShowAds = false`)
- Shared sync: enabled only for shared-tag scope
- Subscription entry: hidden while `subscriptionEnabled = false`

## Limit Rules
- Existing data must always remain visible.
- If current usage is already over limit, do not hide or delete existing data.
- Block only new additions that would exceed limits.
- Personal URL count includes active + archived and excludes pending-delete-finalized rows.
- Shared-tag URL count is independent from personal URL count.
- Same URL can count in both personal and shared scopes.
- Sync-received data must be accepted even when over limit; only later manual additions are blocked.

## Entry Points To Enforce
- Manual URL save
- Share-extension URL save
- Normal tag create
- Shared tag create
- Add URL to shared tag

## Import Behavior
- When limits would be exceeded, do not auto-apply partial success.
- Treat as cancel/explicit confirmation flow (Android currently cancels and reports reason).

## Output/UI
- Add usage section in profile/settings:
  - `保存URL x / 200`
  - `通常タグ x / 10`
  - `共有タグ x / 2`
  - Per shared tag: `タグ名 y / 20`
- Show non-billing limit messages (no paywall wording).
