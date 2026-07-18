# Manual QA Matrix

## Goal
Provide the manual checks needed after repo readiness and before public launch. Save screenshots or short videos for every failing row and for the final pass summary.

## Android / Pixel 9a

| Scenario | Steps | Expected result | Evidence | Stop if |
|---|---|---|---|---|
| Fresh install | Install internal/release build on Pixel 9a test device. | App launches with canonical package `jp.miyamibu.urlalbum`. | Device/build screenshot. | Wrong package ID or crash. |
| Upgrade from previous build | Install over previous internal build without clearing data. | Existing saved URLs remain. | Before/after count. | Data loss or forced logout without reason. |
| App launch offline | Enable airplane mode and launch. | Saved local URLs visible; no crash. | Screenshot. | Launch blocks on network. |
| URL save | Manually paste a URL and save. | URL appears once; duplicate behavior is correct. | Screenshot. | Save fails or duplicates incorrectly. |
| Share-sheet save | Share URL from browser to りんばむ. | Share receiver saves and returns to app correctly. | Screen recording. | Share target missing or save fails. |
| Tag add/edit/remove | Add, rename, remove local tag. | Tag updates only selected entry where expected. | Screenshot. | Shared tag leaks into local-only flow. |
| Archive/unarchive | Archive and restore an entry. | State changes without deletion. | Screenshot. | Entry disappears permanently. |
| Delete / pending delete | Delete, observe pending state, use Undo. | Undo restores before grace window. | Screenshot. | Immediate destructive delete. |
| Export | Run normal export. | Export completes and does not include raw `fetchedBody`. | Archive listing. | Raw body/token/prompt appears. |
| AI-safe Export contents | Inspect `manifest.json`, `entries.jsonl`, `schema.json`, `README_FOR_AI.md`, `redaction_report.json`. | `publicSafeId`, `aiEligible`, redaction/excerpt fields present. | Sanitized file snippets. | raw DB id or raw body exposed. |
| Shared tag excluded from AI eligibility | Export entry with shared-tag provenance. | `aiEligible=false` by default. | JSON snippet. | Shared tag is eligible by default. |
| Manual ChatGPT handoff preview/redaction | Select one or more local tags containing fixtures in URL/title/author/tag/collection/summary/excerpt/memo. Open `ChatGPTに聞く`. | OR selection and eligible/excluded counts/reasons are correct; preview uses the ZIP's redacted values; known email/phone/token-like/Supabase/JWT/local-path patterns are masked; unknown-secret warning is visible. | Sanitized preview and archive snippets. | Shared/archived/pending item enters ZIP, known pattern remains, preview differs from ZIP, or warning is absent. |
| Manual ChatGPT handoff confirmation/share | Try to continue before and after confirming the preview. | ZIP/share is blocked before explicit confirmation; after confirmation Android attempts ChatGPT direct share and safely falls back to OS chooser; no question payload/API/OAuth/login occurs. | Screen recording and sanitized intent/share evidence. | Unconfirmed share succeeds, wrong archive is shared, app auto-inputs/sends a question, or provider network call occurs. |
| Feature flag OFF means no normal AI transparency UI | Launch release/internal build with default flags. | ChatGPT personal-link card may be visible in the shared-tag profile screen but is unavailable when external configuration is absent; AI transparency entry is absent from Release UI. | Home/profile screenshots. | Unapproved AI transparency UI or an actionable unconfigured sync appears. |
| Link death snapshot display | Open/export saved entry with saved metadata. | Shows or exports saved-time snapshot notice. | Screenshot/export snippet. | User cannot tell metadata is saved-time. |
| Account/local data deletion clears AI receipt/draft | Trigger local/account deletion flow in test account. | AI receipt/draft/diff local tables are cleared with app data. | Before/after check. | AI local data remains. |
| Release build smoke test | Run top flows on release-equivalent build. | No crash or release-only missing resource. | Build/version screenshot. | Release build differs from tested behavior. |
| Crash-free quick monkey test | Navigate for 5 minutes across save/list/detail/export. | No crash or ANR. | Logcat summary. | Crash/ANR. |

## iOS / TestFlight

