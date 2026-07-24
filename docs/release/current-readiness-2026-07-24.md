# Current readiness — 2026-07-24

Status: `NO_GO_INTERNAL / NOT_VERIFIED_FOR_RELEASE`

This document describes the remediation diff based at commit
`e7c4163abb338fb9219fc4f91df4ea1d202f7f7b`. The remediation was committed as
`5567f1a2de7ab9dc972b968669882a47a083b240` on branch
`codex/android-main-proof-20260710` and pushed to GitHub. It is not a store,
production, physical-device, Supabase-applied, or OpenAI approval.

## Local scope

- Android canonical identity: `jp.miyamibu.urlalbum`, `1.0.15 (17)`.
- iOS canonical identity: `com.mibu.codebridge.ios`, `1.0.15 (17)`.
- Personal-link snapshot sync is fail-closed and disabled on Android/iOS.
- MCP personal data requires both explicit flags and remains disabled.
- Android release local media downloads are disabled; the iOS backend resolver is also compile-time disabled in Release builds.
- No deploy, store upload, production secret entry, or OpenAI submission was performed.

## Verification boundary

The readiness YAML beside this file is the machine-readable source for this
review. Its `commit` value is the baseline for `worktreeDiffSha256`;
`scripts/verify_current_readiness.py` accepts that baseline at the current HEAD
or an unchanged descendant, and rejects a drifted digest, identity drift, and
fail-open feature gates. Local tests and builds do not close the
following gates:

- Physical Android/iPhone UI, accessibility, and current screenshots.
- Supabase migration application, RLS, pgTAP execution, and scheduler behavior.
- Resolver/provider/webhook runtime, quotas, signed downloads, and production secrets.
- Store-signed candidates, upload/submission state, and OpenAI connector state.

Any failed or unavailable check keeps the status at `NO_GO_INTERNAL` or
`NOT_VERIFIED`; historical `REPO_GO` rows in `repo-go-evidence.md` are
superseded and must not be reused as current proof.
