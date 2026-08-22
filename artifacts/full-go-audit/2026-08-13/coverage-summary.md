# Coverage Summary — S2 current evidence

## Snapshot and counting rule

- Count time: 2026-08-13 JST; final command timestamp is recorded in `sol-max-s2-raw.md`.
- Root repository: branch `codex/full-go-mobile-s1-20260811`, HEAD `965d4d0cdd8fd916bc5adc996fc682b9875022d3`.
- Nested repository `web/usage-guide`: branch `main`, HEAD `dde36e2c253376c01843aa859fddb65d9602374a`.
- Public Sites v5 is tied to source commit `ef3a02bce618c95b96d9ec263064c1e4bc3b0537`, not current nested HEAD.
- Starting snapshots declared by C0 were root `965d4d0…` and nested `20add6f…`. Root HEAD did not move; nested HEAD and both working-tree contents changed. The target is therefore `SNAPSHOT_CHANGED_DURING_REVIEW`, and the dirty root tree is not a frozen release snapshot.
- Counts below use exact manifests where an exhaustive denominator exists. Categories without a current exhaustive inventory are marked `UNVERIFIED`; historical inventory numbers are not promoted to current denominators.

## Coverage by required category

| Category | Verified / denominator | State breakdown / evidence boundary |
|---|---:|---|
| Information sources | 2 / 17 fully verified | 2 VERIFIED, 8 PARTIALLY_VERIFIED, 2 UNVERIFIED, 4 INACCESSIBLE, 1 NOT_APPLICABLE. The 17-source denominator comes from the original audit manifest. Current public Web, App Store page/archive, and Play Console evidence improved individual items, but production Auth/backup/GCP/App Store Connect remain incomplete. |
| Files | 26 / 1,013 VERIFIED | `project-file-manifest.csv` exhaustively lists 906 root tracked + 71 root untracked + 36 nested tracked paths: 26 VERIFIED, 259 PARTIALLY_VERIFIED, 158 UNVERIFIED, 570 OUT_OF_SCOPE. No ignored/cache path is presented as a reviewed file. |
| Requirements | 59 / 59 structurally traced | Canonical tracker: 25 PASS, 24 BLOCKED_EXTERNAL, 5 PARTIAL, 1 LOCAL_ONLY, 4 REMOVED. Structural coverage is complete; implementation/runtime completion is not. CSV/XLSX equality and summary sheet are validator-verified. |
| Screens | UNVERIFIED / UNVERIFIED | No current exhaustive screen manifest was rebuilt after the shared mobile changes. Web evidence covers public privacy/deletion/reset/invite, usage-guide, and unauthenticated admin login; mobile post-fix rendered coverage is incomplete. |
| Routes | UNVERIFIED / UNVERIFIED | Public verifier covers 6 named production routes/assets and admin verifier covers the root plus protected unauthenticated API boundaries. The complete current route denominator was not independently enumerated. |
| Components | UNVERIFIED / UNVERIFIED | Changed Web/admin components and selected mobile components were reviewed. No current all-component denominator exists after the dirty-tree changes. |
| States | UNVERIFIED / UNVERIFIED | Initial audit defined 33 state classes, but the full screen-by-state matrix was not rerun. Reset recovery states and admin unauthenticated/focus/forced-colors states have evidence; complete loading/error/offline/timeout/role/theme coverage does not. |
| Roles | 3 / 4 partially exercised | Unauthenticated public user, normal mobile user (host/simulator evidence), and unauthenticated admin boundary have evidence. Authenticated production administrator is unverified; no role is counted fully verified across all surfaces. |
| APIs | UNVERIFIED / UNVERIFIED | Deno function tests and unauthenticated admin API 401 contracts passed. Production Auth/DB/function-to-current-source correspondence and live purchase/support E2E were not exhaustively verified. |
| Data structures | 66 / 66 migration files inventoried; 0 / 66 current-production replay verified in this phase | All migration files are listed in the file manifest. Existing historical linked migration evidence is retained, but no current destructive replay/restore was run against production. |
| Tests | Suite-level evidence, no deduplicated global-case denominator | S2 current: admin 19 PASS; usage guide 2 PASS; Deno 44 PASS; reset recovery 10 PASS; release-manifest 4 PASS; canonical tracker 10 PASS. Public/admin shell verifiers, contrast verifier, build/lint/typecheck and audits passed separately. S1 reports 400 Android host tests PASS and historical 179 iOS PASS + 3 live skips, but S2 did not rerun those mobile suites. |
| Devices | 0 / current physical-device matrix fully verified by S2 | Historical/accidental Android evidence and prior iPhone records exist. Current requested physical iPhone and final post-fix mobile UI proof are incomplete. The Share Extension's 20-second timeout was not validated under a real blocked synchronous save. |
| Browsers | 1 local browser engine + HTTP contracts / target browser denominator UNVERIFIED | Browser smoke and current screenshots cover one Chromium-like environment and responsive widths. Safari/Firefox/Edge/full OS matrix was not rerun. |
| Visual verification | UNVERIFIED / UNVERIFIED | Web admin has 320/1280/forced-colors artifacts; explicit dark mode is unsupported by design and is NOT_APPLICABLE, not verified. Mobile current screenshots are partial and not physical-device completion. No full all-screen/all-state visual denominator exists. |
| Major flows | UNVERIFIED / UNVERIFIED | Public password reset/invite/privacy and unauthenticated admin boundaries are verified. Mobile save/share/export flows have source/host/simulator evidence, but current physical Share Extension timeout/lifecycle and full production Auth/Store/purchase flows remain unverified. |

## Exact canonical tracker gates

- `android_device`: 4 stories.
- `iphone_device`: 4 stories.
- `distribution_signing`: 3 stories.
- `supabase_auth`: 21 stories.
- `store_console`: 6 stories.
- `resend_live`: 4 stories.
- `none`: 29 stories.

One story can contribute to more than one gate, so gate counts do not sum to 59.

## Excluded/ignored aggregate

- Root ignored paths: 31,772, counted exhaustively with `git ls-files --others --ignored --exclude-standard -z`; these are not individual manifest rows because they include reproducible Gradle/Next/npm/build/cache output and may be very large.
- Nested ignored paths: 29,542 by the same NUL-delimited Git method; predominantly `node_modules`/build output.
- Exclusion method: Git's active ignore rules, not a sample. Tracked and non-ignored untracked files are still exhaustively listed.
- Exclusion consequence: ignored build/cache/vendor paths are `OUT_OF_SCOPE` for the file manifest, not `VERIFIED`. Relevant test/build results are represented by durable logs and named output evidence instead.

## Unverified and inaccessible high-impact areas

- Root clean integration commit/branch and immutable final snapshot.
- Current production Supabase Auth email recovery, DB/schema/source correspondence, live Resend delivery, and backup restore.
- App Store Connect product-copy/localization editing and post-change public result.
- Google Play Data safety form correction/save/review/public result, active artifact/policy status, and final Play App Signing SHA-256 comparison.
- Historical GCP-key-shaped material restriction/rotation/deactivation in GCP Console.
- Current physical iPhone/Android full UI, Dynamic Type/VoiceOver/TalkBack, Store sandbox purchase/downgrade, and real Share Extension blocked-save timeout/lifecycle behavior.
- Fresh F1/F2/F3 audits and the required consecutive no-new-finding rounds; they were not run under the two-subagent cap.

## Coverage conclusion

Coverage status is `PARTIALLY_VERIFIED / INCOMPLETE_EVIDENCE`. Current release status is `NO_GO_INTERNAL / BLOCKED_EXTERNAL`; no percentage is used to disguise categories without an exact current denominator.