| Scenario | Steps | Expected result | Evidence | Stop if |
|---|---|---|---|---|
| Fresh install | Install TestFlight build. | App launches with bundle `com.mibu.codebridge.ios`. | Screenshot. | Wrong bundle or crash. |
| Upgrade from previous TestFlight build | Install over previous TestFlight. | Existing saved URLs remain. | Before/after count. | Data loss. |
| App launch offline | Enable airplane mode and launch. | Local saved URLs visible. | Screenshot. | Network required for launch. |
| Share Extension | Share URL from Safari to りんばむ extension. | Extension saves URL and app shows it. | Screen recording. | Extension missing or crash. |
| URL save | Paste and save URL in app. | Entry appears once. | Screenshot. | Save fails. |
| Tag operations | Add/edit/remove local tag. | UI and saved state update correctly. | Screenshot. | Tag state corrupts. |
| Archive/unarchive | Archive then restore. | Entry state changes without permanent loss. | Screenshot. | Entry lost. |
| Export | Run export/share flow. | Export completes. | Share sheet/export evidence. | Export fails. |
| AI-safe Export contents | Inspect JSON/ZIP output. | Redaction/excerpt/publicSafeId fields present; raw body absent. | Sanitized snippet. | raw body/token/prompt appears. |
| Manual ChatGPT handoff preview/redaction | Select local tags with sensitive-pattern fixtures and open `ChatGPTに聞く`. | Eligible/excluded counts/reasons are correct; preview equals ZIP; all known patterns are masked; unknown-secret warning is visible. | Physical-iPhone Appium screenshots/source plus sanitized archive snippets. | Shared/archived/pending item enters ZIP, known pattern remains, preview differs from ZIP, or warning is absent. |
| Manual ChatGPT handoff confirmation/share | Attempt generation before confirmation, then confirm and open share. | Unconfirmed generation/share is blocked; confirmed flow opens the iOS share sheet with the intended ZIP; no question payload/API/OAuth/login occurs. | Physical-iPhone Appium/WDA operation evidence and share-sheet screenshot. | Unconfirmed share succeeds, wrong ZIP is shared, app auto-inputs/sends a question, or provider network call occurs. |
| Link death snapshot display | Open/export saved metadata entry. | Saved-time notice is visible/exported. | Screenshot/snippet. | Notice missing. |
| Feature flag OFF | Launch default TestFlight build. | AI Transparency is absent and unconfigured ChatGPT personal-link sync is not actionable. The separately documented manual `ChatGPTに聞く` export entry may remain visible. | Screenshots. | Debug/provider UI is exposed or unconfigured sync is actionable. |
| Account/local data deletion clears AI receipt/draft | Trigger deletion flow in test account/local data reset. | AI local data cleared. | Before/after check. | Receipt/draft persists. |
| Accessibility / Dynamic Type basic check | Increase text size and navigate list/detail/export. | Text remains usable; no major overlap. | Screenshots. | Critical overlap or blocked action. |
| Crash-free smoke test | Use save/list/detail/export/share for 5 minutes. | No crash. | Device log summary. | Crash. |

## MCP / ChatGPT Developer Mode

| Scenario | Steps | Expected result | Evidence | Stop if |
|---|---|---|---|---|
| MCP disabled returns safe response / 401 | Disable `URLSAVER_MCP_ENABLED` and call endpoint. | 404 `mcp_disabled` or 401 auth challenge; no data. | curl status. | Personal data returned. |
| MCP enabled in staging only | Enable on staging host only. | Production remains disabled unless launch owner enables it. | Env screenshot without values. | Production accidentally enabled. |
| Auth required | Call without Authorization. | 401 `auth_required`. | curl status. | Noauth data returned. |
| Invalid token rejected | Call with dummy token. | 401 `invalid_token`. | curl status. | Dummy token accepted. |
| Search returns only current user | Query with staged user token. | Only that user's local personal links. | Sanitized response. | Cross-user result. |
| Fetch returns excerpt/summary, not raw body | Fetch a known `publicSafeId`. | Summary/excerpt/snapshot notice only. | Sanitized response. | raw body appears. |
| list_tags excludes shared tags by default | Call `rinbam.list_tags`. | Local personal tags only. | Sanitized response. | Shared tags included. |
| includeSharedTags rejected or scoped | Call search with `includeSharedTags=true`. | Rejected unless explicit future scope exists. | curl status/body. | Shared tags included silently. |
| get_ai_receipt returns metadata only | Call receipt tool. | Metadata and size buckets only. | Sanitized response. | raw prompt/body/token appears. |
| Prompt injection fixture cannot write/external call | Search/fetch entry containing injection text. | Read-only output; no write/external side effect. | Logs and response. | Any mutation or external call. |
| Rate limit works | Repeatedly call staging endpoint. | 429 after configured threshold. | curl status. | Unlimited requests. |
| Audit logs do not contain raw prompt/body/token | Inspect staging logs. | Logs contain status/tool/user boundary only, no secrets/raw text. | Sanitized log excerpt. | sensitive data appears. |
