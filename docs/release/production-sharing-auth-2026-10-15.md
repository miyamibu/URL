# Production identity linking and provider status — 2026-10-15

Status: mobile candidate `1.0.27 (42)` in validation. Production Google/Apple configuration and same-user linking are verified. ChatGPT commercial sign-in application is received; approval and a production client have not been issued. Store upload/submission of this candidate is tracked separately in the final submission checklist.

## Production configuration and account proof

- The automatic group migration was applied on October 14 and was not replayed. Deployment/catalog evidence is recorded in [the migration snapshot](production-sharing-auth-2026-10-14.md).
- Production project `xocumgxbylmpoobfqows` has Google and Apple enabled and manual identity linking enabled. Saved settings were reopened to verify persistence. Email confirmation remains enabled, anonymous sign-in disabled, and single-session-per-user enforcement disabled.
- Apple Services ID `com.mibu.codebridge.web` is associated with native primary App ID `com.mibu.codebridge.ios`, domain `xocumgxbylmpoobfqows.supabase.co`, and callback `https://xocumgxbylmpoobfqows.supabase.co/auth/v1/callback`. Supabase accepts both the Web Services ID and the native audience.
- The owner completed the Apple key download and OAuth client-secret input/save. The private key passes local structure validation and the generated ES256 secret passes signature validation. Private values, key material, account addresses and identifiers are excluded from this record and Git. The configured secret expires April 12, 2027 at 14:30 JST; no renewal automation was created.
- Added exact redirect `urlsaver://auth/callback` while retaining the existing allowlist entries. A newly started Google sign-in returned to the canonical iPhone app. The earlier SiteURL fallback does not establish that the existing `urlsaver://**` wildcard was invalid.
- The owner completed native Apple linking from the Google-signed-in iPhone account. A read-only production identity query confirmed both `apple` and `google` on the same user, using a hashed identifier for comparison. No separate account was merged or deleted.
- The physical Android account screen matched the iPhone account using a locally hashed normalized email. This is distinct from Android build42 provider refresh and visual proof, which must be recorded after its data-preserving update.

## Approved embedded display

The user approved the generated provider-state direction with a specific correction: retain the current Profile / Account panel, placement and navigation. No new page is introduced. Google/Apple are static checked `連携済み` rows only after authenticated user identities are verified. Only a provider verified as unlinked offers its existing link action. Unknown/loading/error states remain explicit and errors offer retry.

Both platforms use a dedicated Auth user GET to refresh display state, without invoking shared-tag sync or uploading the local outbox. Saved legacy sessions remain readable, and an unverified legacy provider state is unknown. An account change or stale response must not mark a different account as linked.

Google uses the official original-color G image with aspect-fit sizing, Apple uses a recognizable provider mark, and ChatGPT reuses the existing white blossom with a dark backing. ChatGPT remains noninteractive and marked `準備中` until an approved client is available. Disabled presentation must preserve icon legibility. The iPhone success callback also closes the completed Google Safari sheet; unsuccessful or untrusted callbacks do not trigger that success behavior.

## Verification and evidence boundary

- iOS: five relevant domain tests passed with zero failures, covering legacy decoding, identities, GET-only persistence, invalid user data and account changes. Compile and mobile UI contract passed. Appium/XCUITest on the canonical physical iPhone verified both static linked rows, all provider icons, the retained account and profile refresh after app restart. The final `1.0.27 (42)` app was installed with data preserved and its inventory and provider screen were rechecked.
- Android: 72 focused OAuth/repository/ViewModel/session-codec tests passed with zero failures, including a same-user token-change race. Debug assemble and lint passed. The final official Apple artwork update also passed Debug assemble/lint and the UI contract. Play-signed physical-device evidence is pending at this snapshot.
- The iOS distribution archive/export and single upload succeeded at 15:58:46 JST. App/share canonical identifiers, `1.0.27 (42)`, distribution signatures and disabled debug entitlement were verified; all eight app runtime settings match the approved build41. Apple processing and review submission are separate stages. Existing iOS/Android build41 release records remain historical. Current Google Play detail shows submission25 published on October13 at22:58 JST. App Store detail shows `1.0.26 (41)` ready for distribution. A new marketing version is therefore required for this update.
- OpenAI's official form displayed an explicit inquiry-received confirmation after the owner submitted it on October15. Receipt does not establish approval, client issuance, configured ChatGPT login or working ChatGPT account linking.

Private raw provider/device evidence remains under `artifacts/production-auth/` and `artifacts/ui-review/2026-10-15/`, excluded from Git. No user data was reset, no production fixture was created, and no destructive device operation was used.
